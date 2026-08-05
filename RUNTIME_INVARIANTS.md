# Triplex Runtime Structural Invariants (v0.1)

Companion to `UNIFICATION_PLAN.md`. Governs the `apps/android` runtime internals: the native media layer, generation-epoch machinery, barge-in, and the cloud-boundary interfaces. Rust for `native-media` and `agent-core` (cdylib behind JNI); Kotlin for product and control plane.

## 0. Global definitions and invariants

**Clock.** One time domain: `CLOCK_MONOTONIC_RAW` in ns (`mono_ns`). The RTP-timestamp ↔ `mono_ns` mapping is a linear fit maintained off the RT path; frames carry both. No wall clock anywhere in media or epoch logic.

**Canonical audio.** 16 kHz mono `i16`, 10 ms frames (160 samples, 320 B payload). Codec and rate conversion happen on the transport adapter thread, never in a device or mixer callback.

**RT context** = { audio-device callback (takeover mode), RTP rx/tx hot loops, mixer pop }. Inside RT context the following are forbidden and mechanically enforced (§6): heap allocation or deallocation, JNI, locks/parking, logging, syscalls beyond the driver's own, unbounded loops. The only communication out of RT context is wait-free rings and atomics.

**Two planes.**
- *Capture plane* (caller RTP → jitter → adapter → VAD → ASR): **never flushed, never epoch-cancelled.** Barge-in speech is the next turn's input; losing it is a correctness bug.
- *Synthesis plane* (reasoning → TTS → mixer → RTP egress): the sole object of epoch cancellation.

**Ownership.** A frame slot has exactly one owner at any instant. Ownership moves only by ring push/pop or freelist return. Kotlin never owns or touches a frame.

## 1. Pooled canonical frame buffer — `native-media::frame`

### 1.1 Layout

```rust
#[repr(C)]
pub struct FrameHeader {
    pub seq:     u64,  // per-stream monotonic; a gap ⇒ producer sets DISCONT
    pub mono_ns: i64,  // capture: arrival at adapter; synth: synthesis-complete time
    pub rtp_ts:  u32,  // capture: sender ts; synth: assigned at egress (0 until then)
    pub epoch:   u32,  // synth: validity tag (§2); capture: correlation only
    pub len:     u16,  // valid samples ≤ 160
    pub flags:   u16,  // DISCONT | SILENCE | EOU | FINAL_CHUNK
    pub stream:  u8,   // CAPTURE=0, SYNTH=1, REFLEX=2
    _pad: [u8; 7],
}
// Slot = FrameHeader + [i16; 160] = 384 B, in a 64-byte-aligned contiguous slab
// (one Box<[Slot]> per pool, allocated at init, optionally mlocked). Handles are
// u16 slot indices, not pointers — FFI-safe, and ownership discipline (debug-
// asserted owner tags) removes ABA concerns.
```

### 1.2 Pools and rings

Two pools. Each has exactly one acquiring thread and one releasing thread, so the freelist is itself SPSC:

| Pool | Slots | Sole acquirer | Sole releaser | Chain |
|---|---|---|---|---|
| capture | 32 (320 ms) | transport adapter (RTP rx) | ASR host | adapter → `cap_ring` → VAD → `asr_ring` → ASR → freelist |
| synth | 32 | TTS host | mixer | TTS → `synth_ring` → mixer → freelist (played **or** epoch-dropped) |

VAD passes the slot through unchanged (annotations go to a side event ring); it never releases.

```rust
/// Wait-free SPSC ring of Copy items; capacity is a power of two.
pub struct SpscRing<T: Copy> {
    head: CachePadded<AtomicU32>,
    tail: CachePadded<AtomicU32>,
    buf:  Box<[UnsafeCell<T>]>,
}
impl<T: Copy> SpscRing<T> {
    pub fn push(&self, v: T) -> Result<(), Full>; // producer thread only; Release store
    pub fn pop(&self) -> Option<T>;               // consumer thread only; Acquire load
}

pub struct FramePool { /* slab + SpscRing<u16> freelist */ }
impl FramePool {
    /// RT-safe, wait-free, never blocks. None ⇒ apply drop policy below.
    pub fn try_acquire(&self) -> Option<FrameMut>;
    // Release = freelist.push(slot). FrameMut::drop performs it (atomics only ⇒
    // RT-safe) and debug-asserts the caller is the pool's registered releaser.
}
```

