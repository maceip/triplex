//! A multi-turn conversation on the real runtime.
//!
//! `pipeline.rs` proves one turn and one barge-in. This proves the thing a
//! phone call actually is: several exchanges in a row, on real threads, over
//! one `NativeMediaRuntime`, with a caller who interrupts partway through and
//! then keeps talking.
//!
//! What that catches which a single turn does not:
//!
//! * **Epoch reuse.** The epoch is the response-cycle id and it advances on
//!   every entry to LISTENING. Over one turn any monotonic counter looks
//!   right; over five, an off-by-one lets turn N's audio play into turn N+1.
//! * **Per-turn heard-state.** Every turn has to commit exactly one heard
//!   record, tagged with its own epoch. A leaked record from a previous turn is
//!   what makes an agent repeat a sentence the caller already heard.
//! * **Capture continuity across barge-in.** The flush at barge-in targets the
//!   synthesis path. Over a single interruption a dropped capture frame hides
//!   in the noise; over a whole conversation it is the difference between the
//!   ASR hearing the caller and hearing part of them.
//! * **Thread-lifetime accumulation.** The reasoner and TTS hosts live for the
//!   whole call. Anything they accumulate per turn — ring pressure, marks
//!   history, alignment offsets — only shows up after several.
//!
//! Every frame here is real 16 kHz PCM pushed through the real capture ring;
//! nothing is stubbed below the model traits.

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

/// One reply per turn, so a turn that reuses the previous one is visible in
/// the alignment marks rather than hidden behind identical text.
struct PerTurnReasoner {
    replies: Vec<&'static str>,
    turn: usize,
    emitted: bool,
}

impl ReasonerModel for PerTurnReasoner {
    fn begin(&mut self, _transcript: &str) {
        self.emitted = false;
    }

    fn decode_step(&mut self) -> StepOut {
        if self.emitted {
            StepOut::Done
        } else {
            self.emitted = true;
            let reply = self.replies[self.turn.min(self.replies.len() - 1)];
            self.turn += 1;
            StepOut::Chunk(reply.to_string())
        }
    }
}

/// 100 ms of audible tone per word, with a word-end alignment mark.
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
            // Flat per word, and deliberately *not* the caller's square wave.
            // The VAD gates capture against an echo reference, so a fixture
            // whose agent audio has the same period as its caller audio has
            // the agent's own echo cancel the barge-in — the pipeline behaves
            // correctly and the test silently stops testing anything.
            let amp = (500 * ((word_index % 7) + 1)) as i16;
            for s in &mut pcm_out[sample..sample + n] {
                *s = amp;
            }
            word_index += 1;
            sample += n;
            if marks < marks_out.len() {
                marks_out[marks] = TtsMark {
                    sample_off: sample as u32,
                    text_off: text_end as u32,
                };
                marks += 1;
            }
        }
        SynthChunkOut { samples: sample, marks }
    }
}

struct ConversationSink {
    jobs_tx: Option<SyncSender<(String, Token)>>,
    final_rev: u32,
    transcript: String,
    heard: Vec<HeardState>,
    remote_cancels: u32,
    dispatched: Vec<Token>,
}

impl TurnSink for ConversationSink {
    fn finalize_asr(&mut self) -> (u32, String) {
        (self.final_rev, self.transcript.clone())
    }

