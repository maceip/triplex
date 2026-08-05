//! TurnController: the sole epoch writer and turn FSM
//! (RUNTIME_INVARIANTS.md §2.2, §3.3–§3.4, §3.6).
//!
//! States: LISTENING → ENDPOINTING → COMMITTED → SPEAKING → LISTENING, with
//! transient YIELDING and terminal ENDED. **The epoch increments on every
//! entry to LISTENING** — normal drain, barge-in, stop — so epoch ≡
//! response-cycle id and anything tagged older is definitionally stale.
//!
//! Barge-in publication order (§3.4): (1) epoch bump — which also arms the
//! mixer flush, step 3, inside `NativeMediaRuntime::interrupt` — then
//! (2) doorbells, (4) remote cancellation, (5) YIELDING until the mixer's
//! flush ack, at which point the conservative heard-state is computed from
//! the playout ledger and alignment marks and committed through the sink.
//!
//! Invalidation is queue-surgery-free: this controller never touches another
//! thread's queue; workers discover staleness at their own checkpoints.

use triplex_native_media::agent::ledger::{PlayoutLedger, PlayoutRecord};
use triplex_native_media::agent::mixer::{MixerStatus, StatusRing};
use triplex_native_media::SpscRing;

use crate::cancel::DoorbellRegistry;
use crate::epoch::{EpochDomain, Token, REV_FINAL};
use crate::heard::{heard_text_boundary, heard_watermark, HeardState};
use crate::tts::{AlignmentMark, MarksRing};
use crate::vad::{SpeechOnset, VadEvent, VadEventRing};

pub const ASR_EVENT_SLOTS: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AsrEvt {
    Partial { rev: u32, stable: bool, mono_ns: i64 },
}

/// Producer: ASR host. Consumer: TurnController.
pub type AsrEventRing = SpscRing<AsrEvt, ASR_EVENT_SLOTS>;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TurnState {
    Listening,
    Endpointing,
    Committed,
    Speaking,
    Yielding,
    Ended,
}

/// Product-side effect boundary. The controller owns *when*; the sink owns
/// *what*. `start_reasoning` with `speculative: true` receives an empty
/// transcript — the sink resolves the partial text by revision (§2.2).
pub trait TurnSink {
    /// Synchronously finalize ASR; returns (final revision, transcript).
    fn finalize_asr(&mut self) -> (u32, String);
    fn start_reasoning(&mut self, transcript: &str, token: Token, speculative: bool);
    /// Promote buffered speculation to authoritative; false ⇒ the controller
    /// falls back to an authoritative `start_reasoning`.
    fn promote_speculation(&mut self, token: Token) -> bool;
    /// §3.4 step 4: cancel remote in-flight work for the dead epoch.
    fn cancel_remote(&mut self, old_epoch: u32);
    /// Heard-state commit for history (the commit executor's input).
    fn heard_state(&mut self, heard: HeardState);
}

#[derive(Clone, Copy, Debug)]
pub struct TurnConfig {
    /// Adaptive endpoint window bounds (§2.2: d ∈ [80, 250] ms).
    pub endpoint_fast_ns: i64,
    pub endpoint_slow_ns: i64,
    /// Transit floor for the conservative heard watermark (§3.6).
    pub min_one_way_ns: i64,
    /// Uncertainty margin above the floor (§3.6).
    pub heard_margin_ns: i64,
    pub history_cap: usize,
}

impl Default for TurnConfig {
    fn default() -> Self {
        Self {
            endpoint_fast_ns: 80_000_000,
            endpoint_slow_ns: 250_000_000,
            min_one_way_ns: 20_000_000,
            heard_margin_ns: 60_000_000,
            history_cap: 1024,
        }
    }
}

/// Input rings, grouped to keep constructor arity honest.
pub struct TurnWiring<'a> {
    pub vad_rx: &'a VadEventRing,
    pub asr_rx: &'a AsrEventRing,
    pub status_rx: &'a StatusRing,
    pub ledger_rx: &'a PlayoutLedger,
    pub marks_rx: &'a MarksRing,
}

