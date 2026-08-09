# Triplex — Canonical Architecture

> **One canonical technical doc. Synthesized from `docs/*.md` — keeps every architectural shape, struct, and interface that is live today; removes history (how things used to be) and dated test runs. `docs/` remains as history, not to be extended.**

**Android voice agent that clones your voice from one sentence and makes/answers calls in that voice. Undetectable = latency, not just timbre. Phone does the work; cloud is explicit fallback.**

**Truth rule:** Nothing ships as production-ready without real PSTN + real device + timing + interruption evidence.

---

## 1. Product + Architecture

**Product:** User creates consented voice profile (one sentence = consent record + cloning reference, 3–30s WAV, SHA-256 audited), gets a Plivo number, creates bounded outbound tasks (typed + permissioned) and inbound policies. Agent shows live state, can be stopped/handed over, reports truthful evidenced outcome.

```
PSTN <--> Plivo (SIP/TLS+SRTP, direct RTP) <--> Android agent runtime (VAD->ASR->turn->reasoning->TTS->mixer)
Plivo webhooks <--> Gateway (bridge.secure.build: auth, numbers, creds, routing XML, policy, audit)
Android -.-> cloud models only if measured win (REMOTE_ASR/REASONING/TTS — never conversation logic)
```

**Placement is visible per utterance:** `LOCAL` | `REMOTE_ASR` | `REMOTE_REASONING` | `REMOTE_TTS` | `REMOTE_AGENT` (phone off — open decision, ships as honest unavailable today). One orchestration impl in Rust; cloud supplies models behind trait seams (`SpeechModel`/`ReasonerModel`/`TtsModel`).

**Call paths:**
- *Inbound, phone ready:* PSTN -> Plivo Answer URL -> gateway validates `ready` (models warm) -> SIP dials Android -> direct RTP -> local agent.
- *Outbound:* App `CreateTask` -> gateway `authorizeOutbound(dest E164)` -> Plivo connects PSTN to Android -> agent conducts task.
- *Unavailable:* honest unavailable / message-taking / transfer per policy (not built).

---

## 2. Canonical Shapes & Interfaces (keep — normative)

### 2.1 Global invariants

- **Clock:** Single `CLOCK_MONOTONIC_RAW` ns (`mono_ns`). RTP<->mono_ns linear fit off RT path; frames carry both. No wall clock in media/epoch.
- **Canonical audio:** 16 kHz mono `i16`, 10 ms frames (160 samples, 320B). Codec/rate conversion on transport adapter thread, never in callback.
- **RT context** = {device callback (takeover), RTP rx/tx hot loops, mixer pop}. Forbidden in RT: heap alloc/free, JNI, locks/parking, logging, syscalls beyond driver, unbounded loops. Only rings + atomics cross boundary.
- **Two planes:** Capture (caller RTP -> jitter -> adapter -> VAD -> ASR) **never flushed/cancelled** — barge-in speech is next turn. Synthesis (reasoning->TTS->mixer->RTP egress) is sole target of epoch cancellation.
- **Ownership:** One owner per frame slot; moves only by ring push/pop or freelist. Kotlin never owns a frame.

### 2.2 Frame buffer — `native-media::frame`

```rust
#[repr(C)]
pub struct FrameHeader {
    pub seq:     u64,  // per-stream monotonic; gap => DISCONT
    pub mono_ns: i64,  // capture: adapter arrival; synth: synthesis-complete
    pub rtp_ts:  u32,  // capture: sender ts; synth: assigned at egress (0 until then)
    pub epoch:   u32,  // synth: validity tag; capture: correlation only
    pub len:     u16,  // valid samples ≤160
    pub flags:   u16,  // DISCONT | SILENCE | EOU | FINAL_CHUNK
    pub stream:  u8,   // CAPTURE=0, SYNTH=1, REFLEX=2
    _pad: [u8; 7],
}
// Slot = FrameHeader + [i16; 160] = 384 B, 64-byte aligned contiguous slab
// (one Box<[Slot]> per pool, allocated at init, optionally mlocked)
// Handles are u16 indices — FFI-safe, no ABA.
```

