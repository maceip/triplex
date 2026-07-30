# Triplex v2 Implementation Plan

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         TRIPLEX v2                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    TRANSPORT LAYER                            │  │
│  │  WebRTC (UDP) + Opus @ 24kHz + AEC3                          │  │
│  │  • Raw PCM streaming (20ms chunks)                           │  │
│  │  • Echo cancellation (subtract outgoing TTS from mic)        │  │
│  │  • Jitter buffer management                                   │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │               TIER 1: REFLEX LAYER (< 100ms)                 │  │
│  │                                                               │  │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │  │
│  │  │ Silero VAD  │───▶│ Quantized   │───▶│ Decision    │      │  │
│  │  │ (20ms chunks)│    │ BERT/Intent │    │ Router      │      │  │
│  │  └─────────────┘    └─────────────┘    └─────────────┘      │  │
│  │                                               │               │  │
│  │                    ┌──────────────────────────┼───────────┐  │  │
│  │                    ▼                          ▼           ▼  │  │
│  │               BACKCHANNEL            INTERRUPTION    FILLER  │  │
│  │               (ignore)               (tear-down)    (inject) │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼ (if needs response)                  │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              TIER 2: REASONING ENGINE (vLLM)                 │  │
│  │                                                               │  │
│  │  • Llama 3 8B or Qwen 2.5 (quantized)                        │  │
│  │  • Streaming token generation                                │  │
│  │  • Tool/API calls for business logic                         │  │
│  │  • Context window management                                 │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼ (streaming tokens)                   │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              TIER 3: SYNTHESIZER (Dual Engine)               │  │
│  │                                                               │  │
│  │  PHASE 1: Kokoro-82M                                         │  │
│  │  • Decoder-only, 82M params                                  │  │
│  │  • Branded voice (offline fine-tuned embedding)             │  │
│  │  • ~50-100ms TTFA                                            │  │
│  │                                                               │  │
│  │  PHASE 2: Qwen3-TTS / CosyVoice 2                           │  │
│  │  • Zero-shot voice cloning                                   │  │
│  │  • 97-150ms streaming latency                                │  │
│  │  • Reference audio → cloned voice                            │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                              │                                      │
│                              ▼                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │              INTERRUPTION HANDLER (< 50ms)                   │  │
│  │                                                               │  │
│  │  1. Flush WebRTC jitter buffer (stop audio instantly)        │  │
│  │  2. Kill signal to LLM (halt generation)                     │  │
│  │  3. Truncate context to last spoken word                     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Deployment Context

**Target platforms**:
1. **Server (CUDA GPU)** - Business call center, full model inference
2. **Desktop/Laptop (Apple Silicon)** - Development, small business
3. **Mobile (Android/iOS)** - Consumer app, on-device inference

**Call entrance**: Twilio Voice SDK
- Uses **WebSocket (TLS)** for signaling, not pure WebRTC
- Media: DTLS-SRTP
- Need TwiML server to control call flow

## Phase 1: Core Infrastructure (Week 1)

### 1.1 Transport Layer
**Goal**: Raw audio streaming with echo cancellation

**Reality check**: Twilio Voice SDK handles the protocol layer. Our job is:
1. Receive audio from Twilio (via our server)
2. Process through our pipeline
3. Return audio to Twilio

**Components**:
- FastAPI server for TwiML webhooks
- WebSocket handler for Twilio media stream
- Echo cancellation (AEC3 or speexdsp)
- PCM chunk processing (20ms @ 24kHz)

**Files**:
```
src/triplex/transport/
├── __init__.py
├── twilio_server.py    # FastAPI + TwiML webhooks
├── media_stream.py     # WebSocket audio handling
├── audio_buffer.py     # PCM chunk management
└── echo_canceller.py   # AEC integration
```

**Twilio Flow**:
```
Caller → Twilio → Our Server (WebSocket) → Pipeline → Our Server → Twilio → Caller
                  (receive audio)           (process)   (send audio)
```

### 1.2 Tier 1 Reflex Layer
**Goal**: < 100ms intent classification and barge-in detection

**Components**:
- Silero VAD (already have code in `audio/vad.py`)
- Quantized BERT for intent classification
- Decision router for backchannel/interrupt/filler

**Files**:
```
src/triplex/reflex/
├── __init__.py
├── vad_processor.py   # Silero on 20ms chunks
├── intent_classifier.py # Quantized BERT
├── decision_router.py # Backchannel/Interrupt/Filler logic
└── filler_injector.py # Synthetic "hmm" injection
```

### 1.3 Tier 3 Synthesizer (Kokoro-82M)
**Goal**: < 100ms TTFA for branded voice

**Components**:
- Kokoro-82M model
- Pre-loaded voice embedding
- Streaming audio output

**Files**:
```
src/triplex/synthesis/
├── __init__.py
├── kokoro_engine.py   # Kokoro-82M wrapper
├── voice_embeddings.py # Voice management
└── audio_output.py    # Stream to WebRTC
```

## Phase 2: Reasoning Engine (Week 2)

### 2.1 Tier 2 LLM Integration
**Goal**: Streaming text generation from local model

**Components**:
- vLLM server (local)
- Llama 3 8B or Qwen 2.5 (quantized)
- Streaming token output
- Tool calling for API access