pub struct TurnController<'a, 'm, S: TurnSink> {
    epoch: &'a EpochDomain<'m>,
    cfg: TurnConfig,
    wiring: TurnWiring<'a>,
    doorbells: &'a DoorbellRegistry,
    sink: S,
    state: TurnState,
    ledger_hist: Vec<PlayoutRecord>,
    marks_hist: Vec<AlignmentMark>,
    endpoint_deadline_ns: i64,
    spec_rev: Option<u32>,
    pending_onset_ns: i64,
    yield_old_epoch: u32,
    yield_to_epoch: u32,
    user_speaking: bool,
    pub hist_overflows: u64,
}

impl<'a, 'm, S: TurnSink> TurnController<'a, 'm, S> {
    pub fn new(
        epoch: &'a EpochDomain<'m>,
        cfg: TurnConfig,
        wiring: TurnWiring<'a>,
        doorbells: &'a DoorbellRegistry,
        sink: S,
    ) -> Self {
        let history_cap = cfg.history_cap;
        Self {
            epoch,
            cfg,
            wiring,
            doorbells,
            sink,
            state: TurnState::Listening,
            ledger_hist: Vec::with_capacity(history_cap),
            marks_hist: Vec::with_capacity(history_cap),
            endpoint_deadline_ns: 0,
            spec_rev: None,
            pending_onset_ns: 0,
            yield_old_epoch: 0,
            yield_to_epoch: 0,
            user_speaking: false,
            hist_overflows: 0,
        }
    }

    pub fn state(&self) -> TurnState {
        self.state
    }

    pub fn sink_mut(&mut self) -> &mut S {
        &mut self.sink
    }

    /// Event-driven poll; call from the TurnController thread on every
    /// doorbell wake or transport tick.
    pub fn poll(&mut self, now_ns: i64) {
        self.drain_playout();
        while let Some(status) = self.wiring.status_rx.pop() {
            self.on_status(status);
        }
        while let Some(event) = self.wiring.vad_rx.pop() {
            self.on_vad(event);
        }
        while let Some(event) = self.wiring.asr_rx.pop() {
            self.on_asr(event, now_ns);
        }
        if self.state == TurnState::Endpointing && now_ns >= self.endpoint_deadline_ns {
            self.commit_turn();
        }
    }

    /// User stop / hangup: same publication order as barge-in, terminal state.
    pub fn hangup(&mut self) {
        let old = self.epoch.current_epoch();
        self.epoch.bump_epoch();
        self.doorbells.ring_all();
        self.sink.cancel_remote(old);
        self.spec_rev = None;
        self.state = TurnState::Ended;
    }

    fn drain_playout(&mut self) {
        while let Some(record) = self.wiring.ledger_rx.pop() {
            if self.ledger_hist.first().is_some_and(|f| f.epoch != record.epoch) {
                self.ledger_hist.clear();
            }
            if self.ledger_hist.len() < self.cfg.history_cap {
                self.ledger_hist.push(record);
            } else {
                self.hist_overflows += 1;
            }
        }
        while let Some(mark) = self.wiring.marks_rx.pop() {
            if self.marks_hist.first().is_some_and(|f| f.epoch != mark.epoch) {
                self.marks_hist.clear();
            }
            if self.marks_hist.len() < self.cfg.history_cap {
                self.marks_hist.push(mark);
            } else {
                self.hist_overflows += 1;
            }
        }
    }

    fn on_status(&mut self, status: MixerStatus) {
        let current = self.epoch.current_epoch();
        match status {
            MixerStatus::FirstAudio { epoch, .. } => {
                if epoch == current && self.state == TurnState::Committed {
                    self.state = TurnState::Speaking;
                }
            }
            MixerStatus::Drained { epoch, .. } => {
                if epoch == current && self.state == TurnState::Speaking {
                    self.complete_turn();
                }
            }
            MixerStatus::FlushAck { epoch, .. } => {
                if self.state == TurnState::Yielding && epoch == self.yield_to_epoch {
                    self.finish_barge_in();
                }
            }
        }
    }

    fn on_vad(&mut self, event: VadEvent) {
        match event {
            VadEvent::Onset(onset) => {
                self.user_speaking = true;
                match self.state {
                    // §2.2: onset during ENDPOINTING closes the window; same
                    // epoch, speculation self-invalidates via rev.
                    TurnState::Endpointing => self.state = TurnState::Listening,
                    TurnState::Committed | TurnState::Speaking => self.barge_in(onset),
                    _ => {}
                }
            }
            VadEvent::Offset { .. } => self.user_speaking = false,
        }
    }

