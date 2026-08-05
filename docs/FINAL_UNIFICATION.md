# Triplex final unification

Status: approved, in execution
Date: 2026-08-05
Supersedes: the consolidation sections of `UNIFICATION_PLAN.md` (its north star,
placement rules, and performance bar remain in force)

## Why this document exists

Four capabilities were built in Python and recorded as implemented in
`IMPLEMENTATION_PLAN.md`. They were then rebuilt in Rust and Kotlin for the
Android runtime without their decisions being carried across. That was not
waste — the locked decision is that Python does not ship in the Android
runtime, so a native implementation had to exist — but it produced parallel
designs, one direct contradiction, and a tree where it is unclear what is
alive.

This document ends that ambiguity. It records what is retired, what is kept,
and the four phases that leave one cohesive application surface.

The device-first priority does not change. The phone is the magnet, and
minimum caller-perceived latency is still the governing objective.

## Decision 1: the Python agent is retired

`experiments/python-agent/` now holds the retired tree. It is read-only
reference material. It does not ship, is not maintained, and is not a fallback.

| Retired | Language | LOC | Why |
|---|---|---|---|
| `pipeline.py`, `asr/`, `llm/`, `reasoning/`, `agent/`, `gate/`, `inference/` | Python | ~2,500 | A second live agent. Superseded by `apps/android/agent` (Rust). |
| `transport/` (Chime, KVS, MKV, meeting gateway) | Python | 2,861 | Targets a provider architecture the plan parked; does not work with Plivo. |
| `interruption/{tear_down,state_alignment}.py` | Python | 188 | Superseded by the Rust generation-epoch machinery, which is proven on device. |
| `audio/vad.py` | Python | 261 | Superseded by `agent-core::vad`. |
| `reflex/engine.py` | Python | 237 | Taxonomy ported; implementation superseded. |
| `transport/echo_canceller.py` | Python | ~500 | Genuine NLMS canceller, but it serves the browser path's acoustic loop. Direct media has none. If takeover mode ever needs AEC, adopt WebRTC AEC3 rather than porting this. Evidence-only. |
| `mobile/` | Kotlin | — | Sine-wave "Kokoro", a gateway route that never existed, a clone screen that never recorded. Rejected. Preserved in its own git history. |

Retirement means moved with history and marked superseded. Nothing is deleted.
Rejected code stays recoverable; classification only stops it being mistaken
for production.

## Decision 2: three Python roles are kept

These are kept because none of them is an agent. They do not duplicate
orchestration.

1. **`gateway/`** — the cloud control plane. Auth, device registration, number
   and credential provisioning, task policy, Plivo webhook routing, minimal
   audit. Deployed at `bridge.secure.build`, signature-validated, proven on a
   real inbound PSTN call.
2. **`services/voice_models/` and `services/voice-clone/`** — model serving.
   Thin wrappers around the Kokoro and Qwen3-TTS engines with no conversation
   logic. This is the mechanism that makes capability offload possible.
3. **`testlab/`** — evaluation and measurement harnesses. Model comparison,
   the reference-quality diagnosis suite, transport validation. Python is the
   right tool and it never ships.

## Decision 3: cloud fallback without a second implementation

Two different things hide under "fallback", and only one of them is a second
implementation.

**Capability offload** — the device cannot run a *model* (no GPU headroom,
memory constrained, thermal limit). The fix is a remote model backend behind
the trait seams that already exist in `agent-core`: `SpeechModel`,
`ReasonerModel`, `TtsModel`. A `RemoteTts` that posts to a model server is on
the order of a hundred lines implementing an existing trait, and it inherits
epoch cancellation, doorbells, heard-state, and barge-in unchanged, because
the orchestration above it is untouched. One implementation, swappable
backend.

**REMOTE_AGENT** — the phone is off, unreachable, or has declined. That is
genuinely a second conversational implementation, and it is still an open
decision in `UNIFICATION_PLAN.md`. It is not being built now. When the phone
is unavailable the honest behaviour is the one already shipping: a truthful
unavailable message, later message-taking or transfer.

**The governing rule: exactly one implementation of orchestration, in Rust.
The cloud supplies models, never conversation logic.**

