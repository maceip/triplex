# Triplex v2

```
╔══════════════════════════════════════════════════════════════╗
║  ⚠  FOR RED TEAMS ONLY — NOT TO BE USED IN PRODUCTION  ⚠  ║
╚══════════════════════════════════════════════════════════════╝
```

Production-grade voice agent with sub-300ms conversational latency.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         CALLER                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         TWILIO                                  │
│  • PSTN gateway, WebSocket signaling                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    TIER 1: REFLEX LAYER                         │
│                         (< 100ms)                               │
│                                                                 │
│  Silero VAD → Intent Check → Backchannel/Interrupt/Filler      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (if needs response)
┌─────────────────────────────────────────────────────────────────┐
│                    TIER 2: REASONING                            │
│                         (vLLM + Llama 3 8B)                     │
│                                                                 │
│  Streaming text generation, tool calls, context management     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼ (streaming tokens)
┌─────────────────────────────────────────────────────────────────┐
│                    TIER 3: SYNTHESIS                            │
│                                                                 │
│  Phase 1: Kokoro-82M (82M params, ~130ms TTFA)                 │
│  Phase 2: Qwen3-TTS / CosyVoice 2 (zero-shot cloning)          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    INTERRUPTION                                 │
│                         (< 50ms)                                │
│                                                                 │
│  Flush buffer → Kill LLM → Truncate context                    │
└─────────────────────────────────────────────────────────────────┘
```

## Latency Targets

| Component | Target | Status |
|-----------|--------|--------|
| Tier 1 Reflex | < 100ms | ✅ VAD works |
| Tier 2 Reasoning | 1-2s | ⏳ Need vLLM |
| Tier 3 Synthesis | < 150ms | ✅ Kokoro works |
| Interruption | < 50ms | ⏳ Wire up |
| **E2E** | **< 300ms TTFA** | In progress |

## Quick Start

```bash
cd /Users/mac/triplex

# Use Python 3.12 (3.14 not compatible with some packages)
source .venv/bin/activate

# Install
pip install torch torchaudio onnxruntime numpy
pip install fastapi uvicorn websockets scipy
pip install "kokoro>=0.9.2" soundfile
pip install transformers accelerate

# Test TTS
PYTHONPATH=src python -c "
from triplex.synthesis.kokoro_engine import KokoroEngine
engine = KokoroEngine()
audio, sr = engine.synthesize('Hello, how can I help?')
print(f'Generated {len(audio)} samples at {sr}Hz')
"
```

## Project Structure

```
src/triplex/
├── transport/          # Twilio server, audio streaming
│   ├── twilio_server.py
│   └── audio_buffer.py
├── reflex/             # Tier 1 decision layer
│   └── engine.py
├── reasoning/          # Tier 2 LLM (TODO)
├── synthesis/          # Tier 3 TTS
│   └── kokoro_engine.py
├── interruption/       # Tear-down cascade (TODO)
├── gate/               # Audio gatekeeper (from v1)
└── audio/              # VAD, frontend (from v1)
```

## Status

**Working:**
- ✅ Kokoro-82M TTS (~0.4s for 3s audio)
- ✅ Silero VAD (from v1)
- ✅ Audio format conversion (mu-law ↔ PCM, resampling)
- ✅ Reflex layer logic

**In Progress:**
- ⏳ Twilio server (needs TwiML/websocket wiring)
- ⏳ vLLM + Llama 3 8B
- ⏳ Tear-down cascade

**Not Started:**
- Zero-shot voice cloning (Phase 2)
- Mobile deployment

## Key Differences from v1

| v1 | v2 |
|----|----|
| Parler-TTS (~3-6s) | Kokoro-82M (~0.4s) |
| Mock LLM | vLLM + Llama 3 8B |
| No phone integration | Twilio Voice SDK |
| Simple tiered responses | Reflex layer with barge-in |