```rust
/// Wait-free SPSC ring of Copy items; capacity power-of-two.
pub struct SpscRing<T: Copy> {
    head: CachePadded<AtomicU32>,
    tail: CachePadded<AtomicU32>,
    buf:  Box<[UnsafeCell<T>]>,
}
impl<T: Copy> SpscRing<T> {
    pub fn push(&self, v: T) -> Result<(), Full>; // producer only; Release
    pub fn pop(&self) -> Option<T>;               // consumer only; Acquire
}
pub struct FramePool { /* slab + SpscRing<u16> freelist */ }
impl FramePool {
    pub fn try_acquire(&self) -> Option<FrameMut>; // RT-safe, wait-free, never blocks
    // Release = freelist.push(slot) via FrameMut::drop (atomics only, RT-safe)
}
```

**Pools (each one acquirer + one releaser, so freelist is SPSC):**

| Pool | Slots | Acquirer | Releaser | Chain |
|---|---|---|---|---|
| capture | 32 (320ms) | transport adapter (RTP rx) | AsrHost | adapter -> `cap_ring` -> VAD -> `asr_ring` -> ASR -> freelist |
| synth | 32 | TtsHost | mixer | TTS -> `synth_ring` -> mixer -> freelist (played **or** epoch-dropped) |

VAD passes slot unchanged (annotations to side event ring), never releases.

**Exhaustion (no block/alloc/log):** capture: drop-newest, set DISCONT on next acquire, bump Relaxed counter. Synth: drop + counter (means pacing bound violated — unreachable nominally).

**Depth:** `synth_ring` cap 8 frames = 80ms hard ceiling, pacing keeps nominal <=60ms. `cap_ring`/`asr_ring` 16 each. Callback = acquire + memcpy + stamp + push (inside 5ms budget).

### 2.3 JNI boundary — `native-media` / `nativebridge`

Hot path 100% native. All JNI is non-RT, from Kotlin main/IO dispatchers only:

```kotlin
object NativeRuntime { // :runtime-bridge
    external fun init(configCbor: ByteArray): Long          // pools, loads+warms models
    external fun startCall(h: Long, transportFd: Int, callMeta: ByteArray): Boolean
    external fun stopCall(h: Long, reason: Int)             // idempotent
    external fun userInterrupt(h: Long)                     // -> epoch event
    external fun drainEvents(h: Long, buf: ByteBuffer): Int // fixed-size records -> direct buffer
    external fun metricsSnapshot(h: Long, buf: ByteBuffer): Int
}
```

Events (transcripts, FSM changes, placement records, outcome evidence) are fixed-size records from non-RT workers into fixed-capacity queues, drained by one thread parked on eventfd into Kotlin `SharedFlow`. **No native->Kotlin callbacks.** Models via native APIs (LiteRT C). Readiness advertised only after warm. Kotlin-side runtime (if ever) runs on copies via direct buffers.

Also: `nativebridge/agent/AgentBridge.kt` (baseline session `AgentState`/`AgentStatus` poll), `nativebridge/audio/AudioPipeline.kt`+`FramePool.kt`, `nativebridge/runtime/NativeRuntimeManager.kt`.

### 2.4 Generation epoch — `agent-core::epoch`

```rust
pub static EPOCH:   AtomicU32; // sole writer: TurnController
pub static ASR_REV: AtomicU32; // sole writer: AsrHost; monotonic per call, never reset

#[derive(Copy, Clone, PartialEq)]
pub struct Token { pub epoch: u32, pub rev: u32 } // REV_FINAL for post-commit

pub fn snapshot() -> Token;     // two Acquire loads; torn pair => spurious invalidation (safe)
pub fn valid(t: Token) -> bool; // exact equality; epoch-only for REV_FINAL
```

- Work on a specific ASR partial carries `(epoch, rev)`; authoritative post-commit carries `(epoch, REV_FINAL)`.
- Equality (never ordering); one writer per atomic, others Acquire-read.
- `ASR_REV++` per partial revision => within-turn speculation self-invalidates without epoch bump.

### 2.5 Turn FSM — `agent-core::turn` (sole epoch writer)

States: `LISTENING -> ENDPOINTING -> COMMITTED -> SPEAKING -> LISTENING`, transient `YIELDING`, terminal `ENDED`. **Epoch++ on every entry to LISTENING.**

