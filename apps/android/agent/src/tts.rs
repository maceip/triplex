//! TTS host (RUNTIME_INVARIANTS.md §2.3).
//!
//! Consumes `(E, REV_FINAL)` plan chunks, synthesizes into a preallocated
//! buffer, frames the PCM into canonical 16 kHz i16 10 ms frames stamped
//! with the token's epoch, and publishes word-boundary alignment marks in
//! utterance coordinates for heard-state (§3.6). Checkpoints (doorbell +
//! lazy token check) run between synthesis chunks and inside every
//! backpressure wait, so a barge-in unblocks the host immediately — the
//! push itself is epoch-gated a second time inside `push_synth` (belt and
//! suspenders, §3.4).

use std::time::Duration;

use triplex_native_media::{NativeMediaRuntime, SpscRing, Stream, FLAG_FINAL_CHUNK, FRAME_SAMPLES};

use crate::cancel::Doorbell;
use crate::clock::Clock;
use crate::epoch::{EpochDomain, Token, REV_FINAL};
use crate::reasoner::PlanItem;

/// Word-end alignment mark in utterance coordinates (§3.6).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AlignmentMark {
    pub epoch: u32,
    /// Utterance sample offset at which the word has fully ended.
    pub utt_sample_off: u64,
    /// Byte offset (into the utterance text) just past the word.
    pub text_off: u32,
}

pub const MARKS_SLOTS: usize = 256;
/// Producer: TTS host. Consumer: TurnController.
pub type MarksRing = SpscRing<AlignmentMark, MARKS_SLOTS>;

/// Chunk-relative word-end mark produced by the model.
#[derive(Clone, Copy, Debug, Default)]
pub struct TtsMark {
    pub sample_off: u32,
    pub text_off: u32,
}

/// 2 s bound per synthesis chunk.
pub const MAX_CHUNK_SAMPLES: usize = 32_000;
pub const MAX_CHUNK_MARKS: usize = 64;

pub struct SynthChunkOut {
    pub samples: usize,
    pub marks: usize,
}

/// Synthesizes one text chunk into caller-provided storage; must not
/// allocate after warmup. Production plugs the on-device TTS here.
pub trait TtsModel {
    fn synth_chunk(
        &mut self,
        text: &str,
        pcm_out: &mut [i16],
        marks_out: &mut [TtsMark],
    ) -> SynthChunkOut;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SynthOutcome {
    Completed,
    Cancelled,
}

pub struct TtsHost<'a, 'm, M: TtsModel, C: Clock> {
    model: M,
    epoch: &'a EpochDomain<'m>,
    media: &'a NativeMediaRuntime,
    marks_tx: &'a MarksRing,
    clock: &'a C,
    doorbell: Doorbell,
    utt_epoch: u32,
    utt_sample_off: u64,
    utt_text_off: u32,
    pcm_buf: Box<[i16]>,
    marks_buf: [TtsMark; MAX_CHUNK_MARKS],
    pub marks_dropped: u64,
    pub backpressure_waits: u64,
}

impl<'a, 'm, M: TtsModel, C: Clock> TtsHost<'a, 'm, M, C> {
    pub fn new(
        model: M,
        epoch: &'a EpochDomain<'m>,
        media: &'a NativeMediaRuntime,
        marks_tx: &'a MarksRing,
        clock: &'a C,
        doorbell: Doorbell,
    ) -> Self {
        Self {
            model,
            epoch,
            media,
            marks_tx,
            clock,
            doorbell,
            utt_epoch: 0,
            utt_sample_off: 0,
            utt_text_off: 0,
            pcm_buf: vec![0_i16; MAX_CHUNK_SAMPLES].into_boxed_slice(),
            marks_buf: [TtsMark::default(); MAX_CHUNK_MARKS],
            marks_dropped: 0,
            backpressure_waits: 0,
        }
    }

    #[inline]
    fn cancelled(&self, token: Token) -> bool {
        self.doorbell.is_rung() || !self.epoch.valid(token)
    }