    fn on_asr(&mut self, event: AsrEvt, now_ns: i64) {
        let AsrEvt::Partial { rev, stable, .. } = event;
        if self.user_speaking
            || !matches!(self.state, TurnState::Listening | TurnState::Endpointing)
        {
            return;
        }
        // §2.2: stable partial + trailing silence opens (or refreshes) the
        // adaptive endpoint window.
        self.endpoint_deadline_ns = now_ns
            + if stable {
                self.cfg.endpoint_fast_ns
            } else {
                self.cfg.endpoint_slow_ns
            };
        self.state = TurnState::Endpointing;
        if stable && self.spec_rev != Some(rev) {
            self.spec_rev = Some(rev);
            let token = Token {
                epoch: self.epoch.current_epoch(),
                rev,
            };
            self.sink.start_reasoning("", token, true);
        }
    }

    fn commit_turn(&mut self) {
        let (final_rev, transcript) = self.sink.finalize_asr();
        let token = Token {
            epoch: self.epoch.current_epoch(),
            rev: REV_FINAL,
        };
        // §2.2: speculation promotes only when its revision matches the
        // final transcript; otherwise discard and rerun authoritatively.
        let promoted = self.spec_rev == Some(final_rev) && self.sink.promote_speculation(token);
        if !promoted {
            self.sink.start_reasoning(&transcript, token, false);
        }
        self.state = TurnState::Committed;
    }

    /// §3.4 publication order.
    fn barge_in(&mut self, onset: SpeechOnset) {
        let old = self.epoch.current_epoch();
        let new = self.epoch.bump_epoch(); // (1) broadcast + (3) mixer flush
        self.doorbells.ring_all(); // (2)
        self.sink.cancel_remote(old); // (4)
        self.pending_onset_ns = onset.mono_ns;
        self.yield_old_epoch = old;
        self.yield_to_epoch = new;
        self.spec_rev = None;
        self.state = TurnState::Yielding; // (5)
    }

    fn finish_barge_in(&mut self) {
        let old = self.yield_old_epoch;
        let watermark = heard_watermark(
            &self.ledger_hist,
            old,
            self.pending_onset_ns,
            self.cfg.min_one_way_ns,
            self.cfg.heard_margin_ns,
        );
        let boundary = heard_text_boundary(&self.marks_hist, old, watermark);
        self.sink.heard_state(HeardState {
            epoch: old,
            interrupted: true,
            heard_samples: watermark,
            heard_text_end: boundary,
            unspoken_from: boundary,
            reopen_segment: watermark == 0,
        });
        // Epoch already advanced at publication; this entry to LISTENING is
        // the same response-cycle boundary.
        self.state = TurnState::Listening;
    }

