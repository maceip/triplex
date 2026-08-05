//! Baseline agent session: the full local pipeline on real threads.
//!
//! Wires VAD → BaselineAsr → TurnController → ReflexReasoner → ToneTts →
//! synth queue → paced egress pump over one `NativeMediaRuntime`. This is
//! the delivery-sequence "neutral local agent" stage with baseline models;
//! real model hosts replace `ReflexReasoner`/`ToneTts`/`BaselineAsr` behind
//! the same traits.
//!
//! Egress note: the session's own pump consumes the synth ring and mirrors
//! egress into the echo reference/ledger. That makes it the synth-ring
//! consumer — it must not run concurrently with a live PJSIP call whose
//! conference port pops the same ring. The JNI layer only creates a session
//! while no call is active (loopback/demo mode).

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use triplex_native_media::agent::echo_ref::EchoRefBuffer;
use triplex_native_media::agent::ledger::PlayoutLedger;
use triplex_native_media::agent::mixer::{EgressPump, EgressSink, StatusRing};
use triplex_native_media::NativeMediaRuntime;

use crate::baseline::{BaselineAsr, ReflexReasoner, ToneTts};
use crate::cancel::{Doorbell, DoorbellRegistry};
use crate::clock::{Clock, RawMonotonicClock};
use crate::epoch::{EpochDomain, Token};
use crate::heard::HeardState;
use crate::reasoner::{PlanItem, ReasonerHost};
use crate::tts::{MarksRing, TtsHost};
use crate::turn::{AsrEventRing, TurnConfig, TurnController, TurnSink, TurnState, TurnWiring};
use crate::vad::{EnergySpeechModel, VadConfig, VadEventRing, VadLoop};

pub const STATE_STOPPED: u32 = 0;
pub const STATE_LISTENING: u32 = 1;
pub const STATE_ENDPOINTING: u32 = 2;
pub const STATE_COMMITTED: u32 = 3;
pub const STATE_SPEAKING: u32 = 4;
pub const STATE_YIELDING: u32 = 5;
pub const STATE_ENDED: u32 = 6;

/// Shared session counters; written by session threads, read via FFI.
#[derive(Default)]
pub struct SessionStatus {
    pub state: AtomicU32,
    pub epoch: AtomicU32,
    pub vad_onsets: AtomicU64,
    pub turns_committed: AtomicU64,
    pub utterances_completed: AtomicU64,
    pub barge_ins: AtomicU64,
    pub egressed_samples: AtomicU64,
    pub voiced_samples: AtomicU64,
}

pub struct SessionConfig {
    pub response_text: String,
    pub tick: Duration,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self {
            response_text: "acknowledged task noted ".to_string(),
            tick: Duration::from_millis(2),
        }
    }
}

struct SessionSink {
    jobs_tx: Option<mpsc::SyncSender<(String, Token)>>,
    asr: std::rc::Rc<std::cell::RefCell<BaselineAsr>>,
    status: Arc<SessionStatus>,
}

impl TurnSink for SessionSink {
    fn finalize_asr(&mut self) -> (u32, String) {
        self.asr.borrow_mut().finalize()
    }

    fn start_reasoning(&mut self, transcript: &str, token: Token, speculative: bool) {
        // The reflex reasoner is cheap; baseline skips speculative decode.
        if speculative {
            return;
        }
        self.status.turns_committed.fetch_add(1, Ordering::Relaxed);
        if let Some(tx) = &self.jobs_tx {
            let _ = tx.try_send((transcript.to_string(), token));
        }
    }

    fn promote_speculation(&mut self, _token: Token) -> bool {
        false
    }

    fn cancel_remote(&mut self, _old_epoch: u32) {}

    fn heard_state(&mut self, heard: HeardState) {
        if heard.interrupted {
            self.status.barge_ins.fetch_add(1, Ordering::Relaxed);
        } else {
            self.status
                .utterances_completed
                .fetch_add(1, Ordering::Relaxed);
        }
    }
}

struct CountingSink<'a> {
    status: &'a SessionStatus,
}

