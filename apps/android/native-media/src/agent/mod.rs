//! Egress-side agent substrate (RUNTIME_INVARIANTS.md §1–§3): the playout
//! ledger consumed by heard-state, the far-echo PCM reference consumed by the
//! VAD gate, and the paced egress pump that enforces the O(1) epoch gate and
//! the 40 ms pacing invariant.

pub mod echo_ref;
pub mod ledger;
pub mod mixer;
