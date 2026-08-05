# Triplex implementation and release plan

Last implementation pass: 2026-07-31

## Objective

Deliver the four requested capabilities without mocked production behavior:
echo cancellation, real Chime/KVS media, sub-50 ms interruption teardown, and
dynamic zero-shot voice cloning. Keep live infrastructure/model validation as
explicit release gates so code completion is never confused with deployment
truth.

## Deployed architecture

```text
PSTN
  -> Chime SIP rule
  -> SIP media application Lambda
       create meeting + caller attendee + IndividualAudio media pipeline
       return JoinChimeMeeting
  -> Chime meeting
       caller audio -> Chime-managed KVS pool
       agent audio <- Chime SDK JS custom MediaStream attendee
  -> EventBridge start/end -> encrypted SQS + DLQ
  -> GPU call processor
       KVS MKV/AAC -> decode -> AEC -> VAD -> ASR -> vLLM -> dual TTS
       PCM -> authenticated local WebSocket -> AudioWorklet -> Chime meeting
```

One `VoicePipeline` is allocated per call. Model weights are shared where safe;
vLLM cancellation state is call-scoped so one caller's barge-in cannot abort
another caller's request.

## Item 1: echo cancellation — implemented

Files:

- `src/triplex/transport/echo_canceller.py`
- `src/triplex/pipeline.py`
- `tests/test_echo_canceller.py`

Contract:

- Signed 16-bit mono PCM in and out at 20 ms boundaries.
- Outgoing audio enters a thread-safe far-end FIFO only after transport commit.
- A normalized adaptive filter learns delayed/multipath correlated echo.
- Near-end double-talk freezes adaptation instead of suppressing the caller.
- Interruption flushes stale reference audio; a call reset clears coefficients.
- ERLE, input/reference/residual power, and adaptation state are observable.

Evidence:

- The synthetic delayed multipath regression requires greater than 20 dB ERLE.
- FIFO pairing and double-talk protection have dedicated tests.
- A local 200-frame run measured 0.906 ms median, 0.995 ms p95, and 1.404 ms
  maximum processing time per 20 ms frame.

Release tuning:

- `echo_delay_ms` exists because PSTN/meeting round-trip delay must be calibrated
  from a real call; the synthetic test does not establish the production value.

## Item 2: live Chime/KVS media — implemented

Files:

- `src/triplex/transport/mkv.py`
- `src/triplex/transport/kvs_consumer.py`
- `src/triplex/transport/sip_media_app/handler.py`
- `src/triplex/transport/call_processor.py`
- `src/triplex/transport/meeting_gateway.py`
- `src/triplex/transport/chime_meeting.py`
- `web/meeting_audio_bridge/`
- `aws/template.yaml`
- `aws/processor-template.yaml`
- `scripts/provision_chime.py`

Ingress guarantees:

- `GetMedia` is treated as persistent Matroska, never raw PCM.
- The incremental EBML parser tolerates arbitrary network splits and repeated
  KVS fragment headers, captures AAC codec configuration, handles supported
  lacing, and captures continuation tokens.
- AAC access units are decoded through FFmpeg/PyAV, downmixed to mono, and
  resampled before entering the fixed 16 kHz/20 ms PCM pipeline.
- Audio is downmixed/resampled/rechunked to fixed 16 kHz/20 ms PCM frames.
- Recoverable GetMedia failures reconnect with bounded exponential backoff.

Call orchestration guarantees:

- Only `caller-*` IndividualAudio lifecycle events are admitted; the agent's
  own KVS stream is ignored.
- Starts are idempotent, ends cancel their exact task, and SQS is deleted only
  after Chime egress admission succeeds. Malformed/failed admissions reach the
  DLQ through normal redelivery.
- Each output attendee is created through the Chime meetings API.

Egress guarantees:

- Python sends framed PCM through an authenticated, ephemeral gateway session.
- A pinned Amazon Chime SDK JS client joins as an attendee and supplies an
  `AudioWorklet`-backed custom `MediaStream` as its audio input.
- The worklet reports cumulative samples actually played and returns the exact
  PCM boundary when a generation is flushed.
- Gateway credentials and producer/browser tokens are scoped per call; Chrome
  sandbox disabling is opt-in rather than the default.

Deployment guarantees:

- The core and processor CloudFormation templates pass AWS validation and
  `cfn-lint` with no findings.
- Chime resources absent from CloudFormation are provisioned idempotently via
  their documented APIs.
- Event routing uses encrypted SQS, a DLQ, retries, and least-scope runtime
  roles. Runtime model/gateway secrets come from Secrets Manager.
- SIP join failures and hangups clean up both the media pipeline and meeting.