Placement stays visible. Every stage execution records LOCAL, REMOTE_ASR,
REMOTE_REASONING, REMOTE_TTS, or REMOTE_AGENT with its reason and latency.
Per-utterance remote synthesis bills GPU for every minute of every call, so
it is a device-tier decision recorded at enrollment, never a silent per-call
surprise.

## Decision 4: the one contradiction to resolve

`IMPLEMENTATION_PLAN.md` Item 3 resolved that partial synthesizer segments are
dropped, "because neither Kokoro nor the high-level Qwen clone API supplies
trustworthy word timestamps".

`agent-core::heard` does the opposite: it maps the heard watermark to text
through TTS word-boundary alignment marks. That holds for the baseline tone
TTS, where marks are exact by construction. It does not hold for Kokoro or
Qwen.

This is now an input to model selection, not merely cleanup. Either the chosen
on-device engine emits trustworthy per-word marks, or `heard.rs` falls back to
whole-segment retention. Resolve before the engine is chosen.

## The four phases

### Phase 1 — Stop the bleeding

- Write the disposition ledger: one row per module, marked adopt, adapt,
  port-decisions-only, evidence-only, or reject, with the reason. The fourth
  category is the one that was missing.
- Resolve the word-timestamp contradiction above.
- Run `experiments/python-agent/tests/test_interruption.py` and extract every
  assertion the Rust tests do not already make.

Exit: the ledger exists, the contradiction is closed, and no module's status
is ambiguous.

### Phase 2 — Port decisions, not code

For each retired item with a native counterpart, transcribe its contract into
`RUNTIME_INVARIANTS.md` with a citation to the retired file, and mark the
retired file superseded in its header.

- Item 3's retention rule and the 50 ms teardown deadline.
- Item 1's `echo_delay_ms` lesson: round-trip delay must be calibrated from a
  real call, which constrains the far-echo gate's lag window.
- `reflex/engine.py`'s action taxonomy for the real reasoner.
- Item 4's consent, audit, and fail-closed routing invariants, and the
  `DualVoiceRouter` contract of branded versus cloned with no implicit
  downgrade.

Exit: every prior decision is either carried into the native runtime or
explicitly declined in writing.

### Phase 3 — Collapse the tree

Three live areas, everything else read-only:

- `apps/android/` — the product. The only user-facing surface.
- `gateway/` — the cloud control plane.
- `services/` and `testlab/` — model serving and measurement.
- `experiments/` — read-only, with a README stating it does not ship.

Exit: a new contributor can tell what is alive in one directory listing.

### Phase 4 — Close the last cloud dependency

Cloned-voice synthesis is the only capability still requiring a runtime that
cannot ship on device. Benchmark the candidates on real hardware and let the
measurement decide.

- Branded slot: Kokoro (incumbent, already validated) against Inflect-Micro-v2
  (37.5 MB, fits in the APK, 6.28x real time on four CPU threads).
- Cloned slot: Audio8 INT4 ONNX or Chatterbox Turbo q4f16, both of which have
  official ONNX exports and streaming.
- Decision rule fixed in advance: real-time factor above 1.5 and first chunk
  under 300 ms on device means synthesis ships on device. Below that, one-time
  preparation moves to the cloud and returns a signed artifact, with synthesis
  still local. Only if synthesis itself cannot run locally does anything
  per-utterance stay remote.

Exit: the `DualVoiceRouter` contract is honoured by on-device engines, or the
offload is explicit, recorded, and priced.

## What carries forward from the prior work

The Python tree was not wasted. It established, with evidence, the contracts
this runtime now inherits: consent-gated cloning with SHA-256 audit and
fail-closed routing; branded and cloned engines selected explicitly per call;
interruption teardown under a hard deadline with exact played-sample
boundaries; conservative retention of only what the caller actually heard; and
a real echo canceller whose calibration lesson still constrains the native
design.

Those decisions are the asset. The Python that implemented them is reference.

## Standing rule

Before building any component, search `IMPLEMENTATION_PLAN.md` and
`experiments/` for the concept and record its disposition first. The ledger
makes that mechanical rather than a matter of memory.