| State | Event | Actions | Next |
|---|---|---|---|
| LISTENING | stable partial && trailing silence >= VAD floor | open adaptive timer `d in [80,250]ms` = f(stability, semantic completeness, prosody); launch speculative `(E,R)` | ENDPOINTING |
| ENDPOINTING | `VadOnset` | cancel timer; speculation self-invalidates via rev | LISTENING (same E) |
| ENDPOINTING | timer fires | AsrHost finalizes -> `R_f`; `TurnEnd`; reflex lookup; promote if `R==R_f` else rerun | COMMITTED |
| COMMITTED | first synth frame enqueued | record `t_first_audio` | SPEAKING |
| COMMITTED | `VadOnset` confirmed | barge-in A: `EPOCH+=1`; drop plan, **reopen user segment** | LISTENING |
| SPEAKING | `VadOnset` confirmed | barge-in B: `EPOCH+=1`, flush to mixer (§3.4) | YIELDING |
| YIELDING | mixer flush-ack (<=1 period) | compute heard-state, commit truncated utterance | LISTENING |
| SPEAKING | `PlayoutDrained(FINAL_CHUNK)` | commit full; `EPOCH+=1` | LISTENING |
| any | `Hangup`/`Stop`/`Takeover` | `EPOCH+=1`; cancel all; flush evidence | ENDED |

### 2.6 Stage contracts — `agent-core::{reasoner,tts,heard}`

```rust
pub trait EpochStage {
    type In; type Out;
    /// Check valid(token) before start AND every ~10ms. Invalid => drop silently, free inputs.
    fn process(&mut self, item: Tagged<Self::In>) -> Option<Tagged<Self::Out>>;
}
```

- **AsrHost** — capture plane, epoch-agnostic in, stamps out `(E, ++ASR_REV)`. Decoder persists across epochs, resets per call/segment, never on barge-in.
- **Reasoner** — speculative `(E,R)` + authoritative `(E, REV_FINAL)`; cooperative cancel between decode steps via per-task `AtomicBool` doorbell + token checks (cancel <=1 decode step).
- **TtsHost** — consumes `(E, REV_FINAL)` plan chunks; emits synth frames stamped `E` + **word-boundary marks (sample->text) + unconditional chunk-end mark** (marks are lower bound — engine without word marks degrades to whole-segment retention).
- **Mixer (RT)** — per pop: `if frame.epoch != EPOCH.load(Acquire) { free; continue }` else egress + playout ledger append. O(1) flush, no queue surgery.
- **CommitExecutor (single thread)** — sole side-effect authority. Validate token then execute without yielding. Validation is linearization point — epoch bump after does not un-do effect. Truthful outcome depends on this single path.

### 2.7 Barge-in — speech onset -> silence

Budgets (p95): onset->decision 40ms; onset->caller-audible stop 150ms (stretch 100ms).

- **Detection (VAD thread):** per 10ms frame NN prob + energy gate; onset = `p > θ_on` for k=2–4 consecutive frames (20–40ms).
- **Echo gate (VAD thread):** Direct media has no acoustic loop; risk is far-end echo. Gate: normalized xcorr of incoming window vs playout ledger over lag `[40,400]ms`; `>θ_echo` suppresses onset. Bounded compute. Local AEC only in takeover/monitor mode.
- **Decision:** `SpeechOnset{mono_ns, conf}` via SPSC -> TurnController futex wake (<1ms); ignored unless COMMITTED/SPEAKING; policy check then ordered publication:
  1. `EPOCH.fetch_add(1, AcqRel)` — broadcast
  2. Per-worker doorbells (reasoner/TTS/remote racers)
  3. `Flush{old_epoch}` to mixer control ring (redundant with epoch check, also starts silence)
  4. Remote cancel (RST_STREAM / gRPC cancel via racer epoch)
  5. Enter `YIELDING`, await mixer ack (<=10ms)
