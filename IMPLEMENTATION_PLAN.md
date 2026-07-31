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

**Call entrance**: AWS Chime SDK

**Real-time audio path** (discovered from AWS docs):
```
PSTN Call → SIP Media App (Lambda) → JoinChimeMeeting → Chime Meeting
                                                              │
                                                              ▼
                                              Media Stream Pipeline
                                                              │
                                                              ▼
                                               Kinesis Video Streams
                                                              │
                                                              ▼
                                                   Our Pipeline (VAD/LLM/TTS)
```

This is the key insight: **JoinChimeMeeting bridges PSTN calls into meetings where we get real-time audio access via Media Stream Pipelines**.

## Phase 1: Core Infrastructure

### 1.1 Transport Layer
**Goal**: Raw audio streaming with echo cancellation

**AWS Chime SDK Architecture**:
```
PSTN Call → SIP Media App (Lambda) → JoinChimeMeeting → Chime Meeting
                                                              │
                                                              ▼
                                              Media Stream Pipeline
                                                              │
                                                              ▼
                                               Kinesis Video Streams
                                                              │
                                                              ▼
                                                   Our Pipeline (VAD/LLM/TTS)
```

**Key insight**: `JoinChimeMeeting` bridges PSTN calls into meetings, enabling real-time audio access via Media Stream Pipelines to Kinesis Video Streams.

**Components**:
- Lambda handler for SIP Media Application events
- KVS consumer for audio ingestion
- Audio output back to meeting
- Echo cancellation (AEC3 or speexdsp)
- PCM chunk processing (20ms @ 16kHz)

**Files**:
```
src/triplex/transport/
├── __init__.py
├── chime_server.py     # SIP Media App Lambda handler
├── kvs_consumer.py     # Kinesis Video Streams reader
├── audio_buffer.py     # PCM chunk management, mu-law conversion
└── echo_canceller.py   # AEC integration
```

**Status**:
- ✅ `chime_server.py` - Architecture documented, action builders implemented
- ⏳ `kvs_consumer.py` - Needs implementation
- ⏳ `echo_canceller.py` - Needs implementation

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

**Status**: ✅ WORKING

**Components**:
- Kokoro-82M model
- Pre-loaded voice embedding
- Streaming audio output

**Files**:
```
src/triplex/synthesis/
├── __init__.py
├── kokoro_engine.py   # Kokoro-82M wrapper ✅
├── voice_embeddings.py # Voice management (future)
└── audio_output.py    # Stream to meeting (needs wiring)
```

**Performance** (verified):
- TTFA: ~130ms for 3-second audio
- Quality: Good with `af_heart` voice
- Streaming: Supported via generator

## Phase 2: Reasoning Engine

### 2.1 Tier 2 LLM Integration
**Goal**: Streaming text generation from local model

**Status**: ✅ vLLM engine scaffolded, needs testing with GPU

**Components**:
- vLLM server (local)
- Llama 3 8B or Qwen 2.5 (quantized)
- Streaming token output
- Tool calling for API access

**Files**:
```
src/triplex/reasoning/
├── __init__.py
├── vllm_engine.py     # vLLM integration ✅
├── stream_decoder.py  # Token → TTS pipeline (in pipeline.py)
├── tool_executor.py   # API calls (future)
└── context_manager.py # Conversation state (in pipeline.py)
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

## Architecture Reality (AWS Chime SDK)

```
┌─────────────────────────────────────────────────────────────────┐
│                         CALLER                                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      AWS CHIME SDK                              │
│  • PSTN gateway (Voice Connector)                              │
│  • SIP Media Application (Lambda)                              │
│  • JoinChimeMeeting → Meeting bridge                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 MEDIA STREAM PIPELINE                           │
│  • Audio capture from meeting                                  │
│  • Kinesis Video Streams output                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OUR PIPELINE                                 │
│                                                                 │
│  1. KVS consumer (audio in)                                    │
│  2. VAD (Silero)                                               │
│  3. LLM (vLLM + Llama 3 8B)                                    │
│  4. TTS (Kokoro-82M)                                           │
│  5. Audio back to meeting                                      │
└─────────────────────────────────────────────────────────────────┘
```

## Current Status

| Layer | Status | Notes |
|-------|--------|-------|
| Transport | ⏳ | chime_server.py scaffolded, needs KVS consumer |
| Tier 1 VAD | ✅ | Silero VAD working |
| Tier 1 Reflex | ⚠️ | Logic exists, not connected |
| Tier 2 LLM | ✅ | vLLM engine scaffolded |
| Tier 3 TTS | ✅ | Kokoro-82M working (~130ms TTFA) |
| Pipeline | ✅ | pipeline.py created, wires layers together |
| ASR | ❌ | Need Whisper integration |
| AEC | ❌ | Need echo cancellation |

## Deployment

### Prerequisites
1. AWS account with Chime SDK enabled
2. Phone number provisioned (request via AWS Support)
3. CUDA GPU instance (g4dn.xlarge or equivalent)

### Deploy Infrastructure

```bash
# 1. Package Lambda code
cd src/triplex/transport
zip -r handler.zip sip_media_app/
aws s3 cp handler.zip s3://triplex-code/handler.zip

# 2. Deploy CloudFormation stack
aws cloudformation create-stack \
  --stack-name triplex-voice-agent \
  --template-body file://aws/template.yaml \
  --parameters \
      ParameterKey=PhoneNumber,ParameterValue=+15551234567 \
      ParameterKey=CodeBucket,ParameterValue=triplex-code \
      ParameterKey=VpcId,ParameterValue=vpc-xxx \
      ParameterKey=SubnetId,ParameterValue=subnet-xxx \
  --capabilities CAPABILITY_IAM

# 3. Build and push Docker image
docker build -t triplex-processor .
docker tag triplex-processor:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/triplex-processor:latest
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/triplex-processor:latest
```

### Architecture

```
Caller → PSTN → Chime Voice Connector → SIP Media App → Lambda
                                                          │
                                                          ▼
                                                  JoinChimeMeeting
                                                          │
                                                          ▼
                                              Chime SDK Meeting
                                                          │
                                                          ▼
                                             Media Stream Pipeline
                                                          │
                                                          ▼
                                          Kinesis Video Streams (KVS)
                                                          │
                                                          ▼
                                              EC2 GPU Instance
                                              ┌─────────────────┐
                                              │ - KVS Consumer  │
                                              │ - VAD (Silero)  │
                                              │ - ASR (Whisper) │
                                              │ - LLM (vLLM)    │
                                              │ - TTS (Kokoro)  │
                                              └─────────────────┘
                                                          │
                                                          ▼
                                             Audio back to meeting
                                                          │
                                                          ▼
                                                Caller hears response
```

### Latency Budget
| Component | Target | Notes |
|-----------|--------|-------|
| Audio capture (KVS) | <20ms | Stream latency |
| VAD | <10ms | Silero on GPU |
| ASR | <100ms | Whisper small.en |
| LLM first token | <100ms | vLLM + Llama 3 8B |
| TTS first chunk | <100ms | Kokoro-82M |
| Audio output | <20ms | Meeting injection |
| **TOTAL** | **<350ms** | Target: <300ms |

## Next Steps

1. **ASR integration** (Whisper streaming) - ✅ Scaffolded
2. **Echo cancellation** (AEC) - Needs implementation
3. **KVS consumer** real-time reading - ✅ Scaffolded
4. **End-to-end test** - Deploy and call