**Exhaustion policy** (no blocking, no alloc, no log): capture — drop-newest, set DISCONT on the next acquired frame, bump a Relaxed atomic counter. Synth — exhaustion means the pacing bound (§3.5) was violated; drop + counter; unreachable in nominal operation.

**Depth invariants:** `synth_ring` capacity 8 frames = 80 ms hard ceiling; pacing keeps nominal queued audio ≤ 60 ms (plan objective). Capture rings hold 16. Callback work is acquire + memcpy + stamp + push — well inside the 5 ms callback→enqueue p95.

### 1.3 JNI boundary

The hot path is 100 % native. JNI surface (all non-RT; called from Kotlin main/IO dispatchers only):

```kotlin
object NativeRuntime { // :runtime-bridge
    external fun init(configCbor: ByteArray): Long          // builds pools, loads + warms models
    external fun startCall(h: Long, transportFd: Int, callMeta: ByteArray): Boolean
    external fun stopCall(h: Long, reason: Int)             // graceful; idempotent
    external fun userInterrupt(h: Long)                     // manual stop / takeover → epoch event
    external fun drainEvents(h: Long, buf: ByteBuffer): Int // fixed-size records into a direct buffer
    external fun metricsSnapshot(h: Long, buf: ByteBuffer): Int
}
```

Events (transcripts, FSM changes, placement records, outcome evidence) are fixed-size records produced by non-RT worker threads into fixed-capacity queues, drained by one dedicated thread that parks on an eventfd and feeds a Kotlin `SharedFlow`. **No callbacks from native into Kotlin, ever.** Models run through native APIs (LiteRT C). If a Kotlin-side runtime is ever unavoidable, it operates only on copies delivered through direct buffers — it never joins the RT ownership graph. Readiness is advertised to the gateway only after models are loaded and warm.

## 2. Generation epoch — `agent-core::epoch`

### 2.1 Validity tokens

```rust
pub static EPOCH:   AtomicU32; // sole writer: TurnController
pub static ASR_REV: AtomicU32; // sole writer: AsrHost; monotonic per call, never reset

#[derive(Copy, Clone, PartialEq)]
pub struct Token { pub epoch: u32, pub rev: u32 } // rev == REV_FINAL for post-commit work

pub fn snapshot() -> Token;     // two Acquire loads; a torn pair can only cause a
                                // spurious invalidation — the safe direction
pub fn valid(t: Token) -> bool; // exact equality; epoch-only for REV_FINAL tokens
```

- Work that depends on a specific ASR partial carries `(epoch, rev)`. Authoritative post-commit work carries `(epoch, REV_FINAL)`.
- Comparisons are equality, never ordering. Each atomic has exactly one writer; all other threads Acquire-read.
- `ASR_REV` increments on every emitted partial revision, so within-turn speculation self-invalidates without touching the epoch.

### 2.2 Turn FSM (owner: TurnController thread — the only epoch writer)

States: `LISTENING → ENDPOINTING → COMMITTED → SPEAKING → LISTENING`, transient `YIELDING`, terminal `ENDED`.

**The epoch increments on every entry to LISTENING** — normal drain, barge-in, user stop, takeover, or error recovery. Epoch ≡ response-cycle id: anything tagged with an older epoch is definitionally stale, whether cancelled or merely late.

