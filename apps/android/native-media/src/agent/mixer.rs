//! Paced egress pump (RUNTIME_INVARIANTS.md §2.3 mixer contract, §3.4–§3.5).
//!
//! The pump drives `NativeMediaRuntime::pop_synth_or_silence` — which already
//! performs the O(1) per-frame epoch gate and lazy stale drop — and adds the
//! egress-side obligations around it:
//!
//! - **Pacing invariant (§3.5):** egress never runs more than
//!   `PACING_AHEAD_NS` ahead of realtime. Audio handed to the transport is
//!   irrevocable, so this bound is what turns the 150 ms audible-stop budget
//!   into arithmetic.
//! - **Playout ledger (§3.6):** one record per egressed synthesis frame, in
//!   utterance sample coordinates, for the conservative heard watermark.
//! - **Echo reference (§3.2):** every egressed sample (synthesis and fill) is
//!   mirrored into the `EchoRefBuffer` so the VAD far-echo gate can correlate
//!   against what actually went to the wire.
//! - **Status events:** `FirstAudio`, `Drained`, and `FlushAck` for the
//!   TurnController FSM. Correctness never depends on these arriving — the
//!   epoch gate alone guarantees stale audio dies — they carry timing.
//!
//! `poll` is RT-safe: wait-free, allocation-free, bounded per call by the
//! pacing window (at most `PACING_AHEAD_NS / 10 ms` frames plus the bounded
//! stale drain inside `pop_synth_or_silence`).

use crate::agent::echo_ref::EchoRefBuffer;
use crate::agent::ledger::{PlayoutLedger, PlayoutRecord};
use crate::frame::{FLAG_FINAL_CHUNK, FLAG_SILENCE};
use crate::ring::SpscRing;
use crate::runtime::NativeMediaRuntime;

/// Nanoseconds of audio per canonical 16 kHz sample.
pub const NS_PER_SAMPLE: i64 = 62_500;
/// §3.5: egress never runs more than 40 ms ahead of realtime.
pub const PACING_AHEAD_NS: i64 = 40_000_000;
pub const STATUS_SLOTS: usize = 64;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MixerStatus {
    /// First frame of a new epoch's audio hit the wire.
    FirstAudio { epoch: u32, mono_ns: i64 },
    /// The FINAL_CHUNK frame of the current utterance hit the wire.
    Drained { epoch: u32, mono_ns: i64 },
    /// Every synthesis frame queued at interrupt time has been discarded;
    /// `epoch` is the post-interrupt epoch being acknowledged.
    FlushAck { epoch: u32, mono_ns: i64 },
}

/// Producer: egress pump. Consumer: TurnController.
pub type StatusRing = SpscRing<MixerStatus, STATUS_SLOTS>;

/// Hands PCM to the transport. Implementations must uphold RT constraints:
/// no allocation, locks, logging, or unbounded work.
pub trait EgressSink {
    fn send(&mut self, pcm: &[i16], rtp_ts: u32, egress_mono_ns: i64);
}

pub struct EgressPump<'a> {
    media: &'a NativeMediaRuntime,
    ledger_tx: &'a PlayoutLedger,
    status_tx: &'a StatusRing,
    echo_ref: &'a EchoRefBuffer,
    pacing_ahead_ns: i64,
    horizon_ns: i64,
    rtp_ts: u32,
    last_audio_epoch: u32,
    utt_sample_off: u64,
    last_flush_ack: u32,
    pub ledger_overflows: u64,
    pub status_overflows: u64,
}

impl<'a> EgressPump<'a> {
    pub fn new(
        media: &'a NativeMediaRuntime,
        ledger_tx: &'a PlayoutLedger,
        status_tx: &'a StatusRing,
        echo_ref: &'a EchoRefBuffer,
    ) -> Self {
        Self {
            media,
            ledger_tx,
            status_tx,
            echo_ref,
            pacing_ahead_ns: PACING_AHEAD_NS,
            horizon_ns: i64::MIN,
            rtp_ts: 0,
            last_audio_epoch: 0,
            utt_sample_off: 0,
            last_flush_ack: 0,
            ledger_overflows: 0,
            status_overflows: 0,
        }
    }

    /// Realtime position through which audio has been egressed.
    #[inline]
    pub fn horizon_ns(&self) -> i64 {
        self.horizon_ns
    }

    /// RT egress driver; call on every transport tick with the transport's
    /// monotonic clock. Wait-free, allocation-free, bounded.
    pub fn poll<S: EgressSink>(&mut self, now_ns: i64, sink: &mut S) {
        if self.horizon_ns == i64::MIN {
            self.horizon_ns = now_ns;
        }
        self.emit_flush_ack(now_ns);
        while self.horizon_ns < now_ns + self.pacing_ahead_ns {
            let slot = self.media.pop_synth_or_silence(self.horizon_ns);
            self.emit_flush_ack(now_ns);
            let len = usize::from(slot.header.len);
            if len == 0 {
                debug_assert!(false, "zero-length synth frame");
                continue;
            }
            let is_fill = slot.header.flags & FLAG_SILENCE != 0;

            sink.send(&slot.samples[..len], self.rtp_ts, self.horizon_ns);
            self.echo_ref.write(&slot.samples[..len]);

            if !is_fill {
                if slot.header.epoch != self.last_audio_epoch {
                    self.last_audio_epoch = slot.header.epoch;
                    self.utt_sample_off = 0;
                    self.push_status(MixerStatus::FirstAudio {
                        epoch: slot.header.epoch,
                        mono_ns: self.horizon_ns,
                    });
                }
                let record = PlayoutRecord {
                    epoch: slot.header.epoch,
                    seq: slot.header.seq,
                    egress_mono_ns: self.horizon_ns,
                    samples: slot.header.len,
                    utt_sample_off: self.utt_sample_off,
                };
                if self.ledger_tx.push(record).is_err() {
                    self.ledger_overflows += 1;
                }
                self.utt_sample_off += len as u64;
                if slot.header.flags & FLAG_FINAL_CHUNK != 0 {
                    self.push_status(MixerStatus::Drained {
                        epoch: slot.header.epoch,
                        mono_ns: self.horizon_ns,
                    });
                }
            }

            self.rtp_ts = self.rtp_ts.wrapping_add(u32::from(slot.header.len));
            self.horizon_ns += len as i64 * NS_PER_SAMPLE;
        }
    }