    fn start_reasoning(&mut self, transcript: &str, token: Token, speculative: bool) {
        if speculative {
            return;
        }
        self.dispatched.push(token);
        if let Some(tx) = &self.jobs_tx {
            let _ = tx.send((transcript.to_string(), token));
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

/// Everything the conversation did, collected before the worker threads are
/// joined so the assertions can run outside `thread::scope`.
struct Observed {
    hist_overflows: u64,
    flush_ack_timeouts: u64,
    heard: Vec<HeardState>,
    dispatched: Vec<Token>,
    remote_cancels: u32,
    epochs_at_commit: Vec<u32>,
    barge_in_turns: Vec<usize>,
    metrics_capture_dropped: u64,
    metrics_synth_stale: u64,
    asr_forward_drops: u64,
    captured: u64,
    asr_seen: u64,
    tts_cancellations: u64,
    sent_samples: u64,
    voiced_samples: u64,
}

/// What the caller does on one turn.
struct Exchange {
    /// Frames of speech the caller produces before going quiet.
    speech_frames: usize,
    /// Interrupt the agent this many 10 ms ticks after it starts speaking;
    /// `None` lets the agent finish.
    barge_in_after_ticks: Option<usize>,
}

#[test]
fn a_five_turn_conversation_keeps_epochs_heard_state_and_capture_intact() {
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

    let reasoner_done = AtomicBool::new(false);
    let tts_done = AtomicBool::new(false);
    let tts_cancellations = AtomicU64::new(0);

    // Five distinct replies, long enough that an interruption lands
    // mid-utterance rather than after the last word.
    let replies = vec![
        "alpha alpha alpha alpha alpha alpha alpha alpha ",
        "bravo bravo bravo bravo bravo bravo bravo bravo ",
        "charlie charlie charlie charlie charlie charlie charlie charlie ",
        "delta delta delta delta delta delta delta delta ",
        "echo echo echo echo echo echo echo echo ",
    ];

    // A real conversation: the caller cuts the agent off twice, in the middle
    // and again near the end, and finishes their last turn uninterrupted.
    let exchanges = [
        Exchange { speech_frames: 6, barge_in_after_ticks: None },
        Exchange { speech_frames: 4, barge_in_after_ticks: Some(20) },
        Exchange { speech_frames: 5, barge_in_after_ticks: None },
        Exchange { speech_frames: 4, barge_in_after_ticks: Some(12) },
        Exchange { speech_frames: 6, barge_in_after_ticks: None },
    ];

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
        ConversationSink {
            jobs_tx: Some(jobs_tx),
            final_rev: 0,
            transcript: "caller turn".into(),
            heard: Vec::new(),
            remote_cancels: 0,
            dispatched: Vec::new(),
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
    let reasoner_worker_bell = reasoner_bell.clone();
    let tts_worker_bell = tts_bell.clone();
    let worker_replies = replies.clone();

    let observed = thread::scope(|scope| {
        scope.spawn(move || {
            let mut host = ReasonerHost::new(
                PerTurnReasoner { replies: worker_replies, turn: 0, emitted: false },
                epoch_ref,
                reasoner_worker_bell,
                plan_tx,
            );
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

        /// One 10 ms transport tick of the whole main-thread pipeline.
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
                thread::sleep(Duration::from_micros(200));
            }};
        }

        let mut epochs_at_commit = Vec::new();
        let mut barge_in_turns = Vec::new();

        for (index, exchange) in exchanges.iter().enumerate() {
            // The caller speaks, then goes quiet: onset, then offset.
            for _ in 0..exchange.speech_frames {
                tick!(3_000);
            }
            for _ in 0..25 {
                tick!(0);
            }
            assert_eq!(
                turn.state(),
                TurnState::Listening,
                "turn {index}: trailing silence should return to LISTENING"
            );

            // A stable ASR partial opens the endpoint window; the fast (80 ms)
            // deadline commits the turn and dispatches authoritative reasoning.
            let rev = epoch.bump_rev();
            turn.sink_mut().final_rev = rev;
            asr_events
                .push(AsrEvt::Partial { rev, stable: true, mono_ns: clock.now_ns() })
                .unwrap();
            tick!(0);
            assert_eq!(
                turn.state(),
                TurnState::Endpointing,
                "turn {index}: a stable partial opens the endpoint window"
            );
            epochs_at_commit.push(epoch.current_epoch());
            for _ in 0..12 {
                tick!(0);
            }

            // Wait for the agent's first audio to reach the wire.
            let mut speaking = false;
            for _ in 0..3_000 {
                tick!(0);
                if turn.state() == TurnState::Speaking {
                    speaking = true;
                    break;
                }
            }
            assert!(speaking, "turn {index}: never reached SPEAKING");

            match exchange.barge_in_after_ticks {
                None => {
                    // Let the utterance drain naturally.
                    let mut listening = false;
                    for _ in 0..3_000 {
                        tick!(0);
                        if turn.state() == TurnState::Listening {
                            listening = true;
                            break;
                        }
                    }
                    assert!(listening, "turn {index}: agent never finished speaking");
                }
                Some(after) => {
                    for _ in 0..after {
                        tick!(0);
                    }
                    // The caller starts talking over the agent.
                    for _ in 0..4 {
                        tick!(3_000);
                    }
                    let mut listening = false;
                    for _ in 0..400 {
                        tick!(0);
                        if turn.state() == TurnState::Listening {
                            listening = true;
                            break;
                        }
                    }
                    assert!(listening, "turn {index}: barge-in never completed");
                    // Let the interrupting speech end so the next turn starts
                    // from silence, as it would on a real call.
                    for _ in 0..25 {
                        tick!(0);
                    }
                    barge_in_turns.push(index);
                }
            }
        }

        // Teardown before the assertions, not after. A failed assertion inside
        // `thread::scope` leaves the worker threads parked on channels that
        // are never closed, and the scope's join turns a one-line failure into
        // a hung test that CI kills with no message. Close the job channel,
        // let the workers exit, snapshot what happened, and do the judging
        // outside.
        turn.sink_mut().jobs_tx = None;
        let mut workers_done = false;
        for _ in 0..3_000 {
            if reasoner_done.load(Ordering::Acquire) && tts_done.load(Ordering::Acquire) {
                workers_done = true;
                break;
            }
            tick!(0);
        }
        assert!(workers_done, "worker threads failed to exit");

        Observed {
            hist_overflows: turn.hist_overflows,
            flush_ack_timeouts: turn.flush_ack_timeouts,
            heard: turn.sink_mut().heard.clone(),
            dispatched: turn.sink_mut().dispatched.clone(),
            remote_cancels: turn.sink_mut().remote_cancels,
            epochs_at_commit,
            barge_in_turns,
            metrics_capture_dropped: media.snapshot().capture_dropped_frames,
            metrics_synth_stale: media.snapshot().synth_stale_frames,
            asr_forward_drops: vad.counters().asr_forward_drops,
            captured,
            asr_seen,
            tts_cancellations: tts_cancellations.load(Ordering::Relaxed),
            sent_samples: sink.sent_samples,
            voiced_samples: sink.voiced_samples,
        }
    });

    let turns = observed.epochs_at_commit.len();
    assert_eq!(turns, 5, "the conversation ran to completion");

    // Five turns, five reasoning dispatches, five heard-state commits — one
    // each, in order. A turn that leaked a record from the previous cycle or
    // skipped its own shows up right here.
    assert_eq!(observed.dispatched.len(), turns, "one dispatch per turn");
    assert_eq!(observed.heard.len(), turns, "one heard-state per turn");

    // The epoch is the response-cycle id: strictly increasing, never reused,
    // and each turn's heard-state carries its own.
    assert!(
        observed.epochs_at_commit.windows(2).all(|pair| pair[1] > pair[0]),
        "epochs must advance across turns: {:?}",
        observed.epochs_at_commit
    );
    let heard_epochs: Vec<u32> = observed.heard.iter().map(|h| h.epoch).collect();
    assert_eq!(
        heard_epochs, observed.epochs_at_commit,
        "each turn reports under its own epoch"
    );
    let dispatch_epochs: Vec<u32> = observed.dispatched.iter().map(|t| t.epoch).collect();
    assert_eq!(
        dispatch_epochs, observed.epochs_at_commit,
        "reasoning is dispatched under the committing epoch"
    );

    // The interrupted turns, and only those, are marked interrupted — and the
    // caller demonstrably heard part of them.
    let interrupted: Vec<usize> = observed
        .heard
        .iter()
        .enumerate()
        .filter(|(_, h)| h.interrupted)
        .map(|(i, _)| i)
        .collect();
    assert_eq!(
        interrupted, observed.barge_in_turns,
        "only the barged-in turns are interrupted"
    );
    assert_eq!(
        observed.remote_cancels as usize,
        observed.barge_in_turns.len(),
        "one remote cancellation per barge-in, and not one more"
    );
    for index in &observed.barge_in_turns {
        let heard = observed.heard[*index];
        assert!(
            heard.heard_samples > 0,
            "turn {index}: the caller heard some of the interrupted utterance"
        );
        assert_eq!(
            heard.unspoken_from, heard.heard_text_end,
            "turn {index}: resumption starts exactly where hearing stopped"
        );
        assert!(
            !heard.reopen_segment,
            "turn {index}: audio was heard, so the segment is not reopened"
        );
    }

    // Turns the caller let finish report the whole utterance.
    for (index, heard) in observed.heard.iter().enumerate() {
        if observed.barge_in_turns.contains(&index) {
            continue;
        }
        assert!(!heard.interrupted, "turn {index}: completed cleanly");
        assert!(
            heard.heard_text_end > 0,
            "turn {index}: a completed utterance has a text end"
        );
    }

    // The capture plane never lost a frame — not through five turns and not
    // through two mid-utterance flushes.
    assert_eq!(observed.metrics_capture_dropped, 0, "capture is never dropped");
    assert_eq!(observed.asr_seen, observed.captured, "every captured frame reached ASR");
    assert_eq!(observed.asr_forward_drops, 0);
    assert_eq!(observed.hist_overflows, 0, "history stayed within its cap");
    assert_eq!(observed.flush_ack_timeouts, 0, "the mixer acknowledged every flush");

    // Both barge-ins flushed queued synthesis, and the TTS host saw them.
    assert!(observed.metrics_synth_stale > 0, "queued synthesis was flushed");
    assert!(
        observed.tts_cancellations >= observed.barge_in_turns.len() as u64,
        "the TTS host observed every cancellation"
    );

    // The caller actually heard the agent, repeatedly, and the line was never
    // left carrying nothing but silence.
    assert!(observed.voiced_samples > 0, "agent audio reached the wire");
    assert!(
        observed.sent_samples >= observed.voiced_samples,
        "every voiced sample was sent"
    );
}