- **Making stop audible:** Mixer drops stale frames next pop (<=10ms). **Pacing invariant: egress never >40ms ahead of realtime** (structural bound). Audible stop = decision + <=10ms mixer + <=40ms in-flight + network/jitter(~40–80ms).
- **Heard-state (conservative):** Playout ledger `{epoch, seq, egress_mono_ns, samples}`; watermark = greatest sample with `egress_mono_ns + min_one_way(RTCP RTT floor) + 60ms margin <= onset_mono_ns` (margin always makes harder to count as heard). Map via TTS alignment marks to last fully-emitted word; ledger has chunk-end mark unconditionally. Commit via CommitExecutor: `interrupted:true` + `unspoken_suffix` for next turn. Watermark before first word => drop utterance + **reopen user segment** (ASR continuation appends). No rollback; false start (<120ms) never restores E-1.

### 2.8 Cloud boundary + racing

```rust
#[repr(u8)] pub enum Placement { Local, RemoteAsr, RemoteReasoning, RemoteTts, RemoteAgent }
pub struct PlacementRecord { stage: Stage, mode: Placement, reason: ReasonCode, token: Token, t_start:i64, t_first:i64, t_done:i64 }
```

Every stage emits one record (race emits winner+loser) via event ring; only audited aggregates leave device.

```kotlin
interface ControlGateway { // never carries media, never on model threads; suspends on IO dispatcher
    suspend fun registerDevice(att: DeviceAttestation): Registration
    suspend fun readiness(state: ReadinessReport)
    suspend fun authorizeOutbound(task: TaskRef, dest: E164): RouteGrant
    suspend fun fetchPolicy(): PolicyBundle
    suspend fun postOutcome(evidence: OutcomeEvidence)
    suspend fun capabilityCall(cap: CapabilityRef, req: ByteArray): ByteArray // narrow proxy
}
```

Gateway never blocks native runtime; loss => policy-cached local operation; readiness only after warm.

```rust
pub trait PureInference { type Out: Validate; } // marker: no side effects, idempotent
pub struct Race { state: AtomicU8 /* OPEN|WON_LOCAL|WON_REMOTE|DEAD */ }
pub async fn race<T: PureInference>(tok: Token, pol: HedgePolicy) -> Option<Placed<T::Out>>;
```

Arbitration: first *valid* (token valid + `Validate`) CASes `OPEN->WON_*`, loser cancelled (doorbell / RST_STREAM). Hedging: remote delayed by `min(p50_local, cap)` unless predictor favors remote. Every race subscribes to epoch doorbell -> `DEAD` on bump. Never races: commits, tool effects, anything past TTS first PCM (one producer/epoch — no mid-utterance voice switch). Placement hedges via offline `PlacementRecord` predictor.

### 2.9 Threads + enforcement

| Thread | Priority | RT | In -> Out |
|---|---|---|---|
| device callback (takeover only) | HW RT | yes | driver -> cap_ring |
| RTP rx (jitter->adapter) | URGENT_AUDIO | yes | socket -> cap_ring |
| RTP tx / mixer | URGENT_AUDIO | yes | synth_ring -> socket+ledger |
| VAD + echo gate | URGENT_AUDIO-1 | no | cap_ring -> asr_ring + onset |
| TurnController (sole epoch writer) | high (event-driven) | no | onset/event -> EPOCH + control rings |
| AsrHost | high | no | asr_ring -> partials |
| Reasoner pool | default/cancellable | no | turn -> plan chunks |
| TtsHost | high-1 | no | plan -> synth_ring |
| CommitExecutor (single) | default | no | effect queue -> history/tools |
| event drain -> Kotlin | default | no | event rings -> direct buffer -> SharedFlow |
| gateway IO | Kotlin IO | no | — -> HTTPS |

SIP stack (PJSIP/Linphone) owns its threads; adapter attaches at conference bridge / media port (copy+stamp+push only).

Enforcement: debug/CI `#[global_allocator]` panics on RT alloc; clippy `disallowed_*` denies `std::sync::Mutex`/`log`/`println!`/`Box::new` in `native-media::rt`; atomics only for observability; `loom` over `SpscRing` + epoch publication/commit ordering; `miri` over slab/handles; latency CI replays recorded PSTN traces + device-lab calls and asserts p95 table below (regression fails build; slow baseline doesn’t move bar); ledger replay: every barge-in produces slice+alignment marks checked vs stimulus ground truth.