**Files**:
```
src/triplex/reasoning/
├── __init__.py
├── llm_server.py      # vLLM integration
├── stream_decoder.py  # Token → TTS pipeline
├── tool_executor.py   # API calls
└── context_manager.py # Conversation state
```

### 2.2 Interruption Handler
**Goal**: < 50ms tear-down cascade

**Components**:
- WebRTC buffer flush
- LLM kill signal
- Context truncation

**Files**:
```
src/triplex/interruption/
├── __init__.py
├── tear_down.py       # The cascade logic
└── state_alignment.py # Context truncation
```

## Phase 3: Voice Cloning (Week 3)

### 3.1 Zero-Shot TTS
**Goal**: Dynamic customer voice cloning

**Components**:
- Qwen3-TTS or CosyVoice 2
- Reference audio handling
- Streaming output

**Files**:
```
src/triplex/synthesis/
├── zero_shot_engine.py # Qwen3-TTS / CosyVoice
├── voice_cloner.py     # Reference audio → voice
└── dual_router.py      # Kokoro vs Zero-shot routing
```

## Dependencies

### Pip Installable
```
# Transport
aiortc>=1.6.0
opuslib>=3.0.0
webrtc-audio-processing  # if available, else speexdsp

# VAD
webrtcvad>=2.0.10

# TTS Phase 1
kokoro>=0.9.2

# TTS Phase 2 (zero-shot)
# CosyVoice or Qwen3-TTS - check availability

# LLM
vllm>=0.6.0
transformers>=4.46.0

# Audio
torch>=2.2.0
torchaudio>=2.2.0
soundfile>=0.12.0
numpy>=1.26.0
```

### System Dependencies
```
# macOS
brew install opus
brew install portaudio

# For AEC (may need to build from source)
# webrtc-audio-processing
```

## Keep vs Rebuild

### KEEP (with modifications)
| Component | Current | Modification |
|-----------|---------|--------------|
| VAD | `audio/vad.py` | Wire into reflex layer |
| Gatekeeper | `gate/gatekeeper.py` | Replace with quantized BERT, or keep as intent classifier |

### REBUILD
| Component | Why |
|-----------|-----|
| Transport | New - WebRTC/Opus/AEC |
| Reflex Layer | New architecture |
| TTS | Replace Parler-TTS with Kokoro-82M |
| LLM | Wire vLLM (currently mock) |
| Interruption | New - tear-down cascade |

### REMOVE
| Component | Why |
|-----------|-----|
| `response/tiered.py` | Replaced by reflex layer |
| `response/tts.py` | Replaced by Kokoro engine |
| `audio/synthesis.py` | Replaced |
| `agent/compliance.py` | Not needed for this use case |

## Milestone 1 Target

**Working prototype with**:
1. FastAPI server handling Twilio webhooks
2. WebSocket media stream (receive audio, send response)
3. Silero VAD detecting speech in real-time
4. Kokoro-82M generating "Hello" response (< 100ms TTFA)
5. End-to-end latency measurement

**Test method**: Call Twilio number → our server → "Hello" response

This validates the core pipeline before adding LLM.

## Implementation Order

1. **Transport** - Twilio server, media stream, echo cancellation
2. **Tier 1 Reflex** - VAD + intent classifier (keep existing gatekeeper or upgrade to BERT)
3. **Tier 3 TTS** - Kokoro-82M integration
4. **Tier 2 LLM** - vLLM + Llama 3 8B
5. **Interruption** - Tear-down cascade

## Code Migration

### Keep (with adaptation)
- `gate/gatekeeper.py` → Use as intent classifier in Tier 1, or replace with quantized BERT
- `audio/vad.py` → Wire into reflex layer

### Archive (move to `_old/`)
- `response/tiered.py` - Different architecture now
- `response/tts.py` - Replaced by Kokoro
- `audio/synthesis.py` - Replaced
- `agent/compliance.py` - Not needed

### New directories
- `transport/` - Twilio server, media stream
- `reflex/` - Tier 1 logic
- `reasoning/` - Tier 2 LLM
- `synthesis/` - Tier 3 TTS
- `interruption/` - Tear-down cascade

---

## Confirmed Requirements

| Requirement | Decision |
|-------------|----------|
| Deployment | Server (CUDA) + Desktop (Apple Silicon) + Mobile |
| Call entrance | Twilio Voice SDK (WebSocket signaling) |
| Voice cloning | Phase 1 only (MVP = branded voice) |
| LLM | Llama 3 8B with vLLM |

## Architecture Reality

Since we're using **Twilio**, we don't build WebRTC from scratch. Instead:

```
┌─────────────────────────────────────────────────────────────────┐
│                         CALLER                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         TWILIO                                  │
│  • PSTN gateway                                                 │
│  • WebSocket signaling to our server                            │
│  • Media: DTLS-SRTP                                             │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OUR SERVER (FastAPI)                         │
│                                                                 │
│  1. TwiML webhook (call control)                               │
│  2. WebSocket media stream (audio in/out)                      │
│  3. Echo cancellation                                          │
│  4. Pipeline execution                                          │
│  5. Return audio                                                │
└─────────────────────────────────────────────────────────────────┘
```

This is simpler than pure WebRTC - Twilio handles the hard parts (NAT traversal, signaling, phone network).