impl EgressSink for CountingSink<'_> {
    fn send(&mut self, pcm: &[i16], _rtp_ts: u32, _egress_mono_ns: i64) {
        self.status
            .egressed_samples
            .fetch_add(pcm.len() as u64, Ordering::Relaxed);
        if pcm.iter().any(|s| *s != 0) {
            self.status
                .voiced_samples
                .fetch_add(pcm.len() as u64, Ordering::Relaxed);
        }
    }
}

pub struct AgentSessionHandle {
    stop: Arc<AtomicBool>,
    status: Arc<SessionStatus>,
    join: Option<thread::JoinHandle<()>>,
}

impl AgentSessionHandle {
    pub fn status(&self) -> &Arc<SessionStatus> {
        &self.status
    }

    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
        self.status.state.store(STATE_STOPPED, Ordering::Release);
    }
}

impl Drop for AgentSessionHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

fn fsm_state(turn: TurnState) -> u32 {
    match turn {
        TurnState::Listening => STATE_LISTENING,
        TurnState::Endpointing => STATE_ENDPOINTING,
        TurnState::Committed => STATE_COMMITTED,
        TurnState::Speaking => STATE_SPEAKING,
        TurnState::Yielding => STATE_YIELDING,
        TurnState::Ended => STATE_ENDED,
    }
}

/// Spawn the session over `media`.
///
/// # Safety contract (FFI)
/// `media` must outlive the returned handle; the JNI layer guarantees the
/// agent session is destroyed before the media runtime.
pub fn spawn(media: &'static NativeMediaRuntime, config: SessionConfig) -> AgentSessionHandle {
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(SessionStatus::default());
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);

    let join = thread::Builder::new()
        .name("triplex-agent-session".into())
        .spawn(move || run_session(media, config, &thread_stop, &thread_status))
        .expect("spawn agent session thread");

    AgentSessionHandle {
        stop,
        status,
        join: Some(join),
    }
}

