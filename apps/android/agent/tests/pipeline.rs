//! End-to-end barge-in pipeline test (RUNTIME_INVARIANTS.md §2–§3).
//!
//! Full wiring on real threads: capture adapter → VAD (+echo gate) →
//! TurnController → reasoner thread → TTS thread → synth queue → paced
//! egress pump → playout ledger/status → back into the TurnController.
//! Exercises: endpointing, commit, first-audio, ~300 ms of agent speech,
//! a caller barge-in, the ordered publication protocol, flush-ack, and the
//! conservative heard-state — while asserting the capture plane never
//! loses a frame.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, SyncSender};
use std::thread;
use std::time::Duration;

use triplex_agent_core::cancel::{Doorbell, DoorbellRegistry};
use triplex_agent_core::clock::{Clock, TestClock};
use triplex_agent_core::epoch::{EpochDomain, Token};
use triplex_agent_core::heard::HeardState;
use triplex_agent_core::reasoner::{PlanItem, ReasonerHost, ReasonerModel, StepOut};
use triplex_agent_core::tts::{
    MarksRing, SynthChunkOut, SynthOutcome, TtsHost, TtsMark, TtsModel,
};
use triplex_agent_core::turn::{
    AsrEvt, AsrEventRing, TurnConfig, TurnController, TurnSink, TurnState, TurnWiring,
};
use triplex_agent_core::vad::{
    AsrFrameRing, EnergySpeechModel, VadConfig, VadEventRing, VadLoop,
};
use triplex_native_media::agent::echo_ref::EchoRefBuffer;
use triplex_native_media::agent::ledger::PlayoutLedger;
use triplex_native_media::agent::mixer::{EgressPump, EgressSink, StatusRing};
use triplex_native_media::{NativeMediaRuntime, FRAME_SAMPLES, ROUTE_DIRECT_PJSIP};

struct ScriptedReasoner {
    chunks: Vec<String>,
    next: usize,
}

impl ReasonerModel for ScriptedReasoner {
    fn begin(&mut self, _transcript: &str) {
        self.next = 0;
    }

    fn decode_step(&mut self) -> StepOut {
        if self.next < self.chunks.len() {
            let chunk = self.chunks[self.next].clone();
            self.next += 1;
            StepOut::Chunk(chunk)
        } else {
            StepOut::Done
        }
    }
}

/// 100 ms of constant-amplitude audio per word, with a word-end mark.
struct WordTts {
    samples_per_word: usize,
}

impl TtsModel for WordTts {
    fn synth_chunk(
        &mut self,
        text: &str,
        pcm_out: &mut [i16],
        marks_out: &mut [TtsMark],
    ) -> SynthChunkOut {
        let mut sample = 0_usize;
        let mut marks = 0_usize;
        let mut text_end = 0_usize;
        let mut word_index = 0_usize;
        for piece in text.split_inclusive(' ') {
            text_end += piece.len();
            if piece.trim().is_empty() {
                continue;
            }
            let n = self.samples_per_word.min(pcm_out.len() - sample);
            let amp = (500 * ((word_index % 7) + 1)) as i16;
            for s in &mut pcm_out[sample..sample + n] {
                *s = amp;
            }
            sample += n;
            if marks < marks_out.len() {
                marks_out[marks] = TtsMark {
                    sample_off: sample as u32,
                    text_off: text_end as u32,
                };
                marks += 1;
            }
            word_index += 1;
        }
        SynthChunkOut { samples: sample, marks }
    }
}

struct PipelineSink {
    jobs_tx: Option<SyncSender<(String, Token)>>,
    final_rev: u32,
    transcript: String,
    heard: Vec<HeardState>,
    remote_cancels: u32,
}

impl TurnSink for PipelineSink {
    fn finalize_asr(&mut self) -> (u32, String) {
        (self.final_rev, self.transcript.clone())
    }

    fn start_reasoning(&mut self, transcript: &str, token: Token, speculative: bool) {
        if !speculative {
            if let Some(tx) = &self.jobs_tx {
                let _ = tx.send((transcript.to_string(), token));
            }
        }
    }

    fn promote_speculation(&mut self, _token: Token) -> bool {
        false
    }