| State | Event | Actions | Next |
|---|---|---|---|
| LISTENING | stable partial ∧ trailing silence ≥ VAD floor | open adaptive endpoint timer `d ∈ [80, 250] ms` = f(partial stability, semantic completeness, prosody); launch speculative reasoning tagged `(E, R)` | ENDPOINTING |
| ENDPOINTING | `VadOnset` | cancel timer; speculation self-invalidates via rev | LISTENING (same E) |
| ENDPOINTING | timer fires | AsrHost finalizes → `R_f`; emit `TurnEnd`; reflex-ack lookup; speculation with `R == R_f` promotes to authoritative, else discard and rerun | COMMITTED |
| COMMITTED | first synth frame enqueued (reflex or TTS) | record `t_first_audio` | SPEAKING |
| COMMITTED | `VadOnset` (confirmed) | barge-in path A: `EPOCH += 1`; nothing was heard ⇒ drop the plan and **reopen the user segment** (§3.6) | LISTENING |
| SPEAKING | `VadOnset` (confirmed) | barge-in path B: `EPOCH += 1` (publication), flush command to mixer (§3.4) | YIELDING |
| YIELDING | mixer flush-ack (≤ 1 period) | compute heard-state, commit truncated utterance to history | LISTENING |
| SPEAKING | `PlayoutDrained(FINAL_CHUNK)` | commit full utterance; `EPOCH += 1` | LISTENING |
| any | `Hangup` / `Stop` / `Takeover` | `EPOCH += 1`; cancel all; flush outcome evidence | ENDED |

### 2.3 Stage contracts

Every worker follows one skeleton:

```rust
pub trait EpochStage {
    type In; type Out;
    /// Check `valid(item.token)` before starting AND at every cooperative
    /// checkpoint (≥ every ~10 ms of compute). Invalid ⇒ drop silently, free inputs.
    fn process(&mut self, item: Tagged<Self::In>) -> Option<Tagged<Self::Out>>;
}
```

- **AsrHost** — capture plane, epoch-agnostic input; stamps outputs `(E, ++ASR_REV)`. Decoder state persists across epochs; it resets per call or on explicit segment close, never on barge-in.
- **Reasoner** — speculative `(E, R)` and authoritative `(E, REV_FINAL)` modes. Cooperative cancellation between decode steps: each task polls a per-task `AtomicBool` doorbell in addition to lazy token checks, bounding cancel latency to one decode step.
- **TtsHost** — consumes plan chunks `(E, REV_FINAL)`; emits synth frames stamped `E` plus word-boundary alignment marks (sample offset → text offset) into a side ledger (§3.6 depends on these). Checkpoints between synthesis chunks.
- **Mixer** (RT) — per pop: `if frame.epoch != EPOCH.load(Acquire) { free; continue }` else feed egress and append a playout-ledger record. This single compare is the entire RT-side flush: O(1) per frame, no queue surgery, no locks.
- **CommitExecutor** (single thread) — sole authority for side effects and conversation-state writes. Per item: validate token, then execute without yielding. Validation is the linearization point: an epoch increment after validation does not un-call an external effect. Truthful outcome reporting depends on this being the only effect path.

**Queue-surgery-free invalidation invariant:** no thread ever traverses another thread's queue. Staleness is discovered lazily at pop (token check) plus eagerly via doorbells; doorbells affect latency only, never correctness.

## 3. Barge-in protocol (speech onset → epoch bump → silence)

Budgets (p95): onset → decision ≤ 40 ms; onset → caller-audible stop ≤ 150 ms (stretch 100 ms).

### 3.1 Detection (VAD thread)
Per 10 ms capture frame: NN speech probability + energy gate. Onset candidate = `p > θ_on` for k consecutive frames (k = 2–4, adaptive to noise floor) ⇒ 20–40 ms of evidence, inside the 40 ms budget.