    fn complete_turn(&mut self) {
        let epoch = self.epoch.current_epoch();
        let full_end = self
            .marks_hist
            .iter()
            .filter(|m| m.epoch == epoch)
            .map(|m| m.text_off)
            .max()
            .unwrap_or(0);
        let heard = self
            .ledger_hist
            .iter()
            .filter(|r| r.epoch == epoch)
            .map(|r| r.utt_sample_off + u64::from(r.samples))
            .max()
            .unwrap_or(0);
        self.sink.heard_state(HeardState {
            epoch,
            interrupted: false,
            heard_samples: heard,
            heard_text_end: full_end,
            unspoken_from: full_end,
            reopen_segment: false,
        });
        // §2.2: every entry to LISTENING advances the epoch.
        self.epoch.bump_epoch();
        self.spec_rev = None;
        self.state = TurnState::Listening;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cancel::Doorbell;
    use triplex_native_media::{NativeMediaRuntime, ROUTE_DIRECT_PJSIP};

    const MS: i64 = 1_000_000;

    #[derive(Default)]
    struct RecordingSink {
        reasoning: Vec<(String, Token, bool)>,
        promotions: Vec<Token>,
        heard: Vec<HeardState>,
        cancels: Vec<u32>,
        final_rev: u32,
    }

    impl TurnSink for RecordingSink {
        fn finalize_asr(&mut self) -> (u32, String) {
            (self.final_rev, "final transcript".to_string())
        }

        fn start_reasoning(&mut self, transcript: &str, token: Token, speculative: bool) {
            self.reasoning.push((transcript.to_string(), token, speculative));
        }

        fn promote_speculation(&mut self, token: Token) -> bool {
            self.promotions.push(token);
            false
        }

        fn cancel_remote(&mut self, old_epoch: u32) {
            self.cancels.push(old_epoch);
        }

        fn heard_state(&mut self, heard: HeardState) {
            self.heard.push(heard);
        }
    }

    struct Fixture {
        media: NativeMediaRuntime,
        vad: VadEventRing,
        asr: AsrEventRing,
        status: StatusRing,
        ledger: PlayoutLedger,
        marks: MarksRing,
        bells: DoorbellRegistry,
    }

    impl Fixture {
        fn new() -> Self {
            Self {
                media: NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP),
                vad: VadEventRing::new(),
                asr: AsrEventRing::new(),
                status: StatusRing::new(),
                ledger: PlayoutLedger::new(),
                marks: MarksRing::new(),
                bells: DoorbellRegistry::new(4),
            }
        }

        fn controller<'a>(
            &'a self,
            epoch: &'a EpochDomain<'a>,
        ) -> TurnController<'a, 'a, RecordingSink> {
            TurnController::new(
                epoch,
                TurnConfig::default(),
                TurnWiring {
                    vad_rx: &self.vad,
                    asr_rx: &self.asr,
                    status_rx: &self.status,
                    ledger_rx: &self.ledger,
                    marks_rx: &self.marks,
                },
                &self.bells,
                RecordingSink::default(),
            )
        }
    }

    fn stable_partial(fx: &Fixture, epoch: &EpochDomain<'_>) -> u32 {
        let rev = epoch.bump_rev();
        fx.asr
            .push(AsrEvt::Partial { rev, stable: true, mono_ns: 0 })
            .unwrap();
        rev
    }

    #[test]
    fn endpoint_commits_and_attempts_speculation_promotion() {
        let fx = Fixture::new();
        let epoch = EpochDomain::new(&fx.media);
        let mut turn = fx.controller(&epoch);

        let rev = stable_partial(&fx, &epoch);
        turn.sink_mut().final_rev = rev;
        turn.poll(0);
        assert_eq!(turn.state(), TurnState::Endpointing);
        let speculative = &turn.sink_mut().reasoning[0];
        assert_eq!(speculative.1, Token { epoch: 1, rev });
        assert!(speculative.2);

        turn.poll(90 * MS); // fast window is 80 ms
        assert_eq!(turn.state(), TurnState::Committed);
        let sink = turn.sink_mut();
        assert_eq!(sink.promotions, vec![Token { epoch: 1, rev: REV_FINAL }]);
        let authoritative = &sink.reasoning[1];
        assert_eq!(
            (&authoritative.0[..], authoritative.1, authoritative.2),
            ("final transcript", Token { epoch: 1, rev: REV_FINAL }, false)
        );
    }

    #[test]
    fn onset_during_endpointing_cancels_the_window_without_commit() {
        let fx = Fixture::new();
        let epoch = EpochDomain::new(&fx.media);
        let mut turn = fx.controller(&epoch);

        stable_partial(&fx, &epoch);
        turn.poll(0);
        assert_eq!(turn.state(), TurnState::Endpointing);

        fx.vad
            .push(VadEvent::Onset(SpeechOnset { mono_ns: 10 * MS, conf: 0.9 }))
            .unwrap();
        turn.poll(20 * MS);
        assert_eq!(turn.state(), TurnState::Listening);
        assert_eq!(epoch.current_epoch(), 1, "same epoch: nothing to cancel");

        turn.poll(400 * MS);
        assert_eq!(turn.state(), TurnState::Listening, "no late commit");
        assert_eq!(turn.sink_mut().reasoning.len(), 1, "speculation only");
    }

    #[test]
    fn barge_in_publishes_in_order_and_commits_conservative_heard_state() {
        let fx = Fixture::new();
        let epoch = EpochDomain::new(&fx.media);
        let bell = Doorbell::new();
        fx.bells.register(&bell).unwrap();
        let mut turn = fx.controller(&epoch);

        // LISTENING → ENDPOINTING → COMMITTED → SPEAKING.
        let rev = stable_partial(&fx, &epoch);
        turn.sink_mut().final_rev = rev;
        turn.poll(0);
        turn.poll(90 * MS);
        fx.status
            .push(MixerStatus::FirstAudio { epoch: 1, mono_ns: 0 })
            .unwrap();
        turn.poll(91 * MS);
        assert_eq!(turn.state(), TurnState::Speaking);

        // Playout evidence: three frames at 0/10/20 ms, word ends at samples
        // 100/300/450 with text offsets 5/11/17.
        for i in 0..3_u64 {
            fx.ledger
                .push(PlayoutRecord {
                    epoch: 1,
                    seq: i,
                    egress_mono_ns: i as i64 * 10 * MS,
                    samples: 160,
                    utt_sample_off: i * 160,
                })
                .unwrap();
        }
        for (sample, text) in [(100, 5), (300, 11), (450, 17)] {
            fx.marks
                .push(AlignmentMark { epoch: 1, utt_sample_off: sample, text_off: text })
                .unwrap();
        }

        // Barge-in at 95 ms.
        fx.vad
            .push(VadEvent::Onset(SpeechOnset { mono_ns: 95 * MS, conf: 0.95 }))
            .unwrap();
        turn.poll(96 * MS);
        assert_eq!(turn.state(), TurnState::Yielding);
        assert_eq!(epoch.current_epoch(), 2, "publication happened");
        assert!(bell.is_rung(), "doorbells rung after publication");
        assert_eq!(turn.sink_mut().cancels, vec![1], "remote cancel for old epoch");

        // Mixer acknowledges the flush of everything queued at interrupt.
        fx.status
            .push(MixerStatus::FlushAck { epoch: 2, mono_ns: 97 * MS })
            .unwrap();
        turn.poll(97 * MS);
        assert_eq!(turn.state(), TurnState::Listening);
        let heard = turn.sink_mut().heard.clone();
        assert_eq!(
            heard,
            vec![HeardState {
                epoch: 1,
                interrupted: true,
                heard_samples: 320, // frames egressed ≥ 80 ms before onset
                heard_text_end: 11, // last fully emitted word before 320
                unspoken_from: 11,
                reopen_segment: false,
            }]
        );
    }

    #[test]
    fn barge_in_before_any_audio_reopens_the_user_segment() {
        let fx = Fixture::new();
        let epoch = EpochDomain::new(&fx.media);
        let mut turn = fx.controller(&epoch);

        let rev = stable_partial(&fx, &epoch);
        turn.sink_mut().final_rev = rev;
        turn.poll(0);
        turn.poll(90 * MS);
        assert_eq!(turn.state(), TurnState::Committed);

        fx.vad
            .push(VadEvent::Onset(SpeechOnset { mono_ns: 92 * MS, conf: 0.9 }))
            .unwrap();
        turn.poll(93 * MS);
        assert_eq!(turn.state(), TurnState::Yielding);
        fx.status
            .push(MixerStatus::FlushAck { epoch: 2, mono_ns: 94 * MS })
            .unwrap();
        turn.poll(94 * MS);

        let heard = &turn.sink_mut().heard;
        assert_eq!(heard.len(), 1);
        assert!(heard[0].interrupted);
        assert_eq!(heard[0].heard_samples, 0);
        assert!(heard[0].reopen_segment, "nothing heard ⇒ reopen user segment");
    }

    #[test]
    fn normal_drain_commits_full_utterance_and_advances_the_epoch() {
        let fx = Fixture::new();
        let epoch = EpochDomain::new(&fx.media);
        let mut turn = fx.controller(&epoch);

        let rev = stable_partial(&fx, &epoch);
        turn.sink_mut().final_rev = rev;
        turn.poll(0);
        turn.poll(90 * MS);
        fx.status
            .push(MixerStatus::FirstAudio { epoch: 1, mono_ns: 0 })
            .unwrap();
        turn.poll(91 * MS);

        fx.ledger
            .push(PlayoutRecord {
                epoch: 1,
                seq: 0,
                egress_mono_ns: 0,
                samples: 160,
                utt_sample_off: 0,
            })
            .unwrap();
        fx.marks
            .push(AlignmentMark { epoch: 1, utt_sample_off: 100, text_off: 5 })
            .unwrap();
        fx.status
            .push(MixerStatus::Drained { epoch: 1, mono_ns: 100 * MS })
            .unwrap();
        turn.poll(101 * MS);

        assert_eq!(turn.state(), TurnState::Listening);
        assert_eq!(epoch.current_epoch(), 2, "epoch advances on normal drain too");
        let heard = &turn.sink_mut().heard;
        assert_eq!(heard.len(), 1);
        assert!(!heard[0].interrupted);
        assert_eq!(heard[0].heard_text_end, 5);
    }
}