    fn emit_flush_ack(&mut self, now_ns: i64) {
        let ack = self.media.flush_ack_epoch();
        if ack != 0 && ack != self.last_flush_ack {
            self.last_flush_ack = ack;
            self.push_status(MixerStatus::FlushAck {
                epoch: ack,
                mono_ns: now_ns,
            });
        }
    }

    fn push_status(&mut self, status: MixerStatus) {
        if self.status_tx.push(status).is_err() {
            self.status_overflows += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::{Stream, FRAME_SAMPLES};
    use crate::runtime::ROUTE_DIRECT_PJSIP;

    struct VecSink {
        /// (rtp_ts, egress_mono_ns, len, first_sample)
        frames: Vec<(u32, i64, usize, i16)>,
    }

    impl EgressSink for VecSink {
        fn send(&mut self, pcm: &[i16], rtp_ts: u32, egress_mono_ns: i64) {
            self.frames.push((rtp_ts, egress_mono_ns, pcm.len(), pcm[0]));
        }
    }

    fn setup() -> (NativeMediaRuntime, PlayoutLedger, StatusRing, EchoRefBuffer) {
        (
            NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP),
            PlayoutLedger::new(),
            StatusRing::new(),
            EchoRefBuffer::new(16_384),
        )
    }

    #[test]
    fn pacing_never_runs_more_than_forty_ms_ahead() {
        let (media, ledger, status, echo) = setup();
        let mut pump = EgressPump::new(&media, &ledger, &status, &echo);
        let mut sink = VecSink { frames: Vec::new() };

        let epoch = media.epoch();
        let pcm = [7_i16; FRAME_SAMPLES];
        for _ in 0..8 {
            assert!(media.push_synth(&pcm, 0, epoch, 0, Stream::Synth));
        }

        pump.poll(0, &mut sink);
        // Exactly 40 ms of audio: four 10 ms frames, no silence fill.
        assert_eq!(sink.frames.len(), 4);
        assert!(sink.frames.iter().all(|f| f.3 == 7));
        assert_eq!(pump.horizon_ns(), PACING_AHEAD_NS);

        pump.poll(10_000_000, &mut sink);
        assert_eq!(sink.frames.len(), 5);
    }

    #[test]
    fn interrupt_drops_stale_audio_and_acknowledges_flush() {
        let (media, ledger, status, echo) = setup();
        let mut pump = EgressPump::new(&media, &ledger, &status, &echo);
        let mut sink = VecSink { frames: Vec::new() };

        let epoch = media.epoch();
        let pcm = [7_i16; FRAME_SAMPLES];
        for _ in 0..4 {
            assert!(media.push_synth(&pcm, 0, epoch, 0, Stream::Synth));
        }
        pump.poll(0, &mut sink); // plays the 4 queued frames
        assert_eq!(status.pop(), Some(MixerStatus::FirstAudio { epoch, mono_ns: 0 }));
        assert_eq!(ledger.pop().unwrap().utt_sample_off, 0);

        for _ in 0..4 {
            assert!(media.push_synth(&pcm, 0, epoch, 0, Stream::Synth));
        }
        let new_epoch = media.interrupt();
        pump.poll(10_000_000, &mut sink);

        // Everything after the interrupt is silence fill; the queued frames
        // were dropped, never ledgered, and the flush was acknowledged.
        assert!(sink.frames.iter().skip(4).all(|f| f.3 == 0));
        let mut saw_ack = false;
        while let Some(event) = status.pop() {
            if let MixerStatus::FlushAck { epoch, .. } = event {
                assert_eq!(epoch, new_epoch);
                saw_ack = true;
            }
        }
        assert!(saw_ack);
        assert_eq!(media.snapshot().synth_stale_frames, 4);
        // Only the four pre-interrupt frames were ledgered (one already popped).
        let mut remaining = 0;
        while ledger.pop().is_some() {
            remaining += 1;
        }
        assert_eq!(remaining, 3);
    }

    #[test]
    fn final_chunk_emits_drained_with_utterance_offsets() {
        let (media, ledger, status, echo) = setup();
        let mut pump = EgressPump::new(&media, &ledger, &status, &echo);
        let mut sink = VecSink { frames: Vec::new() };

        let epoch = media.epoch();
        let pcm = [5_i16; FRAME_SAMPLES];
        assert!(media.push_synth(&pcm, 0, epoch, 0, Stream::Synth));
        assert!(media.push_synth(&pcm, 0, epoch, FLAG_FINAL_CHUNK, Stream::Synth));
        pump.poll(0, &mut sink);

        assert_eq!(status.pop(), Some(MixerStatus::FirstAudio { epoch, mono_ns: 0 }));
        assert_eq!(
            status.pop(),
            Some(MixerStatus::Drained { epoch, mono_ns: 10_000_000 })
        );
        assert_eq!(ledger.pop().unwrap().utt_sample_off, 0);
        assert_eq!(ledger.pop().unwrap().utt_sample_off, 160);
    }
}
