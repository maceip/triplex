# Triplex unification plan

**Status:** Revised proposal for review  
**Date:** 2026-08-02  
**Scope:** /Users/mac/triplex and /Users/mac/plivo-engine

## North Star

**The phone is the magnet, and minimum caller-perceived latency is the governing objective.**

Android runs as much of the live agent as possible: media, VAD, ASR, turn detection, reasoning, task state, cloned-voice TTS, interruption, and conversational state. Work moves to the cloud only when it must, the phone cannot meet the performance bar, the phone is unavailable, or measurement proves offload is faster.

The cloud is allowed and useful. It must not become an unnecessary media hop or a hidden default.

## Product contract

Each user receives a voice-enabled number and creates a consented voice profile. The Android app can:

- launch a bounded outbound task conducted in the user's cloned voice;
- handle permitted inbound requests to the assigned number;
- request missing information, show live state, stop, or hand over a call;
- report a truthful, evidenced outcome.

Outbound tasks and inbound policies are typed and permissioned. Triplex is not an unrestricted autonomous calling prompt.

## Locked decisions

1. Android is the preferred agent runtime, not merely a controller.
2. Direct provider-to-phone media is preferred; no Triplex cloud media relay by default.
3. Plivo is the immediate baseline. Chime remains a parallel candidate while approval proceeds.
4. Cloud inference is explicit fallback, escalation, asynchronous preparation, or measured optimization.
5. Python does not ship in the Android runtime; existing Python is reference, evaluation, and optional fallback code.
6. Latency outranks locality, cost, convenience, and model preference, subject to correctness, security, and minimum quality.
7. Best maintained off-the-shelf implementations win pairwise by default.
8. No component is called production-ready without real PSTN, device, timing, interruption, and recovery evidence.

## Target architecture

~~~mermaid
flowchart LR
    X["Caller or destination"] <-->|"PSTN"| P["Plivo now; Chime candidate"]
    P <-->|"direct SIP or WebRTC media"| A["Android agent runtime"]
    A --> L["Local VAD, ASR, reasoning, tasks, cloned TTS"]
    A --> V["Encrypted local voice and call state"]

    P -->|"webhooks and status"| G["Triplex control gateway"]
    G -->|"routing and provider API"| P
    A <-->|"auth, readiness, policy"| G
    G --> D["Accounts, numbers, task definitions, minimal audit"]

    A -.->|"explicit offload"| F["Optional cloud models or capability proxy"]
    P <-.->|"fallback only"| R["Optional media relay"]
    R <-.-> A
~~~

The gateway is required for public provider control. It is not required to carry the preferred media path.

## Placement rules

| Capability | Preferred location | Offload condition |
|---|---|---|
| Audio, VAD, interruption, DSP | Android | Only a declared remote-agent call |
| Streaming ASR | Android | Device/language/thermal failure or measured remote win |
| Reasoning and task state | Android | Explicit heavy-query escalation or measured remote win |
| Cloned-voice TTS | Android | Quality/thermal failure or measured remote win |
| Number, credentials, webhooks, routing | Cloud gateway | Required public control function |
| External tools | Narrowest safe location | Capability proxy when secrets/stable egress are required |
| Voice/model preparation | Android first; cloud job when useful | Rare heavy training, conversion, quantization, or compilation |
| Media relay | None | Direct media fails raw-audio, lifecycle, or latency gates |

Placement is visible: LOCAL, REMOTE_ASR, REMOTE_REASONING, REMOTE_TTS, or REMOTE_AGENT. The reason and latency are recorded.

For uncertain pure inference, policy may allow local and remote execution to race. The first valid result wins; the loser is cancelled. Side effects and conversation-state commits never race.

## Call paths

### Inbound, phone ready

1. Caller dials the assigned Plivo number.
2. Plivo calls the Triplex Answer URL.
3. The gateway validates the event and device policy/readiness.
4. Routing XML dials the registered Android SIP endpoint.
5. Plivo media flows directly to Android.
6. The local agent handles the conversation.

### Outbound task

1. The local task starts from the registered Android endpoint.
2. The gateway authorizes the destination and returns provider routing.
3. Plivo connects the PSTN destination to Android.
4. The local agent conducts the task.

### Phone unavailable

