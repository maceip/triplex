//! Baseline (no-AI) agent components.
//!
//! These are the neutral stand-ins that let the full pipeline — VAD, turn
//! FSM, reasoner host, TTS host, mixer, barge-in — run end-to-end on device
//! before real models are integrated. Nothing here pretends to be AI: the
//! ASR is a presence transcriber whose transcript is an honest duration
//! marker, the reasoner returns a fixed acknowledgement, and the TTS
//! synthesizes audible tone words.

use triplex_native_media::{FrameSlot, FRAME_SAMPLES, SAMPLE_RATE_HZ};

use crate::epoch::EpochDomain;
use crate::reasoner::{ReasonerModel, StepOut};
use crate::tts::{SynthChunkOut, TtsMark, TtsModel};
use crate::turn::{AsrEvt, AsrEventRing};
use crate::vad::AsrFrameRing;

/// Presence transcriber: tracks speech energy per frame, bumps the ASR
/// revision while speech evolves, and emits one stable partial after
/// sufficient trailing silence. Sole `ASR_REV` writer (§2.1).
pub struct BaselineAsr {
    energy_floor_rms: f32,
    min_utterance_ms: u32,
    stable_silence_ms: u32,
    speech_ms: u32,
    silence_ms: u32,
    last_rev: u32,
    transcript: String,
    stable_emitted: bool,
    pub event_overflows: u64,
}

impl BaselineAsr {
    pub fn new(energy_floor_rms: f32) -> Self {
        Self {
            energy_floor_rms,
            min_utterance_ms: 150,
            stable_silence_ms: 200,
            speech_ms: 0,
            silence_ms: 0,
            last_rev: 0,
            transcript: String::new(),
            stable_emitted: true,
            event_overflows: 0,
        }
    }

    /// Consume ASR-plane frames (from the VAD forward ring) and emit partial
    /// events. Runs on the session pump thread.
    pub fn poll(
        &mut self,
        asr_rx: &AsrFrameRing,
        events: &AsrEventRing,
        epoch: &EpochDomain<'_>,
    ) {
        while let Some(slot) = asr_rx.pop() {
            self.consume(&slot, events, epoch);
        }
    }

    fn consume(&mut self, slot: &FrameSlot, events: &AsrEventRing, epoch: &EpochDomain<'_>) {
        let len = usize::from(slot.header.len).min(FRAME_SAMPLES);
        let frame_ms = (len as u32 * 1_000) / SAMPLE_RATE_HZ;
        if rms(&slot.samples[..len]) >= self.energy_floor_rms {
            self.speech_ms += frame_ms;
            self.silence_ms = 0;
            self.stable_emitted = false;
            // Revision the evolving hypothesis at a bounded cadence so
            // within-turn speculation invalidates (§2.1).
            if self.speech_ms % 100 < frame_ms {
                self.last_rev = epoch.bump_rev();
                let event = AsrEvt::Partial {
                    rev: self.last_rev,
                    stable: false,
                    mono_ns: slot.header.mono_ns,
                };
                if events.push(event).is_err() {
                    self.event_overflows += 1;
                }
            }
        } else if !self.stable_emitted && self.speech_ms >= self.min_utterance_ms {
            self.silence_ms += frame_ms;
            if self.silence_ms >= self.stable_silence_ms {
                self.last_rev = epoch.bump_rev();
                self.transcript = format!("[utterance {} ms]", self.speech_ms);
                self.stable_emitted = true;
                self.speech_ms = 0;
                self.silence_ms = 0;
                let event = AsrEvt::Partial {
                    rev: self.last_rev,
                    stable: true,
                    mono_ns: slot.header.mono_ns,
                };
                if events.push(event).is_err() {
                    self.event_overflows += 1;
                }
            }
        }
    }

    /// Synchronous finalization for `TurnSink::finalize_asr`.
    pub fn finalize(&mut self) -> (u32, String) {
        (self.last_rev, self.transcript.clone())
    }
}

/// Fixed-acknowledgement reasoner: one chunk, then done.
pub struct ReflexReasoner {
    response: String,
    emitted: bool,
}

impl ReflexReasoner {
    pub fn new(response: &str) -> Self {
        Self {
            response: response.to_string(),
            emitted: false,
        }
    }
}

impl ReasonerModel for ReflexReasoner {
    fn begin(&mut self, _transcript: &str) {
        self.emitted = false;
    }

    fn decode_step(&mut self) -> StepOut {
        if self.emitted {
            StepOut::Done
        } else {
            self.emitted = true;
            StepOut::Chunk(self.response.clone())
        }
    }
}

/// Tone-word TTS: each whitespace-separated word becomes a 120 ms sine burst
/// (word-indexed frequency) plus a 30 ms gap, with word-end alignment marks.
pub struct ToneTts {
    word_index: u32,
}