**Mined from `experiments/python-agent/tests/test_interruption.py` (one real defect found):**
| Assertion | Rust before | Fix |
|---|---|---|
| Generation aborted on confirmed speech | already (epoch bump) | — |
| Exactly one flush per barge-in | already (one `interrupt()` per publication) | — |
| Teardown under 50ms | already (budgets) | — |
| Retain only fully-played segments | fixed via unconditional chunk-end mark (marks = lower bound) | — |
| **Deadline must not cancel abort or remote flush** | **defect: YIELDING waited forever on flush-ack (wedge)** | **Fixed: `flush_ack_deadline_ns` 50ms releases FSM + commits conservative heard-state, cancellation already published stays; regression `missing_flush_ack_still_commits_conservative_heard_state`** |

Inherited contracts (retired Python `interruption/{tear_down,state_alignment}`, `transport/echo_canceller` ~500 LOC NLMS, `reflex/engine` 237, `audio/vad` 261): deadline bounds reporting not cancellation; one flush per barge-in; retain only fully-rendered (whole-segment fallback if no word marks); echo delay must be calibrated from **real PSTN**, not synthetic — lag [40,400] is start range; adaptation freezes during double-talk (never flush capture); reflex taxonomy backchannel/barge-in/filler <100ms.

---

## 3. Subsystems — Requirements + Current Status

| Subsystem | Requirement | Status now | Next |
|---|---|---|---|
| **Android App** `app/` Compose+Hilt (SDK35, spike floor 29, Kotlin 2.0.21 Gradle 8.10.2) — `MainActivity.kt`/`TriplexApplication.kt`/`di/{Native,Network,Repository}Module` + `telephony/sip/TelephonyController.kt` bridging PJSIP | Enrollment->Dashboard(Stop/history/FAB)->CreateTask(task_type+E164+params)->ActiveCall; VoiceClone consent->capture->prepare->preview->revoke; placement-tagged `LatencyTracker` p50/p95; `data/local/SecureStorage.kt` hardware-backed, `data/remote/GatewayApi.kt` Ktor, `domain/model/{Models,State}.kt`; `tts/TtsBenchmark.kt`+`TpuBenchmark.kt` | Builds/installs/runs on Pixel 10 Pro Fold | `control/TurnController.kt` shadows Rust FSM — must remove (single writer); reasoning is stub; `app/src/main/cpp/native_runtime.cpp` thin JNI |
| **Agent Core** `agent` (`triplex-agent-core` `cdylib`+`rlib`, depends `native-media`+`libc`) | Above epoch/FSM/stage/heard/barging/racing contracts; `ffi.rs`; Cargo `codegen-units=1 lto=thin panic=abort` | 19 tests; barge-in proven on device; `flush_ack_deadline_ns` (50ms) fixes YIELDING hang (`missing_flush_ack_still_commits`) ; chunk-end mark resolves word-timestamp contradiction | Wire real `SpeechModel`/`ReasonerModel`/`TtsModel` (now baseline tone TTS only); VAD `θ/k` per device tier deferred to spike |
| **Telephony** `telephony-plivo` + `supertonic_pack`/`ve_pack`/`bert_pack`/`google_tensor_runtime` (`TelephonyController`/`PlivoSipEndpoint`) | PJSIP (vs Linphone still open): TLS+SRTP fail-closed, registration, lifecycle reachability, caller PCM in / generated PCM injection without loopback, DTMF/flush/hangup/handoff | 16 tests; direct RTP spike works on PSTN when creds present; Supertonic ~250MB staged on first launch (falls back to tone) | Blocked: needs real Plivo SIP creds in `SecureStorage` (`NO_CREDENTIALS` today); outbound + inbound handoff both waiting on Plivo |
| **Gateway** `gateway/app` FastAPI/asyncpg `bridge.secure.build` | `GET /health`; `POST /auth/register` (mints `X-Device-Token`); `POST /devices/register|ready`; `GET /devices/status`; `POST /admin/sip-credentials` (`X-Admin-Key`); `GET /devices/sip-credentials`; `POST /plivo/answer|hangup` (XML dial, `verify_v3`); `POST /tasks` + `VOICE_SERVICE_URL` (`REMOTE_TTS` today); narrow `capabilityCall` proxy | Live, sig-validated, real inbound PSTN hits server (replies unavailable until handoff) | No tests; voice `REMOTE_TTS` explicit and billed per minute |
| **Voice** `services/voice_models` (Kokoro, Qwen3-TTS) + `voice-clone` | `DualVoiceRouter` contract: per-call explicit branded vs cloned, missing clone **fails closed (never silent downgrade)**; consent requires speaker_id+consent_id+tz-aware time+verbatim text+3-30s WAV SHA-256; prompt caching, serialized generation | Branded **Inflect-Micro-v2 LOCAL always**: 37.7MB (decode 30.4 + duration 7.3) APK asset, RTF 5.75-6.08 on 4 threads (3.48 on 1 thread), short turn ~210ms (gate is `RTF>1.5 && <300ms` — passed 3.6x). Cloned via server Qwen 0.6B `REMOTE_TTS` works end-to-end (real voice tested) | Cloned on-device **OPEN**: candidates Chatterbox Turbo q4f16 ~380MB MIT (1-step distilled, beat ElevenLabs 63.75% blind) and Audio8 INT4 ~572MB Apache2. No phone bench yet. If CPU fails gate → LiteRT AOT on Tensor G5 (see §4). |
| **Testlab** `testlab/` | PSTN verification (`live_pstn_test.py`), `tts-bench/bench_onnx.py`, `litert-aot/*`, `validation/check_gates.py` — measures p50/p95/p99 across Wi-Fi/LTE/5G, loss/jitter/handoff, accents/noise | TTS bench done; Inherent/BERT TPU bench done (see §4) | PSTN soak not cleared (needs number + GPU + echo calibration) |
| **Experiments** `experiments/` | Retired `python-agent`/`legacy-mobile`/Chime-KVS — **read-only history**, not to extend | Retired per `FINAL_UNIFICATION.md` | Do not revive |

