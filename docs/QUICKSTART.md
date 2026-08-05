# Triplex quick start

```bash
cd /Users/mac/triplex
python3.12 -m venv .venv
source .venv/bin/activate
pip install -e '.[dev,aws,tts,voice-clone]'
pip install faster-whisper
PYTHONPATH=src python -m pytest -q
```

Build the Chime browser-audio bridge:

```bash
npm ci --prefix web/meeting_audio_bridge
npm run build --prefix web/meeting_audio_bridge
```

Run the worker locally only when AWS event routing, real model dependencies,
and a Chrome/Chromium binary are configured:

```bash
export AWS_REGION=us-east-1
export CALL_EVENTS_QUEUE_URL='https://sqs.us-east-1.amazonaws.com/ACCOUNT/QUEUE'
export MEETING_GATEWAY_ADMIN_TOKEN='a-long-random-secret'
export HF_TOKEN='your-model-access-token'
export CHROME_EXECUTABLE='/path/to/chrome'
PYTHONPATH=src python -m triplex.runtime
```

Mocks are test-only and opt-in through `USE_MOCK_ASR=true` and
`USE_MOCK_LLM=true`. The defaults are the real faster-whisper and vLLM paths.
See [README.md](README.md) for deployment order and
[`IMPLEMENTATION_PLAN.md`](`IMPLEMENTATION_PLAN.md`) for validation status.

Cloned-voice mode is explicit and fail-closed. Set `VOICE_MODE=cloned` plus the
six consent/profile variables documented in the README and mount the local
reference WAV before starting the runtime.