The user must choose cloud fallback, constrained message taking, transfer, honest unavailable response, or rejection. Cloud preparation permission, backup permission, and live cloud-agent permission are separate.

## Provider strategy

### Plivo first

Plivo SIP endpoints can register from a maintained Android SIP stack. Plivo's own Android SDK is deprecated, but its SIP endpoint service is not. Evaluate PJSIP and Linphone.

The first spike must prove:

- secure registration and Android lifecycle reachability;
- received caller PCM with transport timing;
- generated PCM injection without microphone/speaker loopback;
- DTMF, immediate flush, hangup, reconnect, and network handoff;
- acceptable encrypted-media support, latency, and audio quality.

Plivo Audio Streaming through a thin relay is the comparison/fallback path, not the assumed architecture.

### Chime in parallel

When approved, test direct PSTN-to-Chime-to-Android media, raw caller PCM, generated PCM injection, interruption, playout evidence, background recovery, and regional latency. Compare equivalent direct-to-phone paths before selecting primary or secondary provider.

## Android bedrock

- **Kotlin/Compose:** product state, lifecycle, task/policy, placement, readiness, UI, and recovery.
- **Maintained SIP/WebRTC stack:** signaling, RTP/SRTP, jitter, packet loss, codecs, and transport.
- **Small native media layer:** timestamped bounded frames, generated-audio injection, playout ledger, and cancellation.
- **On-device model runtime:** LiteRT/LiteRT-LM or another measured Android runtime.
- **Encrypted local state:** voice profile, task state, and user-authorized history.

No allocation, model loading, logging, storage, or unbounded work occurs in real-time audio callbacks. Models and voice profiles are loaded and warm before the endpoint advertises readiness.

One generation epoch crosses ASR, reasoning, TTS, and playout. Interruption increments it, cancels all stale work, flushes audio, and retains only content known or conservatively estimated to have been heard.

## Cloud boundary

Required:

- authentication and device registration;
- provider credentials, numbers, APIs, webhooks, and routing;
- device readiness and push/wakeup;
- task/policy distribution;
- minimal status/outcome audit;
- narrow capability proxy where required.

Optional:

- encrypted backup/sync;
- remote ASR, reasoning, TTS, or complete-agent fallback;
- media relay;
- asynchronous voice/model preparation.

For rare voice-cloning work, Android captures, checks, trims, and encrypts the minimum source package. A cloud job may perform expensive adaptation, conversion, quantization, or device compilation. A signed immutable artifact returns to Android for verification, preview, storage, and low-latency local synthesis.

## Build versus adopt

Prefer configuration, upstream contribution, a narrow adapter, then a minimal maintained fork. Hand-rolling is the last option.

Do not rebuild SIP, RTP, WebRTC, codecs, jitter/PLC, resampling, AEC, cryptography, secure storage, databases, model runtimes, or hardware delegates without a reproducible failure from the best maintained option and a measured custom win.

Custom Triplex work belongs in:

- latency-aware placement and safe inference racing;
- cross-stage cancellation and caller-heard state alignment;
- typed task policy, capability grants, and outcome proof;
- voice lifecycle and product interactions;
- performance and real-call test enforcement.

## Existing code and consolidation

| Source | Disposition | Canonical destination |
|---|---|---|
| Triplex Python pipeline | Preserve interfaces, tests, interruption ideas, and model comparisons; not the default runtime | experiments/python-agent and testlab |
| Triplex mobile | Preserve product concepts; rebuild corrupted client, placeholders, media, and local models | apps/android |
| Plivo engine | Preserve native/timing ideas; reject invented socket, fake NetEQ/APM, no-op audio, and buffer bugs | experiments/plivo-native-draft, then selected work into native-media or telephony-plivo |
| Chime/KVS/browser | Preserve provider knowledge; do not promote the multi-hop browser path | experiments/chime-kvs-browser and telephony-chime |

/Users/mac/triplex becomes the only active source of truth.

To absorb /Users/mac/plivo-engine:

1. Snapshot/hash its source, docs, licenses, and build state.
2. Import it with provenance under experiments/plivo-native-draft.
3. Classify every file in a disposition ledger, with evidence and reason, as adopt, adapt, rewrite, evidence-only, or reject.
4. Move proven provider work to apps/android/telephony-plivo.
5. Move proven shared frame/JNI/media work to apps/android/native-media.
6. Keep the sibling folder read-only during migration.
7. Retire it as an active location only after canonical gates and a recoverable archive; deletion requires separate approval.