### 3.2 Echo gate
Direct-media calls have **no local acoustic loop** — agent audio never touches the device speaker; both directions are network streams. The false-trigger risk is far-end echo (the caller's device reflecting agent audio back). Gate: normalized cross-correlation of the incoming window against the playout ledger over lag ∈ [40, 400] ms; correlation > θ_echo suppresses the onset. Bounded compute, runs on the VAD thread. Local AEC applies only in human-takeover/monitor mode, where the device speaker and mic actually engage.

### 3.3 Decision
`SpeechOnset { mono_ns, conf }` → SPSC ring → TurnController doorbell (futex wake, < 1 ms). Ignored unless FSM ∈ {COMMITTED, SPEAKING}. Policy check (minimum speech duration, echo-gate verdict), then:

### 3.4 Publication and invalidation (ordered)
1. `EPOCH.fetch_add(1, AcqRel)` — the cancellation broadcast. Every consumer discards stale work at its next checkpoint or pop.
2. Ring per-worker cancel doorbells (reasoner, TTS, racer remotes) — bounds long-kernel cancel latency.
3. Push `Flush { old_epoch }` to the mixer control ring — redundant with the epoch compare; also switches egress to the silence policy.
4. Cancel remote in-flight work: HTTP/2 RST_STREAM / gRPC cancel via racer epoch subscription (§4.3).
5. Enter YIELDING; await mixer ack (≤ 10 ms).

### 3.5 Making the stop audible
- The mixer drops all stale synth frames on its next pop cycle (≤ 10 ms).
- **Pacing invariant: egress never runs more than 40 ms ahead of realtime.** Audio already handed to the RTP socket is irrevocable, so the in-flight window is a structural bound, not a tuning knob. Caller-audible stop = decision + ≤ 10 ms mixer + ≤ 40 ms in-flight + network/far-end jitter (~40–80 ms) — the 150 ms budget holds by construction.
- After the flush, egress emits comfort-noise/silence frames: stream continuity, no RTP gap, no far-end PLC artifacts.

### 3.6 Conservative heard-state retention
- Playout ledger (SPSC from mixer): `{ epoch, seq, egress_mono_ns, samples }`.
- Heard watermark — conservative means *only what we are sure was heard*: the greatest sample with `egress_mono_ns + min_one_way + margin ≤ onset_mono_ns`, where `min_one_way` is the transit floor (RTCP RTT floor) and the margin (default 60 ms) covers transit uncertainty above that floor. The margin is *added*: it must make frames harder to count as heard, never easier.
- Map the watermark to text via TTS alignment marks: retain through the last **fully emitted word** at or before the watermark.
- Commit via CommitExecutor: utterance prefix, `interrupted: true`, and a machine-readable `unspoken_suffix` — the E+1 reasoner knows both what was said and what was cut.
- Watermark before the first word ⇒ drop the utterance entirely and **reopen the user segment**: the ASR continuation appends to the prior final transcript so the merged user turn reads as one utterance.
- **No rollback.** Cancellation is monotonic. A false start (onset confirmed, then < 120 ms of speech and an empty ASR delta) never restores E−1 work; the current-epoch reasoner may elect to re-speak using `unspoken_suffix`.

## 4. Cloud boundary — telemetry gateway and capability racing

### 4.1 Placement visibility (plan-mandated)

```rust
#[repr(u8)]
pub enum Placement { Local, RemoteAsr, RemoteReasoning, RemoteTts, RemoteAgent }

pub struct PlacementRecord {
    stage: Stage, mode: Placement, reason: ReasonCode,
    token: Token, t_start: i64, t_first: i64, t_done: i64,
}
```

Every stage execution emits exactly one record (a race emits winner and loser). Records flow through the event ring; only aggregates the audit policy enumerates leave the device.

### 4.2 Gateway interface (Kotlin, control plane only)

```kotlin
interface ControlGateway { // :gateway — never carries media, never runs on model threads
    suspend fun registerDevice(att: DeviceAttestation): Registration
    suspend fun readiness(state: ReadinessReport)               // models warm, endpoint registered
    suspend fun authorizeOutbound(task: TaskRef, dest: E164): RouteGrant  // typed, permissioned
    suspend fun fetchPolicy(): PolicyBundle                     // tasks, inbound rules, placement policy
    suspend fun postOutcome(evidence: OutcomeEvidence)          // minimal truthful audit
    suspend fun capabilityCall(cap: CapabilityRef, req: ByteArray): ByteArray // narrow proxy
}
```

Boundary invariants: every call suspends on the IO dispatcher; the native runtime never blocks on the gateway; readiness is advertised only after warm-up completes; gateway loss degrades to policy-cached local operation and can never stall a live call.

Telemetry: RT code touches only fixed `AtomicU64` counters and the pre-sized event ring. A scavenger drains at 10 Hz into HDR histograms keyed to the plan's p95 table. Raw audio and transcripts never enter telemetry.

### 4.3 Latency-based racing — `agent-core::racer`

```rust
/// Marker: no side effects, idempotent, schema-validated output. Only such ops may race.
pub trait PureInference { type Out: Validate; }

pub struct Race { state: AtomicU8 /* OPEN | WON_LOCAL | WON_REMOTE | DEAD */ }
pub async fn race<T: PureInference>(tok: Token, pol: HedgePolicy) -> Option<Placed<T::Out>>;
```

- Arbitration: the first *valid* result (token still valid + `Validate` passes) CASes `OPEN → WON_*`; the loser is cancelled (local doorbell / RST_STREAM). A failed CAS discards its own result.
- Hedging: remote launch is delayed by `min(p50_local, cap)` unless the placement predictor already favors remote; the predictor is trained offline from PlacementRecords.
- Epoch subscription: every race registers on the epoch doorbell; an increment moves it to `DEAD` and cancels both sides — losers never linger.
- **Never races** (enforced by not implementing `PureInference`): conversation-state commits, tool side effects, anything past TTS first-PCM. A TTS race must resolve before the first synth frame is enqueued; the mixer accepts one producer per epoch — mid-utterance voice switching is forbidden.

## 5. Thread inventory and priorities

| Thread | Priority class | RT-tagged | In | Out |
|---|---|---|---|---|
| device callback (takeover mode only) | HW RT | yes | driver | cap ring |
| RTP rx (jitter → adapter) | URGENT_AUDIO | yes | socket | cap ring |
| RTP tx / mixer | URGENT_AUDIO | yes | synth ring | socket, ledger |
| VAD + echo gate | URGENT_AUDIO − 1 | no | cap ring | asr ring, onset ring |
| TurnController (epoch writer) | high, event-driven | no | onset/event rings | EPOCH, control rings |
| AsrHost | high | no | asr ring | partial events |
| Reasoner pool | default, cancellable | no | turn events | plan chunks |
| TtsHost | high − 1 | no | plan chunks | synth ring |
| CommitExecutor (single) | default | no | effect queue | history, tools |
| event drain → Kotlin | default | no | event rings | JNI direct buffer |
| gateway IO | Kotlin IO dispatcher | no | — | HTTPS |

The SIP stack (PJSIP/Linphone) keeps its own threads; the adapter attaches at the conference-bridge/media port and performs only copy + stamp + push, measured against the 5 ms objective.

## 6. Mechanical enforcement

- **Alloc guard:** debug/CI `#[global_allocator]` wrapper panics on alloc/dealloc from RT-tagged threads; soak tests run with it enabled.
- **Lint wall:** `native-media::rt` modules deny `std::sync::Mutex`, `log`, `println!`, and direct `Box::new` via clippy `disallowed_*` lists; atomic counters are the only observability in RT code.
- **Model checking:** `loom` over `SpscRing` and the epoch publication/commit ordering; `miri` over slab and handle code.
- **Latency CI:** the harness replays recorded PSTN traces plus device-lab calls and asserts the plan's p95 table (callback→enqueue 5 ms, onset→decision 40 ms, onset→silence 150 ms, dropped/duplicated frames = 0, queued audio ≤ 60 ms). A regression fails the build; a slow baseline does not move the bar.
- **Ledger replay:** every barge-in in test produces a heard-state proof (ledger slice + alignment marks) checked against the ground-truth stimulus — the evidence artifact the plan requires before anything is called production-ready.

## Deferred to spikes

Exact VAD θ/k constants per device tier; PJSIP vs Linphone adapter surface; LiteRT cancellation-checkpoint granularity vs decode-step cost; takeover-mode device-audio pool topology.