    /// Synthesize one plan chunk. One call is one §2.3 checkpoint interval.
    pub fn synth_item(&mut self, item: &PlanItem) -> SynthOutcome {
        let token = item.token;
        debug_assert_eq!(
            token.rev, REV_FINAL,
            "TTS consumes only (E, REV_FINAL) plan chunks"
        );
        if token.epoch != self.utt_epoch {
            // First chunk of a new epoch: re-arm the bell and reset the
            // utterance coordinate space.
            self.utt_epoch = token.epoch;
            self.utt_sample_off = 0;
            self.utt_text_off = 0;
            self.doorbell.reset();
        }
        if self.cancelled(token) {
            return SynthOutcome::Cancelled;
        }

        if item.last {
            // FINAL_CHUNK tail (zeroed, one frame) so the egress pump can
            // report `Drained` once the utterance has fully hit the wire.
            let tail = [0_i16; FRAME_SAMPLES];
            return if self.push_frame(&tail, FLAG_FINAL_CHUNK, token) {
                SynthOutcome::Completed
            } else {
                SynthOutcome::Cancelled
            };
        }

        let out = self
            .model
            .synth_chunk(&item.text, &mut self.pcm_buf, &mut self.marks_buf);
        let samples = out.samples.min(MAX_CHUNK_SAMPLES);

        // A chunk-end mark is emitted unconditionally, so heard-state degrades
        // to whole-segment retention when an engine cannot supply trustworthy
        // word timestamps. Kokoro and the high-level Qwen clone API cannot;
        // the retired Python pipeline resolved this by dropping partial
        // segments outright (IMPLEMENTATION_PLAN.md Item 3). Marks are a
        // lower bound on granularity: the last mark at or before the heard
        // watermark is always a safe retention point, whether it marks a word
        // or a whole segment.
        let chunk_end = AlignmentMark {
            epoch: token.epoch,
            utt_sample_off: self.utt_sample_off + samples as u64,
            text_off: self.utt_text_off + item.text.len() as u32,
        };

        // Publish marks before audio so heard-state can resolve any frame
        // the pump egresses immediately (§3.6).
        for mark in &self.marks_buf[..out.marks.min(MAX_CHUNK_MARKS)] {
            let absolute = AlignmentMark {
                epoch: token.epoch,
                utt_sample_off: self.utt_sample_off + u64::from(mark.sample_off),
                text_off: self.utt_text_off + mark.text_off,
            };
            if self.marks_tx.push(absolute).is_err() {
                self.marks_dropped += 1;
            }
        }
        // Skip when the model already marked the chunk boundary, so an engine
        // with real word marks does not emit a duplicate at the same offset.
        let already_marked = self.marks_buf[..out.marks.min(MAX_CHUNK_MARKS)]
            .last()
            .is_some_and(|last| u64::from(last.sample_off) == samples as u64);
        if !already_marked && self.marks_tx.push(chunk_end).is_err() {
            self.marks_dropped += 1;
        }

        let mut offset = 0_usize;
        let mut frame = [0_i16; FRAME_SAMPLES];
        while offset < samples {
            let len = (samples - offset).min(FRAME_SAMPLES);
            frame[..len].copy_from_slice(&self.pcm_buf[offset..offset + len]);
            if !self.push_frame(&frame[..len], 0, token) {
                return SynthOutcome::Cancelled;
            }
            offset += len;
        }
        self.utt_sample_off += samples as u64;
        self.utt_text_off += item.text.len() as u32;
        SynthOutcome::Completed
    }

    /// Push one canonical frame, waiting out mixer backpressure. Every wait
    /// iteration re-checks cancellation, so a barge-in unblocks immediately.
    fn push_frame(&mut self, pcm: &[i16], flags: u16, token: Token) -> bool {
        loop {
            if self.cancelled(token) {
                return false;
            }
            if self.media.synth_ring_full() {
                self.backpressure_waits += 1;
                std::thread::sleep(Duration::from_micros(200));
                continue;
            }
            if self
                .media
                .push_synth(pcm, self.clock.now_ns(), token.epoch, flags, Stream::Synth)
            {
                return true;
            }
            // Rejected: stale epoch (or a racing fill) — the next iteration's
            // cancellation check resolves it.
            std::thread::yield_now();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::TestClock;
    use triplex_native_media::{FLAG_SILENCE, ROUTE_DIRECT_PJSIP};

    struct OneWordModel;

    impl TtsModel for OneWordModel {
        fn synth_chunk(
            &mut self,
            text: &str,
            pcm_out: &mut [i16],
            marks_out: &mut [TtsMark],
        ) -> SynthChunkOut {
            let samples = 320.min(pcm_out.len());
            for s in &mut pcm_out[..samples] {
                *s = 900;
            }
            marks_out[0] = TtsMark {
                sample_off: samples as u32,
                text_off: text.len() as u32,
            };
            SynthChunkOut { samples, marks: 1 }
        }
    }

    #[test]
    fn frames_marks_and_final_tail_are_emitted_in_utterance_coordinates() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let epoch = EpochDomain::new(&media);
        let marks = MarksRing::new();
        let clock = TestClock::new();
        let mut host = TtsHost::new(
            OneWordModel,
            &epoch,
            &media,
            &marks,
            &clock,
            Doorbell::new(),
        );
        let token = epoch.token_final();

        let chunk = PlanItem {
            token,
            text: "hello ".into(),
            last: false,
        };
        assert_eq!(host.synth_item(&chunk), SynthOutcome::Completed);
        assert_eq!(host.synth_item(&chunk), SynthOutcome::Completed);
        let marker = PlanItem {
            token,
            text: String::new(),
            last: true,
        };
        assert_eq!(host.synth_item(&marker), SynthOutcome::Completed);

        // Marks are utterance-absolute across chunks.
        assert_eq!(
            marks.pop(),
            Some(AlignmentMark {
                epoch: token.epoch,
                utt_sample_off: 320,
                text_off: 6
            })
        );
        assert_eq!(
            marks.pop(),
            Some(AlignmentMark {
                epoch: token.epoch,
                utt_sample_off: 640,
                text_off: 12
            })
        );

        // Four audio frames then the FINAL_CHUNK tail; the tail must not be
        // mistaken for mixer silence fill.
        for _ in 0..4 {
            let slot = media.pop_synth_or_silence(0);
            assert_eq!(slot.header.flags & FLAG_SILENCE, 0);
            assert_eq!(slot.samples[0], 900);
        }
        let tail = media.pop_synth_or_silence(0);
        assert_ne!(tail.header.flags & FLAG_FINAL_CHUNK, 0);
        assert_eq!(tail.header.flags & FLAG_SILENCE, 0);
    }

    #[test]
    fn stale_token_cancels_instead_of_pushing() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let epoch = EpochDomain::new(&media);
        let marks = MarksRing::new();
        let clock = TestClock::new();
        let mut host = TtsHost::new(
            OneWordModel,
            &epoch,
            &media,
            &marks,
            &clock,
            Doorbell::new(),
        );
        let token = epoch.token_final();
        epoch.bump_epoch();

        let chunk = PlanItem {
            token,
            text: "hello ".into(),
            last: false,
        };
        assert_eq!(host.synth_item(&chunk), SynthOutcome::Cancelled);
        assert_ne!(
            media.pop_synth_or_silence(0).header.flags & FLAG_SILENCE,
            0,
            "no stale audio reached the synth queue"
        );
    }
}
