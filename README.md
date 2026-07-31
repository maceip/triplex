# Triplex

Triplex is a production-oriented, full-duplex voice-agent prototype for Amazon
Chime SDK PSTN calls. The four implementation-plan features are present and
covered by deterministic regression tests. A live PSTN/CUDA validation run is
still a release gate, not a completed claim.

## Runtime path

```text
PSTN caller
  -> Chime SIP media application / Lambda / JoinChimeMeeting
  -> Chime media stream pipeline
  -> Kinesis Video Streams (Matroska AAC)
  -> AEC -> VAD -> faster-whisper -> vLLM -> Kokoro or Qwen3-TTS
  -> authenticated local gateway / Chime SDK JS attendee
  -> custom MediaStream audio input -> caller
```

The call worker consumes EventBridge lifecycle events through SQS. Every caller
gets isolated conversation, cancellation, AEC, and playout-alignment state.
Production mode does not silently substitute mock ASR, reasoning, transport, or
voice-cloning behavior.

## Implemented plan items

- Echo cancellation: streaming block-NLMS with a far-end FIFO, configurable
  delay, double-talk adaptation freeze, ERLE metrics, and reset/flush behavior.
- Live Chime/KVS integration: incremental Matroska parsing, authentic AAC decode,
  continuation-token reconnects, call-scoped EventBridge/SQS admission, meeting
  attendee creation, authenticated browser egress, and AudioWorklet playout
  acknowledgements.
- Barge-in: ingress remains live during generation; local output, browser
  playout, and the call-scoped vLLM request are torn down concurrently under a
  50 ms deadline. Only fully acknowledged synthesizer text segments enter
  conversation history.
- Dynamic voice cloning: consent-gated local WAV profiles, reference hashing,
  cached Qwen3-TTS prompts, and explicit per-call branded/cloned routing. A
  clone request fails closed if that engine/profile is unavailable.

See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for the implementation and
release-gate record.

## Local verification

Python 3.11 or 3.12 is required.

```bash
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev,aws,tts,voice-clone]'
pip install faster-whisper
PYTHONPATH=src python -m pytest -q
PYTHONPATH=src python -m ruff check \
  src/triplex/runtime.py src/triplex/pipeline.py \
  src/triplex/reasoning/vllm_engine.py src/triplex/interruption \
  src/triplex/synthesis src/triplex/transport tests scripts
npm ci --prefix web/meeting_audio_bridge
npm run build --prefix web/meeting_audio_bridge
npm audit --prefix web/meeting_audio_bridge
```

The generated browser bundle is packaged under
`triplex/transport/meeting_gateway_static` and is included in the Python wheel.
The processor image installs the reviewed Python 3.12 Linux resolution from
`requirements-linux-py312.lock`; see [SECURITY.md](SECURITY.md) for the current
dependency advisory assessment and model-supply-chain boundaries.

## AWS deployment order

The Chime KVS pool, SIP media application, and SIP rule are API-only resources;
the provisioning utility manages them idempotently instead of pretending they
are native CloudFormation types.

```bash
# 1. Create or discover the KVS pool.
python scripts/provision_chime.py --region us-east-1

# 2. Package/upload Lambda, then deploy aws/template.yaml with its KvsPoolArn.
python scripts/package_sip_lambda.py build/sip-handler.zip

# 3. Attach the deployed Lambda to an already-provisioned Chime phone number.
python scripts/provision_chime.py \
  --region us-east-1 \
  --lambda-arn "$SIP_HANDLER_ARN" \
  --phone-number "$CHIME_PHONE_NUMBER"

# 4. Build/push the CUDA image and deploy aws/processor-template.yaml using
#    the queue outputs from the core stack and an existing runtime secret.
```

The processor AMI must already provide Docker, AWS CLI, `jq`, the NVIDIA
container runtime, and a driver capable of CUDA 13. The Secrets Manager value
must be JSON with `MEETING_GATEWAY_ADMIN_TOKEN` and `HF_TOKEN`.

The worker defaults to `VOICE_MODE=branded`. To activate the real clone route,
set `VOICE_MODE=cloned` and provide `VOICE_CLONE_PROFILE_ID`,
`VOICE_CLONE_SPEAKER_ID`, `VOICE_CLONE_REFERENCE_AUDIO`,
`VOICE_CLONE_REFERENCE_TEXT`, `VOICE_CLONE_CONSENT_ID`, and an ISO-8601
`VOICE_CLONE_CONSENT_VERIFIED_AT`. The reference path must be a mounted local
16-bit mono PCM WAV; missing or invalid authority data fails startup.

## Release truth

Local tests, lint, browser build/audit, wheel-content inspection, and both
CloudFormation schemas pass. This checkout cannot truthfully claim the live
release gates: the configured AWS account currently has no Chime PSTN number or
KVS pool, the local host has no CUDA device, and its Docker daemon is stopped.
Those are explicit prerequisites for the live-call, real-vLLM, image-build, and
sub-300 ms end-to-end measurements.