    fn cancel_remote(&mut self, _old_epoch: u32) {
        self.remote_cancels += 1;
    }

    fn heard_state(&mut self, heard: HeardState) {
        self.heard.push(heard);
    }
}

#[derive(Default)]
struct CountingSink {
    sent_samples: u64,
    voiced_samples: u64,
}

impl EgressSink for CountingSink {
    fn send(&mut self, pcm: &[i16], _rtp_ts: u32, _egress_mono_ns: i64) {
        self.sent_samples += pcm.len() as u64;
        if pcm.iter().any(|s| *s != 0) {
            self.voiced_samples += pcm.len() as u64;
        }
    }
}

fn square_frame(amp: i16) -> [i16; FRAME_SAMPLES] {
    let mut frame = [0_i16; FRAME_SAMPLES];
    for (i, s) in frame.iter_mut().enumerate() {
        *s = if (i / 8) % 2 == 0 { amp } else { -amp };
    }
    frame
}

#[test]
fn barge_in_cancels_synthesis_preserves_capture_and_records_heard_state() {
    let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
    let epoch = EpochDomain::new(&media);
    let clock = TestClock::new();
    let echo = EchoRefBuffer::new(16_384);
    let ledger = PlayoutLedger::new();
    let status = StatusRing::new();
    let marks = MarksRing::new();
    let asr_ring = AsrFrameRing::new();
    let vad_events = VadEventRing::new();
    let asr_events = AsrEventRing::new();
    let bells = DoorbellRegistry::new(8);

    let (jobs_tx, jobs_rx) = mpsc::sync_channel::<(String, Token)>(4);
    let (plan_tx, plan_rx) = mpsc::sync_channel::<PlanItem>(64);

    let reasoner_bell = Doorbell::new();
    let tts_bell = Doorbell::new();
    bells.register(&reasoner_bell).unwrap();
    bells.register(&tts_bell).unwrap();

    let tts_cancellations = AtomicU64::new(0);
    let reasoner_done = AtomicBool::new(false);
    let tts_done = AtomicBool::new(false);

    // 30 words ≈ 3 s of audio in three chunks — far more than will play.
    let response: Vec<String> = (0..3)
        .map(|chunk| {
            (0..10)
                .map(|word| format!("word{:02} ", chunk * 10 + word))
                .collect::<String>()
        })
        .collect();
    let total_text_len: usize = response.iter().map(String::len).sum();

    let mut vad = VadLoop::new(
        VadConfig::default(),
        EnergySpeechModel { pivot_rms: 200.0 },
        &media,
        &asr_ring,
        &vad_events,
        &echo,
    );
    let mut pump = EgressPump::new(&media, &ledger, &status, &echo);
    let mut turn = TurnController::new(
        &epoch,
        TurnConfig::default(),
        TurnWiring {
            vad_rx: &vad_events,
            asr_rx: &asr_events,
            status_rx: &status,
            ledger_rx: &ledger,
            marks_rx: &marks,
        },
        &bells,
        PipelineSink {
            jobs_tx: Some(jobs_tx),
            final_rev: 0,
            transcript: "book a table for two".into(),
            heard: Vec::new(),
            remote_cancels: 0,
        },
    );
    let mut sink = CountingSink::default();
    let mut captured = 0_u64;
    let mut asr_seen = 0_u64;

    let epoch_ref = &epoch;
    let media_ref = &media;
    let marks_ref = &marks;
    let clock_ref = &clock;
    let reasoner_done_ref = &reasoner_done;
    let tts_done_ref = &tts_done;
    let tts_cancellations_ref = &tts_cancellations;
    let chunks = response.clone();
    let reasoner_worker_bell = reasoner_bell.clone();
    let tts_worker_bell = tts_bell.clone();

    thread::scope(|scope| {
        scope.spawn(move || {
            let mut host =
                ReasonerHost::new(ScriptedReasoner { chunks, next: 0 }, epoch_ref, reasoner_worker_bell, plan_tx);
            for (text, token) in jobs_rx.iter() {
                let _ = host.run_turn(&text, token);
            }
            reasoner_done_ref.store(true, Ordering::Release);
        });
        scope.spawn(move || {
            let mut host = TtsHost::new(
                WordTts { samples_per_word: 1_600 },
                epoch_ref,
                media_ref,
                marks_ref,
                clock_ref,
                tts_worker_bell,
            );
            for item in plan_rx.iter() {
                if host.synth_item(&item) == SynthOutcome::Cancelled {
                    tts_cancellations_ref.fetch_add(1, Ordering::Relaxed);
                }
            }
            tts_done_ref.store(true, Ordering::Release);
        });

        // One 10 ms transport tick of the whole main-thread pipeline.
        macro_rules! tick {
            ($amp:expr) => {{
                clock.advance(10_000_000);
                let now = clock.now_ns();
                let frame = square_frame($amp);
                assert!(media.push_capture(&frame, now, (captured * 160) as u32));
                captured += 1;
                vad.poll();
                while asr_ring.pop().is_some() {
                    asr_seen += 1;
                }
                turn.poll(now);
                pump.poll(now, &mut sink);
                thread::sleep(Duration::from_micros(300));
            }};
        }

        // Caller speaks, then goes quiet: onset then offset.
        for _ in 0..5 {
            tick!(0);
        }
        for _ in 0..4 {
            tick!(3_000);
        }
        for _ in 0..25 {
            tick!(0);
        }
        assert_eq!(turn.state(), TurnState::Listening);

        // Stable ASR partial opens the endpoint window; the fast (80 ms)
        // deadline commits the turn and dispatches authoritative reasoning.
        let rev = epoch.bump_rev();
        turn.sink_mut().final_rev = rev;
        asr_events
            .push(AsrEvt::Partial { rev, stable: true, mono_ns: clock.now_ns() })
            .unwrap();
        tick!(0);
        assert_eq!(turn.state(), TurnState::Endpointing);
        for _ in 0..12 {
            tick!(0);
        }

        // Wait for the first agent audio to hit the wire.
        let mut speaking = false;
        for _ in 0..3_000 {
            tick!(0);
            if turn.state() == TurnState::Speaking {
                speaking = true;
                break;
            }
        }
        assert!(speaking, "pipeline never reached SPEAKING");

        // ~300 ms of agent speech reaches the caller.
        for _ in 0..30 {
            tick!(0);
        }

        // Caller barges in.
        let epoch_before = epoch.current_epoch();
        for _ in 0..4 {
            tick!(3_000);
        }
        let mut listening = false;
        for _ in 0..200 {
            tick!(0);
            if turn.state() == TurnState::Listening {
                listening = true;
                break;
            }
        }
        assert!(listening, "barge-in never completed");

        // Publication happened exactly once and synthesis died.
        assert_eq!(epoch.current_epoch(), epoch_before + 1);
        assert!(reasoner_bell.is_rung() || reasoner_done.load(Ordering::Acquire));
        let metrics = media.snapshot();
        assert!(metrics.synth_stale_frames > 0, "queued synthesis was flushed");

        // Capture plane survived the flush untouched.
        assert_eq!(metrics.capture_dropped_frames, 0);
        assert_eq!(asr_seen, captured, "every capture frame reached ASR");
        assert_eq!(vad.counters().asr_forward_drops, 0);

        // Conservative heard-state: something was heard, but not everything.
        let sink_ref = turn.sink_mut();
        assert_eq!(sink_ref.remote_cancels, 1);
        assert_eq!(sink_ref.heard.len(), 1);
        let heard = sink_ref.heard[0];
        assert!(heard.interrupted);
        assert!(!heard.reopen_segment);
        assert!(heard.heard_samples >= 1_600, "at least one word was heard");
        assert!(heard.heard_text_end >= 7);
        assert!((heard.heard_text_end as usize) < total_text_len);
        assert_eq!(heard.unspoken_from, heard.heard_text_end);

        // The TTS host observed the cancellation mid-utterance.
        assert!(tts_cancellations.load(Ordering::Relaxed) >= 1);

        // Teardown: close the job channel and pump until workers exit.
        sink_ref.jobs_tx = None;
        let mut workers_done = false;
        for _ in 0..2_000 {
            if reasoner_done.load(Ordering::Acquire) && tts_done.load(Ordering::Acquire) {
                workers_done = true;
                break;
            }
            tick!(0);
        }
        assert!(workers_done, "worker threads failed to exit");
    });
}