pub const TONE_WORD_SAMPLES: usize = 1_920; // 120 ms
pub const TONE_GAP_SAMPLES: usize = 480; // 30 ms

impl ToneTts {
    pub fn new() -> Self {
        Self { word_index: 0 }
    }
}

impl Default for ToneTts {
    fn default() -> Self {
        Self::new()
    }
}

impl TtsModel for ToneTts {
    fn synth_chunk(
        &mut self,
        text: &str,
        pcm_out: &mut [i16],
        marks_out: &mut [TtsMark],
    ) -> SynthChunkOut {
        let mut sample = 0_usize;
        let mut marks = 0_usize;
        let mut text_end = 0_usize;
        for piece in text.split_inclusive(char::is_whitespace) {
            text_end += piece.len();
            if piece.trim().is_empty() {
                continue;
            }
            let needed = TONE_WORD_SAMPLES + TONE_GAP_SAMPLES;
            if sample + needed > pcm_out.len() || marks >= marks_out.len() {
                break;
            }
            let hz = 300.0 + 80.0 * f64::from(self.word_index % 8);
            for i in 0..TONE_WORD_SAMPLES {
                let phase = 2.0 * std::f64::consts::PI * hz * i as f64
                    / f64::from(SAMPLE_RATE_HZ);
                pcm_out[sample + i] = (phase.sin() * 8_000.0) as i16;
            }
            for i in 0..TONE_GAP_SAMPLES {
                pcm_out[sample + TONE_WORD_SAMPLES + i] = 0;
            }
            sample += needed;
            marks_out[marks] = TtsMark {
                sample_off: sample as u32,
                text_off: text_end as u32,
            };
            marks += 1;
            self.word_index += 1;
        }
        SynthChunkOut {
            samples: sample,
            marks,
        }
    }
}

fn rms(pcm: &[i16]) -> f32 {
    if pcm.is_empty() {
        return 0.0;
    }
    let sum_sq: f64 = pcm.iter().map(|s| f64::from(*s) * f64::from(*s)).sum();
    ((sum_sq / pcm.len() as f64).sqrt()) as f32
}

#[cfg(test)]
mod tests {
    use super::*;
    use triplex_native_media::{NativeMediaRuntime, ROUTE_DIRECT_PJSIP};

    #[test]
    fn presence_transcriber_emits_stable_partial_after_trailing_silence() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let epoch = EpochDomain::new(&media);
        let ring = AsrFrameRing::new();
        let events = AsrEventRing::new();
        let mut asr = BaselineAsr::new(100.0);

        let mut speech = FrameSlot::default();
        speech.header.len = FRAME_SAMPLES as u16;
        for (i, s) in speech.samples.iter_mut().enumerate() {
            *s = if (i / 8) % 2 == 0 { 3_000 } else { -3_000 };
        }
        let silence = {
            let mut slot = FrameSlot::default();
            slot.header.len = FRAME_SAMPLES as u16;
            slot
        };

        for _ in 0..30 {
            ring.push(speech).unwrap();
        }
        asr.poll(&ring, &events, &epoch);
        let mut saw_unstable = false;
        while let Some(AsrEvt::Partial { stable, .. }) = events.pop() {
            assert!(!stable);
            saw_unstable = true;
        }
        assert!(saw_unstable, "evolving partials while speaking");

        for _ in 0..25 {
            ring.push(silence).unwrap();
        }
        asr.poll(&ring, &events, &epoch);
        let mut stable_rev = None;
        while let Some(AsrEvt::Partial { rev, stable, .. }) = events.pop() {
            if stable {
                stable_rev = Some(rev);
            }
        }
        let rev = stable_rev.expect("stable partial after trailing silence");
        let (final_rev, transcript) = asr.finalize();
        assert_eq!(final_rev, rev);
        assert_eq!(transcript, "[utterance 300 ms]");
        assert_eq!(asr.event_overflows, 0);
    }

    #[test]
    fn tone_tts_emits_audible_words_with_marks() {
        let mut tts = ToneTts::new();
        let mut pcm = vec![0_i16; 32_000];
        let mut marks = [TtsMark::default(); 8];
        let out = tts.synth_chunk("task noted ", &mut pcm, &mut marks);
        assert_eq!(out.marks, 2);
        assert_eq!(out.samples, 2 * (TONE_WORD_SAMPLES + TONE_GAP_SAMPLES));
        assert!(pcm[..TONE_WORD_SAMPLES].iter().any(|s| s.abs() > 4_000));
        assert_eq!(marks[0].text_off, 5);
        assert_eq!(marks[1].text_off, 11);
    }
}