Rejected code remains recoverable in the preserved snapshot; classification prevents it from becoming active production code, not from being available for later inspection.

## Performance bar

Optimize caller-perceived latency:

~~~text
provider transit
+ endpointing and ASR
+ reasoning or tool wait
+ TTS first audio
+ queues, scheduling, and provider playout
= caller-heard response latency
~~~

Mandatory techniques:

- incremental ASR and adaptive endpointing;
- reflex/cached acknowledgements;
- speculative reasoning from stable partials;
- overlapping ASR, reasoning, tools, and streaming TTS;
- prewarmed models and precomputed voice representations;
- bounded pooled audio and monotonic timestamps;
- measured direct routes and latency-based local/cloud placement.

### Initial p95 release objectives

| Measure | Objective |
|---|---:|
| Android media callback to VAD/ASR enqueue | 5 ms |
| Speech onset to interruption decision | 40 ms |
| Speech onset to caller-audible stop | 150 ms; stretch 100 ms |
| Stable ASR partial after sufficient evidence | 120 ms |
| Adaptive end-of-turn delay | 80-250 ms |
| Reflex trigger to first Android audio | 200 ms; stretch 120 ms |
| General final turn to first Android audio | 500 ms; stretch 300 ms |
| Local TTS to first usable PCM | 100 ms |
| Internal dropped/duplicated frames | 0 |
| Nominal queued audio | <= 60 ms |

Measure p50/p95/p99 on physical PSTN calls across supported phones, Wi-Fi/LTE/5G, loss/jitter/handoff, accents/noise, audio routes, process death, thermal load, and long calls. A slow baseline does not redefine success.

## Delivery sequence

1. **Preserve and govern:** inventory both trees, approve this document, and create canonical structure.
2. **Direct Plivo proof:** no AI; register Android SIP, receive caller PCM, inject a waveform, and measure real PSTN.
3. **Android media bedrock:** bounded frames, mature transport, interruption, lifecycle, replay, thermal and network tests.
4. **Neutral local agent:** streaming local ASR, turn/reflex, reasoning, TTS, and latency gates.
5. **Product control:** auth, number assignment, routing, readiness, tasks, live state, takeover, and unavailable-phone policy.
6. **Local cloned voice:** staged preparation, signed artifact, local streaming synthesis, consent and revocation.
7. **Bounded tasks:** two outbound schemas, initial inbound policy, capability limits, and outcome proof.
8. **Availability/offload:** explicit fallbacks and latency-based placement/racing.
9. **Release:** device/geography matrix, security, abuse controls, p99, soak, outage, and rollback.

Chime proceeds in parallel and reruns the same direct-media and local-agent gates when approved.

## First implementation packet

1. Preserve and fingerprint current work.
2. Create canonical phone-first directories and provenance ledger.
3. Build a minimal Android PJSIP/Linphone spike with no AI or UI redesign.
4. Create one Plivo endpoint and minimal Answer URL.
5. Prove caller PCM enters Android and generated PCM returns without physical loopback.
6. Compare real PSTN direct media with one minimal Audio Streaming relay.
7. Select the Plivo media boundary from evidence.

## Open decisions

- phone-unavailable behavior;
- minimum Android tier and OS;
- PJSIP versus Linphone;
- first two outbound tasks and inbound request types;
- human takeover;
- cloud preparation, backup, and live-fallback permissions;
- launch geography and permanent number versus staged pool.

## Current provider references

- [Plivo SIP endpoints](https://docs.plivo.com/docs/voice/concepts/sip-endpoint)
- [Plivo Endpoint API](https://docs.plivo.com/docs/voice/api/endpoints)
- [Plivo endpoint routing](https://docs.plivo.com/docs/voice/concepts/overview)
- [Plivo mobile SDK deprecation](https://docs.plivo.com/docs/voice/client/androidios/overview)
- [Plivo Audio Streaming](https://docs.plivo.com/docs/voice-agents/audio-streaming/concepts/audio-streaming-reference)
- [Amazon Chime SDK Android clients](https://docs.aws.amazon.com/chime-sdk/latest/dg/meetings-sdk.html)