---

## 4. TTS Placement & Runtime (decided)

**Gate (fixed before measuring):** `RTF > 1.5` and `first chunk < 300ms` on device => ships on device. Else one-time cloud preparation -> signed artifact, synthesis still local. Only if synthesis itself cannot run local does per-utterance stay remote.

**Branded — DECIDED ONNX CPU:** `cpu` EP measured on Pixel 10: `RTF 4.8-5.4` vs `nnapi`/`xnnpack`/`nnapi_npu` all `0/886` nodes (ORT rejects: `Dynamic shape` + `int64 Gather`). No GPU EP exists for ORT on Android (only CPU/NNAPI/XNNPACK). So changing runtime buys nothing here. Host (Apple Silicon) 15.7/4.6 => phone at ~40% (useful ratio, not predictive).

**Cloned — target LiteRT AOT on Tensor G5 if CPU fails:** Tensor G5 TPU proven in-house on this same Pixel (`rango`, Pixel 10 Pro Fold Android 17): BERT gate `414->7.02ms 59x 734/734 ops` and Inherent Conformer (`small_conformer` 6 layers, 4 heads, hidden 256, 750-frame attn on `[1,3000,128]` mel) `512->20ms 25.6x 724/724 ops` via `vendor/lib64/libedgetpu_litert.so` — present **and listed in `/vendor/etc/public.libraries.txt`** — with sepolicy `untrusted_app_all` granted `edgetpu_app_service service_manager find` + `edgetpu_device chr_file ioctl/read/write/map` + `edgetpu_tachyon_server binder transfer` (app never opens `/dev/edgetpu-soc` `0660 system:system`; gets fd over binder). APK bundles `libLiteRtDispatch_GoogleTensor.so` (exports `LiteRtDispatchGetApi`, dlopens vendor lib — 71 Darwinn symbols `thrInitialize`/`thrContextCreate`/…); `LiteRt*` symbols live only in APK, not vendor. LiteRT is only format that reaches TPU (also checked: MediaPipe Tasks = Audio Classification only, Edge Gallery = STT only, AICore/ML Kit = text-out only; `com.google.android.tts` bound to AICore but only fixed-voice `TextToSpeech` API). ExecuTorch has no Tensor backend (its Vulkan targets Adreno/Mali/NVIDIA — Pixel GPU is `PowerVR DXT-48-1536` `ro.hardware.vulkan=powervr`).

