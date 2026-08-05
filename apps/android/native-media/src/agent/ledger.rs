//! Playout ledger (RUNTIME_INVARIANTS.md §3.6).
//!
//! The egress pump appends one record per egressed synthesis frame at the
//! moment it is handed to the transport. The TurnController drains the ring
//! into its per-epoch history and computes the conservative heard watermark
//! from it after a barge-in. Silence fill frames are never ledgered — they
//! are not utterance content.

use crate::ring::SpscRing;

/// One egressed synthesis frame.
#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PlayoutRecord {
    pub epoch: u32,
    pub seq: u64,
    /// Modeled playout instant: the pacing horizon at send time. The frame
    /// becomes audible to the caller roughly one one-way transit later.
    pub egress_mono_ns: i64,
    pub samples: u16,
    /// Cumulative sample offset of this frame within its epoch's utterance;
    /// alignment marks are expressed in the same coordinate space.
    pub utt_sample_off: u64,
}

pub const LEDGER_SLOTS: usize = 1024;

/// Producer: egress pump. Consumer: TurnController.
pub type PlayoutLedger = SpscRing<PlayoutRecord, LEDGER_SLOTS>;
