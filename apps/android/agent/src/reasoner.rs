//! Reasoner host (RUNTIME_INVARIANTS.md §2.3).
//!
//! Cooperative cancellation: every decode step and every (bounded) send
//! retry checks the per-task doorbell *and* lazily validates the token, so
//! cancel latency is bounded by one decode step while correctness never
//! depends on the doorbell. Invalid work is dropped silently — no partial
//! plans escape a dead epoch.
//!
//! The plan channel to the TTS host is a bounded non-RT boundary (String
//! payloads never cross into RT code).

use std::sync::mpsc::{SyncSender, TrySendError};

use crate::cancel::Doorbell;
use crate::epoch::{EpochDomain, Token};

#[derive(Clone, Debug)]
pub struct PlanItem {
    pub token: Token,
    pub text: String,
    /// End-of-plan marker (empty text): tells the TTS host to emit the
    /// FINAL_CHUNK tail so the mixer can report `Drained`.
    pub last: bool,
}

pub enum StepOut {
    Chunk(String),
    Done,
}

/// One bounded decode step at a time; `begin` resets per-turn state.
pub trait ReasonerModel {
    fn begin(&mut self, transcript: &str);
    fn decode_step(&mut self) -> StepOut;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RunOutcome {
    Completed,
    Cancelled,
}

pub struct ReasonerHost<'a, 'm, M: ReasonerModel> {
    model: M,
    epoch: &'a EpochDomain<'m>,
    doorbell: Doorbell,
    plan_tx: SyncSender<PlanItem>,
}

impl<'a, 'm, M: ReasonerModel> ReasonerHost<'a, 'm, M> {
    pub fn new(
        model: M,
        epoch: &'a EpochDomain<'m>,
        doorbell: Doorbell,
        plan_tx: SyncSender<PlanItem>,
    ) -> Self {
        Self {
            model,
            epoch,
            doorbell,
            plan_tx,
        }
    }

    #[inline]
    fn cancelled(&self, token: Token) -> bool {
        self.doorbell.is_rung() || !self.epoch.valid(token)
    }

    /// Run one authoritative (or promoted speculative) turn. Blocking; meant
    /// for the reasoner thread (§5: default priority, cancellable).
    pub fn run_turn(&mut self, transcript: &str, token: Token) -> RunOutcome {
        // Any ring this erases targeted a previous epoch; the token check
        // still guards this task (§2.3).
        self.doorbell.reset();
        self.model.begin(transcript);
        loop {
            if self.cancelled(token) {
                return RunOutcome::Cancelled;
            }
            match self.model.decode_step() {
                StepOut::Chunk(text) => {
                    let item = PlanItem {
                        token,
                        text,
                        last: false,
                    };
                    if !self.send(item, token) {
                        return RunOutcome::Cancelled;
                    }
                }
                StepOut::Done => {
                    let marker = PlanItem {
                        token,
                        text: String::new(),
                        last: true,
                    };
                    return if self.send(marker, token) {
                        RunOutcome::Completed
                    } else {
                        RunOutcome::Cancelled
                    };
                }
            }
        }
    }

    /// Bounded-retry send that keeps honoring cancellation while the plan
    /// channel is backpressured.
    fn send(&self, item: PlanItem, token: Token) -> bool {
        let mut item = item;
        loop {
            if self.cancelled(token) {
                return false;
            }
            match self.plan_tx.try_send(item) {
                Ok(()) => return true,
                Err(TrySendError::Full(returned)) => {
                    item = returned;
                    std::thread::yield_now();
                }
                Err(TrySendError::Disconnected(_)) => return false,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::Duration;
    use triplex_native_media::{NativeMediaRuntime, ROUTE_DIRECT_PJSIP};

    /// Emits chunks forever; each decode step costs ~2 ms.
    struct EndlessModel;

    impl ReasonerModel for EndlessModel {
        fn begin(&mut self, _transcript: &str) {}
        fn decode_step(&mut self) -> StepOut {
            std::thread::sleep(Duration::from_millis(2));
            StepOut::Chunk("chunk ".to_string())
        }
    }

    #[test]
    fn doorbell_cancels_within_one_decode_step() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let epoch = EpochDomain::new(&media);
        let (plan_tx, plan_rx) = mpsc::sync_channel::<PlanItem>(1024);
        let bell = Doorbell::new();
        let token = epoch.token_final();

        std::thread::scope(|scope| {
            let worker_bell = bell.clone();
            let worker = scope.spawn(move || {
                let mut host = ReasonerHost::new(EndlessModel, &epoch, worker_bell, plan_tx);
                host.run_turn("transcript", token)
            });
            // Wait until the model demonstrably produced work, then cancel.
            let first = plan_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            assert!(!first.last);
            bell.ring();
            assert_eq!(worker.join().unwrap(), RunOutcome::Cancelled);
        });
    }

    #[test]
    fn epoch_bump_cancels_without_a_doorbell() {
        let media = NativeMediaRuntime::new(ROUTE_DIRECT_PJSIP);
        let epoch = EpochDomain::new(&media);
        let (plan_tx, plan_rx) = mpsc::sync_channel::<PlanItem>(1024);
        let token = epoch.token_final();

        std::thread::scope(|scope| {
            let worker = scope.spawn(|| {
                let mut host = ReasonerHost::new(EndlessModel, &epoch, Doorbell::new(), plan_tx);
                host.run_turn("transcript", token)
            });
            plan_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            epoch.bump_epoch(); // lazy token check alone must kill the task
            assert_eq!(worker.join().unwrap(), RunOutcome::Cancelled);
        });
    }
}
