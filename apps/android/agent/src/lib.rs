//! agent-core: the neutral local agent pipeline (RUNTIME_INVARIANTS.md §2–§4).
//!
//! Module map onto the spec:
//! - [`epoch`] — the single generation-epoch domain and validity tokens
//!   (§2.1). The epoch atomic itself lives inside `NativeMediaRuntime` so the
//!   RT mixer gate and this domain can never disagree.
//! - [`vad`] — capture-plane VAD evaluation loop with the energy gate,
//!   k-consecutive onset hysteresis, and the far-echo correlation gate
//!   (§3.1–§3.2). Never flushes the capture plane (§0).
//! - [`turn`] — the TurnController: sole epoch writer, turn FSM
//!   LISTENING → ENDPOINTING → COMMITTED → SPEAKING → YIELDING, and the
//!   ordered barge-in publication protocol (§2.2, §3.3–§3.4).
//! - [`cancel`] — per-task doorbells; latency-only, correctness always comes
//!   from token checks (§2.3).
//! - [`reasoner`] / [`tts`] — cooperative-cancellation hosts; the TTS host
//!   emits canonical 16 kHz frames plus word-boundary alignment marks (§2.3).
//! - [`heard`] — conservative heard-state watermark and text-boundary
//!   mapping (§3.6).

pub mod baseline;
pub mod cancel;
pub mod clock;
pub mod epoch;
pub mod ffi;
pub mod heard;
pub mod reasoner;
pub mod session;
pub mod tts;
pub mod turn;
pub mod vad;