Why ORT NPU fails (don’t retry without fixing export): `cpu` `RTF 4.8-5.4` vs `nnapi`/`nnapi_npu`/`xnnpack` all `0/886` nodes (ORT verbose: `base_op_builder.cc:113 Dynamic shape not supported for input:tokens` + `base_op_builder.cc:152 [Gather] Input type: [7] (int64) not supported`). Graph shapes: `duration 1413 nodes (428 post-opt)` = `Constant×648 Unsqueeze×145 Concat×81 Reshape×76 Transpose×59 Gather×50 Shape×49 Slice×41` vs `Conv×22 MatMul×14`; `decode 983 (458 post-opt)` = `Conv×114 LeakyRelu×69 Slice×109 Tanh×17 Sigmoid×16 ConvTranspose×4`. So `duration` (monotonic alignment search, `mel_len` data-dependent) is never offloadable; `decode` (HiFi-GAN vocoder) is if `mel_len` made static. No ORT GPU EP exists on Android at all (only `CPU, NNAPI, XNNPACK` — no Vulkan/OpenCL/WebGPU native).

Work if needed: 1) re-export with fixed `text_len`/`mel_len` buckets + masking, 2) ONNX→TFLite, 3) AOT for `Google_Tensor_G5` (aim 1 partition, full offload), 4) ship matched `litert:2.1.6` + `libLiteRtDispatch_GoogleTensor.so`, 5) split: vocoder on TPU, alignment on CPU. AOT needs Linux x86_64 Ubuntu 22.04 Bazel 7.4.1 NDK r28b + Tensor SDK Beta (no JIT on Tensor; `tools/apply_plugin_main` missing in `~/.venv` today). Branded stays ORT CPU — host 15.7/4.6 vs phone ~40% (not predictive for autoregressive cloned models).

Repro: `adb shell am broadcast -a dev.triplex.TTS_BENCH --ei threads 4 --es ep cpu` (default) vs `nnapi_npu`; `adb logcat -d | grep -E "TTS-BENCH ep=|placed on"` + ORT verbose shows per-node `Node supported: [0]`.

**Other rejections (keep so you don’t re-evaluate):**
| Model | Params | Clones? | On-device path | Licence | Why kept/rejected |
|---|---|---|---|---|---|
| **Inflect-Micro-v2** | 9.4M | No (one English voice) | ONNX 37.7MB | Apache2 | **Adopted branded** — RTF 6.08 short 210ms |
| Kokoro-82M | 82M | No (54 presets) | community ONNX | Apache2 | Kept as multilingual fallback / reference |
| **Chatterbox Turbo q4f16** | 350M | Yes (~5s) | ONNX 380MB synth +177MB enc (enroll only) | MIT | **Leading cloned candidate** — 1-step distilled, beat ElevenLabs 63.75% blind |
| Audio8-TTS-Preview 0.6b | 601M | Yes zero-shot | INT4 ONNX ~572MB, 1.1-1.2GiB RAM synth | Apache2 | Candidate — preview checkpoint, needs exact transcript match |
| Qwen3-TTS 1.7B CustomVoice | 1.7B | **No** (9 presets, style control) | none | — | Rejected — name misleads, not cloning; cloning variant is 0.6B Base (server) |
| fishaudio/s2-pro | 5B | unclear | none | research-only | Rejected — server-scale + licence blocks ship |
| Qwen3-TTS 0.6B Base | 601M | Yes | none (no mobile export) | — | Server-only today — reason cloning is `REMOTE_TTS` |

---

## 5. Performance (caller-perceived)

```
transit + endpointing/ASR + reasoning/tool + TTS first audio + queues = caller-heard latency
```
Mandatory: incremental ASR + adaptive endpointing, reflex/cached acks, speculative reasoning from stable partials, overlapping ASR->reasoning->tools->streaming TTS, prewarmed models + precomputed voice reps, bounded pooled audio + monotonic timestamps.

