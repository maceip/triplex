//! VAD evaluation loop with far-echo gate (RUNTIME_INVARIANTS.md §3.1–§3.2).
//!
//! Runs on the VAD thread (§5: URGENT_AUDIO−1, not RT-tagged, but held to RT
//! discipline anyway: no allocation, no locks, no logging — counters only).
//! Per 10 ms capture frame: neural speech probability + energy gate, then the
//! far-echo cross-correlation against the playout reference, then k-of-n
//! onset/offset hysteresis. Capture frames are always forwarded to the ASR
//! ring — the capture plane is never flushed (§0); barge-in speech is the
//! next turn's input.

use triplex_native_media::agent::echo_ref::EchoRefBuffer;
use triplex_native_media::{FrameSlot, NativeMediaRuntime, SpscRing, FRAME_SAMPLES};

pub const ASR_RING_SLOTS: usize = 64;
pub const VAD_EVENT_SLOTS: usize = 64;

/// Producer: VAD loop. Consumer: ASR host.
pub type AsrFrameRing = SpscRing<FrameSlot, ASR_RING_SLOTS>;
/// Producer: VAD loop. Consumer: TurnController.
pub type VadEventRing = SpscRing<VadEvent, VAD_EVENT_SLOTS>;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpeechOnset {
    /// Timestamp of the first frame of the onset evidence window (§3.1: the
    /// onset is dated at first crossing, the decision after k frames).
    pub mono_ns: i64,
    pub conf: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum VadEvent {
    Onset(SpeechOnset),
    Offset { mono_ns: i64 },
}

/// Speech-probability model. Implementations must be allocation-free after
/// construction; production plugs the on-device NN here.
pub trait SpeechModel {
    fn prob(&mut self, pcm: &[i16]) -> f32;
}

/// Baseline energy-driven stand-in: monotonic in RMS, `rms / (rms + pivot)`.
#[derive(Default)]
pub struct EnergySpeechModel {
    pub pivot_rms: f32,
}

impl SpeechModel for EnergySpeechModel {
    fn prob(&mut self, pcm: &[i16]) -> f32 {
        let r = rms(pcm);
        r / (r + self.pivot_rms.max(1.0))
    }
}

#[derive(Clone, Copy, Debug)]
pub struct VadConfig {
    pub theta_on: f32,
    /// Consecutive speechy frames required for onset (§3.1: k = 2–4).
    pub k_onset: u32,
    /// Consecutive non-speech frames before offset (trailing-silence floor).
    pub k_offset: u32,
    pub energy_floor_rms: f32,
    /// Echo-gate correlation threshold (§3.2).
    pub theta_echo: f32,
    pub lag_min_samples: u64,
    pub lag_max_samples: u64,
    pub lag_step_samples: u64,
}

impl Default for VadConfig {
    fn default() -> Self {
        Self {
            theta_on: 0.85,
            k_onset: 2,
            k_offset: 20,
            energy_floor_rms: 100.0,
            theta_echo: 0.60,
            lag_min_samples: 640,   // 40 ms
            lag_max_samples: 6_400, // 400 ms
            lag_step_samples: 160,  // 10 ms grid
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct VadCounters {
    pub frames: u64,
    pub onsets: u64,
    pub offsets: u64,
    pub echo_suppressed: u64,
    /// Capture frames lost because the ASR ring stalled; must stay 0 (§0).
    pub asr_forward_drops: u64,
    pub event_overflows: u64,
}

pub struct VadLoop<'a, M: SpeechModel> {
    cfg: VadConfig,
    model: M,
    media: &'a NativeMediaRuntime,
    asr_tx: &'a AsrFrameRing,
    event_tx: &'a VadEventRing,
    echo_ref: &'a EchoRefBuffer,
    in_speech: bool,
    consec_on: u32,
    consec_off: u32,
    cand_start_ns: i64,
    cand_conf: f32,
    ref_scratch: [i16; FRAME_SAMPLES],
    counters: VadCounters,
}

impl<'a, M: SpeechModel> VadLoop<'a, M> {
    pub fn new(
        cfg: VadConfig,
        model: M,
        media: &'a NativeMediaRuntime,
        asr_tx: &'a AsrFrameRing,
        event_tx: &'a VadEventRing,
        echo_ref: &'a EchoRefBuffer,
    ) -> Self {
        Self {
            cfg,
            model,
            media,
            asr_tx,
            event_tx,
            echo_ref,
            in_speech: false,
            consec_on: 0,
            consec_off: 0,
            cand_start_ns: 0,
            cand_conf: 0.0,
            ref_scratch: [0; FRAME_SAMPLES],
            counters: VadCounters::default(),
        }
    }

    pub fn counters(&self) -> VadCounters {
        self.counters
    }

    pub fn in_speech(&self) -> bool {
        self.in_speech
    }

    /// Drain the capture ring: classify each frame, then forward it to ASR
    /// unconditionally. Allocation-free and bounded by ring capacity.
    pub fn poll(&mut self) {
        while let Some(slot) = self.media.pop_capture() {
            self.counters.frames += 1;
            let len = usize::from(slot.header.len).min(FRAME_SAMPLES);
            let mono_ns = slot.header.mono_ns;
            let speechy = {
                let pcm = &slot.samples[..len];
                let energy = rms(pcm);
                let prob = self.model.prob(pcm);
                let mut speechy = prob >= self.cfg.theta_on && energy >= self.cfg.energy_floor_rms;
                if speechy && self.is_far_echo(pcm) {
                    speechy = false;
                    self.counters.echo_suppressed += 1;
                }
                self.update_hysteresis(speechy, prob, mono_ns);
                speechy
            };
            let _ = speechy;
            if self.asr_tx.push(slot).is_err() {
                // §0: capture frames are never intentionally dropped. A full
                // ASR queue means the ASR host stalled far past its budget.
                self.counters.asr_forward_drops += 1;
                debug_assert!(false, "asr forward ring overflow");
            }
        }
    }

    fn update_hysteresis(&mut self, speechy: bool, prob: f32, mono_ns: i64) {
        if speechy {
            self.consec_off = 0;
            if self.consec_on == 0 {
                self.cand_start_ns = mono_ns;
                self.cand_conf = prob;
            } else {
                self.cand_conf = self.cand_conf.max(prob);
            }
            self.consec_on += 1;
            if !self.in_speech && self.consec_on >= self.cfg.k_onset {
                self.in_speech = true;
                self.counters.onsets += 1;
                let onset = SpeechOnset {
                    mono_ns: self.cand_start_ns,
                    conf: self.cand_conf,
                };
                if self.event_tx.push(VadEvent::Onset(onset)).is_err() {
                    self.counters.event_overflows += 1;
                }
            }
        } else {
            self.consec_on = 0;
            if self.in_speech {
                self.consec_off += 1;
                if self.consec_off >= self.cfg.k_offset {
                    self.in_speech = false;
                    self.counters.offsets += 1;
                    if self.event_tx.push(VadEvent::Offset { mono_ns }).is_err() {
                        self.counters.event_overflows += 1;
                    }
                }
            }
        }
    }

    /// §3.2: bounded normalized cross-correlation against the playout
    /// reference over the configured lag grid.
    fn is_far_echo(&mut self, pcm: &[i16]) -> bool {
        let mut lag = self.cfg.lag_min_samples;
        while lag <= self.cfg.lag_max_samples {
            let scratch = &mut self.ref_scratch[..pcm.len()];
            if self.echo_ref.read_lagged(lag, scratch) && ncc(pcm, scratch) > self.cfg.theta_echo {
                return true;
            }
            lag += self.cfg.lag_step_samples.max(1);
        }
        false
    }
}

fn rms(pcm: &[i16]) -> f32 {
    if pcm.is_empty() {
        return 0.0;
    }
    let sum_sq: f64 = pcm.iter().map(|s| f64::from(*s) * f64::from(*s)).sum();
    let mean = sum_sq / pcm.len() as f64;
    mean.sqrt() as f32
}

/// |normalized cross-correlation|; 0 when either side is near-silent.
fn ncc(a: &[i16], b: &[i16]) -> f32 {
    let mut dot = 0.0_f64;
    let mut aa = 0.0_f64;
    let mut bb = 0.0_f64;
    for (x, y) in a.iter().zip(b.iter()) {
        let (x, y) = (f64::from(*x), f64::from(*y));
        dot += x * y;
        aa += x * x;
        bb += y * y;
    }
    if aa < 1e3 || bb < 1e3 {
        return 0.0;
    }
    (dot.abs() / (aa.sqrt() * bb.sqrt())) as f32
}

#[cfg(test)]
mod tests {
    use super::*;
    use triplex_native_media::ROUTE_DIRECT_PJSIP;

    fn square_frame(amp: i16) -> [i16; FRAME_SAMPLES] {
        let mut frame = [0_i16; FRAME_SAMPLES];
        for (i, s) in frame.iter_mut().enumerate() {
            *s = if (i / 8) % 2 == 0 { amp } else { -amp };
        }
        frame
    }

    fn setup() -> (NativeMediaRuntime, AsrFrameRing, VadEventRing, EchoRefBuffer) {
        (
            NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP),
            AsrFrameRing::new(),
            VadEventRing::new(),
            EchoRefBuffer::new(16_384),
        )
    }

    #[test]
    fn onset_after_k_frames_then_offset_after_trailing_silence() {
        let (media, asr, events, echo) = setup();
        let mut vad = VadLoop::new(
            VadConfig::default(),
            EnergySpeechModel { pivot_rms: 200.0 },
            &media,
            &asr,
            &events,
            &echo,
        );

        let speech = square_frame(3_000);
        let silence = [0_i16; FRAME_SAMPLES];
        let mut pushed = 0_u64;
        let mut mono = 0_i64;
        for _ in 0..3 {
            assert!(media.push_capture(&speech, mono, 0));
            mono += 10_000_000;
            pushed += 1;
        }
        vad.poll();
        while asr.pop().is_some() {}
        let onset = events.pop().expect("onset after k=2 speechy frames");
        assert_eq!(onset, VadEvent::Onset(SpeechOnset { mono_ns: 0, conf: onset_conf(onset) }));
        assert!(events.pop().is_none(), "no duplicate onset while in speech");

        for _ in 0..25 {
            assert!(media.push_capture(&silence, mono, 0));
            mono += 10_000_000;
            pushed += 1;
            vad.poll();
            while asr.pop().is_some() {}
        }
        assert!(matches!(events.pop(), Some(VadEvent::Offset { .. })));

        let counters = vad.counters();
        assert_eq!(counters.frames, pushed);
        assert_eq!(counters.asr_forward_drops, 0);
        assert_eq!(counters.onsets, 1);
        assert_eq!(counters.offsets, 1);
    }

    fn onset_conf(event: VadEvent) -> f32 {
        match event {
            VadEvent::Onset(onset) => onset.conf,
            VadEvent::Offset { .. } => unreachable!(),
        }
    }

    #[test]
    fn far_echo_is_suppressed_but_novel_speech_is_not() {
        let (media, asr, events, echo) = setup();
        let mut vad = VadLoop::new(
            VadConfig::default(),
            EnergySpeechModel { pivot_rms: 200.0 },
            &media,
            &asr,
            &events,
            &echo,
        );

        // Simulate 8000 samples of egressed agent audio in the reference.
        let mut playout = Vec::with_capacity(8_000);
        for i in 0..8_000_usize {
            let phase = (i / 8) % 2 == 0;
            playout.push(if phase { 2_000_i16 } else { -2_000 });
        }
        echo.write(&playout);

        // Far-end echo: the capture frame replays a reference segment at a
        // 100 ms (1600-sample) lag — exactly on the search grid.
        let lag = 1_600_usize;
        let end = playout.len() - lag;
        let mut echo_frame = [0_i16; FRAME_SAMPLES];
        echo_frame.copy_from_slice(&playout[end - FRAME_SAMPLES..end]);
        for _ in 0..4 {
            assert!(media.push_capture(&echo_frame, 0, 0));
        }
        vad.poll();
        while asr.pop().is_some() {}
        assert!(events.pop().is_none(), "echo must not trigger onset");
        assert!(vad.counters().echo_suppressed >= 4);

        // Novel caller speech: pseudo-random, uncorrelated with the reference.
        let mut lcg = 0x2545_F491_u32;
        let mut noise = [0_i16; FRAME_SAMPLES];
        for s in &mut noise {
            lcg = lcg.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            *s = ((lcg >> 16) as i16 / 8).saturating_mul(3).saturating_add(1_000);
        }
        for _ in 0..3 {
            assert!(media.push_capture(&noise, 0, 0));
        }
        vad.poll();
        assert!(
            matches!(events.pop(), Some(VadEvent::Onset(_))),
            "novel speech must pass the echo gate"
        );
    }
}
