# Disposition ledger

Status: Phase 1 deliverable, living document
Date: 2026-08-05
Governs: `FINAL_UNIFICATION.md`

Every module carries an explicit disposition. Nothing is ambiguously alive.

**Before building any component, find its concept here first.** If it is
absent, add a row before writing code. This ledger exists because four
capabilities were rebuilt without their prior decisions being carried across;
the failure was not re-implementation, which the locked decisions require, but
re-derivation.

## Dispositions

| Code | Meaning |
|---|---|
| **adopt** | Ships as is. |
| **adapt** | Ships after modification. |
| **port-decisions-only** | The code is retired; its *contract* is transcribed into the native runtime with a citation. This is the category that was missing. |
| **evidence-only** | Retained to prove something was measured. Not a design input. |
| **reject** | Recoverable, but never to be promoted. |

## Live — `apps/android/` (the product)

| Module | Lang | Disposition | Notes |
|---|---|---|---|
| `native-media` (frame, ring, runtime, agent/{ledger,echo_ref,mixer}, rtguard) | Rust | adopt | 20 tests; allocation guard proves zero heap on RT paths. |
| `agent-core` (epoch, cancel, clock, vad, turn, reasoner, tts, heard, baseline, session, ffi) | Rust | adopt | 19 tests; barge-in proven on device. |
| `app` (UI, voice, telephony bridge, data, nativebridge) | Kotlin | adapt | Runs on device; predates this work and is uneven. `control/TurnController.kt` still shadows the Rust FSM — resolve in Phase 2. |
| `telephony-plivo` (PJSIP engine, RTP tap, JNI) | Kotlin + C++ | adopt | 16 tests; TLS + SRTP fail closed. |
| `app/src/main/cpp/native_runtime.cpp` | C++ | adapt | Thin JNI over the Rust FFI. Agent model hosts are still stubs. |

## Live — cloud and tooling

| Module | Lang | Disposition | Notes |
|---|---|---|---|
| `gateway/` | Python | adopt | Deployed at `bridge.secure.build`; proven on a real inbound PSTN call. Has no tests — a gap, not a disposition question. |
| `services/voice_models/` (kokoro, qwen3_tts, dual_router) | Python | adopt | Model serving only. `dual_router` additionally **port-decisions-only**: its branded/cloned contract belongs in the Rust `TtsModel` selection. |
| `services/voice-clone/` | Python | adopt | Model serving. Requires a GPU host; Phase 4 decides its future. |
| `testlab/` | Python | adopt | Evaluation and measurement. Never ships. |

## Retired — `experiments/python-agent/`

| Module | Lang | LOC | Disposition | Contract to carry |
|---|---|---|---|---|
| `interruption/{tear_down,state_alignment}.py` | Python | 188 | port-decisions-only | 50 ms teardown deadline; **the deadline bounds the reported result, never the cancellation**; exactly one flush per barge-in; retain only fully played segments. |
| `transport/echo_canceller.py` | Python | ~500 | port-decisions-only + evidence-only | `echo_delay_ms` must be calibrated from a real call, which constrains the far-echo gate's lag window. ERLE > 20 dB and p95 < 1 ms are evidence the approach works; the code serves an acoustic loop direct media does not have. |
| `reflex/engine.py` | Python | 237 | port-decisions-only | Action taxonomy: backchannel, barge-in, filler injection, under 100 ms. |
| `audio/vad.py` | Python | 261 | port-decisions-only | Silero backend and speech/silence/unknown states inform the production `SpeechModel`. |
| `pipeline.py`, `asr/`, `llm/`, `reasoning/`, `agent/`, `gate/`, `inference/` | Python | ~2,500 | reject | A second live agent. Superseded by `agent-core`. |
| `transport/` (Chime, KVS, MKV, meeting gateway) | Python | 2,861 | evidence-only | Provider knowledge retained; the multi-hop browser path is not promoted. |
| `tests/` (24 tests) | Python | 957 | evidence-only | Mined in Phase 1; see below. |
| `mobile/` (separate repo) | Kotlin | — | reject | Sine-wave "Kokoro", a gateway route that never existed, a clone screen that never recorded. Preserved in its own history. |

## Phase 1 resolutions

### Word timestamps — closed

`IMPLEMENTATION_PLAN.md` Item 3 dropped partial segments because "neither
Kokoro nor the high-level Qwen clone API supplies trustworthy word
timestamps". `agent-core::heard` mapped the heard watermark to text through
alignment marks, which assumes the opposite.

Resolved without choosing between them: `TtsHost` now emits a **chunk-end mark
unconditionally**, deduplicated when the engine already marked that boundary.
Marks are therefore a *lower bound on granularity* — the last mark at or
before the watermark is always a safe retention point. An engine with real
word marks yields word precision; one without degrades to whole-segment
retention, which is exactly the retired pipeline's rule.

Consequence for Phase 4: per-word marks are a *desirable* engine property, no
longer a *required* one.

### Assertions mined from the retired tests

`test_interruption.py` asserted four things. Three the Rust already enforced;
one was a real defect.

| Assertion | Status in Rust |
|---|---|
| Generation aborted on confirmed speech | Already enforced — epoch bump. |
| Exactly one flush per barge-in | Already enforced — one `interrupt()` per publication. |
| Teardown under 50 ms | Already enforced — budgets in `RUNTIME_INVARIANTS` §3. |
| Retain only fully played segments | Now enforced — chunk-end marks, above. |
| **Deadline must not cancel the abort or the remote flush** | **Was a defect.** `TurnController` waited in YIELDING for a flush acknowledgement with no timeout; a stalled mixer wedged the FSM permanently. Fixed: `flush_ack_deadline_ns` (50 ms) releases the state machine and commits conservative heard-state, while cancellation — already published — is untouched. Regression: `missing_flush_ack_still_commits_conservative_heard_state`. |

The retired tests earned their keep: they found a hang the native tests did
not.