fn run_session(
    media: &'static NativeMediaRuntime,
    config: SessionConfig,
    stop: &AtomicBool,
    status: &Arc<SessionStatus>,
) {
    let clock = RawMonotonicClock;
    let echo_ref = EchoRefBuffer::new(16_384);
    let ledger = PlayoutLedger::new();
    let mixer_status = StatusRing::new();
    let marks = MarksRing::new();
    let asr_frames = crate::vad::AsrFrameRing::new();
    let vad_events = VadEventRing::new();
    let asr_events = AsrEventRing::new();
    let doorbells = DoorbellRegistry::new(4);
    let epoch = EpochDomain::new(media);

    let (jobs_tx, jobs_rx) = mpsc::sync_channel::<(String, Token)>(8);
    let (plan_tx, plan_rx) = mpsc::sync_channel::<PlanItem>(64);
    let reasoner_bell = Doorbell::new();
    let tts_bell = Doorbell::new();
    doorbells.register(&reasoner_bell).expect("doorbell slot");
    doorbells.register(&tts_bell).expect("doorbell slot");

    let asr = std::rc::Rc::new(std::cell::RefCell::new(BaselineAsr::new(100.0)));

    thread::scope(|scope| {
        let epoch_ref = &epoch;
        let media_ref = media;
        let marks_ref = &marks;
        let clock_ref = &clock;
        let response = config.response_text.clone();

        scope.spawn(move || {
            let mut host = ReasonerHost::new(
                ReflexReasoner::new(&response),
                epoch_ref,
                reasoner_bell,
                plan_tx,
            );
            for (transcript, token) in jobs_rx.iter() {
                let _ = host.run_turn(&transcript, token);
            }
        });
        scope.spawn(move || {
            let mut host = TtsHost::new(
                ToneTts::new(),
                epoch_ref,
                media_ref,
                marks_ref,
                clock_ref,
                tts_bell,
            );
            for item in plan_rx.iter() {
                let _ = host.synth_item(&item);
            }
        });

        // Pump loop on this thread: VAD, baseline ASR, turn FSM, egress.
        let mut vad = VadLoop::new(
            VadConfig::default(),
            EnergySpeechModel { pivot_rms: 200.0 },
            media,
            &asr_frames,
            &vad_events,
            &echo_ref,
        );
        let mut pump = EgressPump::new(media, &ledger, &mixer_status, &echo_ref);
        let mut turn = TurnController::new(
            &epoch,
            TurnConfig::default(),
            TurnWiring {
                vad_rx: &vad_events,
                asr_rx: &asr_events,
                status_rx: &mixer_status,
                ledger_rx: &ledger,
                marks_rx: &marks,
            },
            &doorbells,
            SessionSink {
                jobs_tx: Some(jobs_tx),
                asr: std::rc::Rc::clone(&asr),
                status: Arc::clone(status),
            },
        );
        let mut sink = CountingSink { status };

        status.state.store(STATE_LISTENING, Ordering::Release);
        while !stop.load(Ordering::Acquire) {
            let now = clock.now_ns();
            vad.poll();
            asr.borrow_mut().poll(&asr_frames, &asr_events, &epoch);
            turn.poll(now);
            pump.poll(now, &mut sink);

            status.state.store(fsm_state(turn.state()), Ordering::Relaxed);
            status.epoch.store(epoch.current_epoch(), Ordering::Relaxed);
            status
                .vad_onsets
                .store(vad.counters().onsets, Ordering::Relaxed);
            thread::sleep(config.tick);
        }
        turn.hangup();
        // Dropping `turn` releases jobs_tx → reasoner exits → plan_tx drops →
        // TTS exits → scope joins.
        drop(turn);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use triplex_native_media::{FRAME_SAMPLES, ROUTE_DIRECT_PJSIP, SAMPLE_RATE_HZ};

    fn wait_for<F: Fn() -> bool>(what: &str, timeout: Duration, predicate: F) {
        let start = std::time::Instant::now();
        while !predicate() {
            assert!(start.elapsed() < timeout, "timeout waiting for {what}");
            thread::sleep(Duration::from_millis(5));
        }
    }

    /// Paces 10 ms speech/silence frames into the capture ring in real time.
    fn inject(media: &NativeMediaRuntime, clock: &RawMonotonicClock, amp: i16, frames: u32) {
        let mut frame = [0_i16; FRAME_SAMPLES];
        for (i, s) in frame.iter_mut().enumerate() {
            *s = if (i / 8) % 2 == 0 { amp } else { -amp };
        }
        for n in 0..frames {
            while media.push_capture(&frame, clock.now_ns(), n * FRAME_SAMPLES as u32) == false
            {
                thread::sleep(Duration::from_millis(1));
            }
            thread::sleep(Duration::from_millis(10));
        }
        let _ = SAMPLE_RATE_HZ;
    }

    #[test]
    fn full_turn_and_barge_in_on_real_threads() {
        let media: &'static NativeMediaRuntime =
            Box::leak(Box::new(NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP)));
        let clock = RawMonotonicClock;
        let mut session = spawn(media, SessionConfig::default());
        let status = Arc::clone(session.status());

        wait_for("session start", Duration::from_secs(2), || {
            status.state.load(Ordering::Acquire) == STATE_LISTENING
        });

        // One utterance: 300 ms speech, then silence until the agent speaks.
        inject(media, &clock, 3_000, 30);
        inject(media, &clock, 0, 40);
        wait_for("turn commit", Duration::from_secs(5), || {
            status.turns_committed.load(Ordering::Relaxed) >= 1
        });
        wait_for("agent speech on the wire", Duration::from_secs(5), || {
            status.voiced_samples.load(Ordering::Relaxed) > 0
        });

        // Barge in while the agent is speaking (three tone words ≈ 450 ms).
        inject(media, &clock, 3_000, 25);
        wait_for("barge-in heard-state", Duration::from_secs(5), || {
            status.barge_ins.load(Ordering::Relaxed) >= 1
        });
        let epoch_after_barge = status.epoch.load(Ordering::Relaxed);
        assert!(epoch_after_barge >= 2, "epoch advanced by barge-in");

        // The barged utterance itself ends and commits a second turn.
        inject(media, &clock, 0, 40);
        wait_for("second turn", Duration::from_secs(5), || {
            status.turns_committed.load(Ordering::Relaxed) >= 2
        });

        session.stop();
        assert_eq!(status.state.load(Ordering::Acquire), STATE_STOPPED);
        let metrics = media.snapshot();
        assert_eq!(metrics.capture_dropped_frames, 0, "capture plane intact");
        assert!(metrics.synth_stale_frames > 0, "barge-in flushed synthesis");
    }
}