| p95 objective | Target | Notes |
|---|---:|---|
| media callback -> VAD/ASR enqueue | 5ms | callback = acquire+memcpy+stamp+push |
| speech onset -> interruption decision | 40ms | VAD 20-40ms evidence + <1ms doorbell |
| onset -> caller-audible stop | 150ms (stretch 100ms) | pacing bound <=40ms + mixer <=10ms |
| stable ASR partial | 120ms | |
| end-of-turn delay | 80-250ms | adaptive |
| reflex -> first audio | 200ms (120) | |
| general turn -> first audio | 500ms (300) | |
| local TTS -> first PCM | 100ms | branded measured 210ms short turn |
| dropped/duplicated frames | 0 | |
| queued audio nominal | <=60ms | hard cap 80ms (8 frames) |

Proven on device: tone TTS 6x realtime, barge-in heard-state truncation, pacing holds budget by construction.

---

## 6. Consent & Security (live invariants)

- Voice profile requires **speaker_id + consent_id + tz-aware verification time + verbatim reference text + local 3–30s mono 16-bit WAV**, SHA-256 hashed for audit, 3–30s bound; `DualVoiceRouter` explicit per call, **fail-closed (never silent downgrade to branded)** — silent substitution is a consent bug. Revocation deletes `SecureStorage` ref. Stored in `services/voice-clone/profiles/<id>/profile.json+reference.wav`; prompt cached, generation serialized.
- Gateway: `X-Device-Token` (mints on `/auth/register`), `X-Admin-Key`, Plivo `verify_v3`, call-scoped ephemeral tokens, loopback-bound worker, unprivileged container, fixed model IDs/revisions, no public vLLM surfaces (text-only in-process engine), no multimodal/GGUF/speculative decoding.
- `SecureStorage` (hardware-backed encrypted prefs) holds device token, Plivo creds, voice ref; `android.speech.tts` is fixed-voice only — no cloning.
- No direct wheel URLs; model weights pinned by immutable revision. Bandit/npm/pip-audit clean on production paths. Known VEX until upgrade: `PYSEC-2026-2447` DiskCache (pickle), `PYSEC-2026-3447` Setuptools (macOS dist), `PYSEC-2025-194` Torch (jit), `PYSEC-2025-217,2026-2288/89/90` Transformers, `PYSEC-2026-2303/04/05/3403/04/05/06/08/3542` vLLM — all unreachable surfaces listed above; re-run audit on any upgrade or if arbitrary model IDs / shared caches enabled.

---

## 7. Gaps -> Next (today)

**P0 — unblock real calls:**
- Provision Plivo SIP endpoint + number + Answer/Hangup URLs.
- Wire real `SpeechModel`/`ReasonerModel` (replace stub reflex) + real `TtsModel` selection; delete `control/TurnController.kt` shadow.
- Add gateway tests (currently zero).

**P1 — cloned on-device (Phase 4):**
- Bench Chatterbox Turbo q4f16 + Audio8 INT4 on Pixel 10 CPU against gate. If pass: ship. If fail: static-bucket re-export + TFLite + LiteRT AOT (vocoder on TPU, alignment on CPU) with dispatch v2.1.6.

**P2 — harden:**
- Calibrate far-echo lag on real PSTN (synthetic `ERLE>20dB` not sufficient).
- Choose VAD `θ/k` per device tier; close thermal/network/handoff/long-call soak and p99.
- Resolve `vllm 0.23.0` vs `transformers 4.57.3` pin or isolate GPU engines.

**Open decisions (no code):** phone-unavailable policy, min Android (spike 29, product open), PJSIP vs Linphone final, first 2 outbound task schemas + inbound types, human takeover, cloud prep/backup/live-fallback perms, geography/number pool.

---

## 8. Build & Run (today)

```bash
# Android (local.properties: sdk.dir=..., gateway.url=http://10.0.2.2:8000)
cd apps/android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8000 tcp:8000

# Rust invariants
cd apps/android/native-media && cargo test
cd apps/android/agent && cargo test

# Gateway
cd gateway && docker compose up -d  # or: uvicorn app.api.main:app --reload --port 8000

# TTS bench
.venv/bin/python testlab/tts-bench/bench_onnx.py
adb shell am broadcast -a dev.triplex.TTS_BENCH --ei threads 4; adb logcat -d | grep TTS-BENCH
```

*Before building any component, find its concept in §3 (disposition: adopt/adapt/reject). If absent, add a row first. Edit this file, not `docs/` — `docs/` is frozen history.*