## Item 3: interruption teardown — implemented

Files:

- `src/triplex/interruption/tear_down.py`
- `src/triplex/interruption/state_alignment.py`
- `src/triplex/pipeline.py`
- `src/triplex/reasoning/vllm_engine.py`
- `web/meeting_audio_bridge/src/pcm-worklet.js`
- `tests/test_interruption.py`

Contract:

- Ingress/VAD continues while LLM and TTS generation are active.
- Confirmed caller speech cancels the current generation task.
- Local queued PCM, the exact call-scoped vLLM request, and browser playout are
  flushed concurrently under a 50 ms deadline.
- The browser flush acknowledgement is an exact played-sample boundary.
- Only fully played synthesizer segments are retained. Partial segments are
  conservatively omitted because neither Kokoro nor the high-level Qwen clone
  API supplies trustworthy word timestamps.
- No generated-but-unplayed assistant text survives in conversation history.

Evidence:

- The regression requires model abort, one transport flush, retained text only
  from the fully played segment, and measured teardown below 50 ms.
- A 200-trigger local coordinator run measured 0.049 ms median, 0.064 ms p95,
  0.087 ms p99, and 0.111 ms maximum before real network/browser latency.

## Item 4: dynamic zero-shot voice cloning — implemented

Files:

- `src/triplex/synthesis/qwen3_tts_engine.py`
- `src/triplex/synthesis/dual_router.py`
- `tests/test_voice_cloning.py`

Contract:

- The later engine is Qwen3-TTS Base, loaded only when clone mode is configured.
- CUDA uses FlashAttention 2 when that optional kernel is installed and the
  model's authentic manual PyTorch attention path otherwise.
- A clone profile requires a speaker ID, consent ID, timezone-aware verification
  time, verbatim reference text, and a local 16-bit mono PCM WAV.
- Reference duration is constrained to 3–30 seconds and the file is SHA-256
  hashed for audit records.
- Model-specific clone prompts are cached and generation is serialized around
  the shared model instance.
- The router selects branded Kokoro or a named clone per call. Missing clone
  dependencies/profiles fail closed; they never fall back to Kokoro silently.
- The default call worker loads clone mode only when `VOICE_MODE=cloned` and all
  required local reference and consent metadata pass validation at startup.
- Since Qwen's high-level clone API returns audio without word timestamps,
  interrupted partial clone output is conservatively excluded from history.

Evidence:

- Tests cover consent rejection, reference validation/hash/prompt caching,
  generated PCM chunking, explicit routing, and no-fallback behavior using an
  injected model object rather than a fake production branch.

## Validation record

Completed in this checkout:

- Python: 24 tests pass.
- Ruff: all implementation/test/deployment Python files pass.
- CloudFormation: both templates pass AWS `validate-template` and `cfn-lint`.
- Browser bridge: TypeScript bundle succeeds; production and full npm audits
  report zero vulnerabilities.
- Linux/CUDA dependency resolution succeeds for Python 3.12; the CUDA 13.0.2
  base image manifest exists for both amd64 and arm64.
- The Linux dependency graph is locked for the processor image; model IDs and
  immutable model revisions are pinned. Current upstream advisory exceptions
  and their unreachable-surface controls are recorded in `SECURITY.md`.
- The installed Qwen3-TTS 0.1.1 API matches the adapter; its real 0.6B Base
  model initialized on local MPS, cached a 4.55-second Kokoro reference prompt,
  and generated 61,440 finite samples at 24 kHz through the clone path.
- Packaging: the wheel contains the runtime, call worker, and all three gateway
  static assets; the SIP Lambda zip contains only its two required modules.
- AEC: synthetic ERLE exceeds 20 dB and local p95 DSP time is below 1 ms.
- Interruption: deterministic regression is below the 50 ms deadline.

Release gates still requiring external prerequisites:

1. Provision a Chime SDK PSTN phone number and ACTIVE KVS pool, deploy both
   stacks, and complete a real caller-audio ingress plus agent-audio egress call.
2. Build/run the image on supported NVIDIA hardware and exercise the real vLLM
   path. The local Apple Silicon host has no CUDA device and its Docker daemon
   is currently unavailable.
3. On that live path, collect p50/p95/p99 for KVS capture, ASR, first vLLM token,
   first audio, interruption, and end-to-end caller-heard latency. The original
   sub-300 ms target remains a target until those measurements exist.
4. Calibrate PSTN echo delay and rerun ERLE/double-talk measurements on captured,
   consented test calls.

The configured AWS account was inspected read-only: it currently has no Chime
phone numbers, SIP media applications, KVS pools/streams, or GPU instances. No
live-call or CUDA result should be inferred from the local passing suite.
