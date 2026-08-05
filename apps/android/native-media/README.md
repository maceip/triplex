# Triplex Native Media Library

High-performance C++ audio and SIP transport layer for phone-first agent execution.

## Architecture

Per `UNIFICATION_PLAN.md`, this library provides:

- **Zero-allocation audio**: SpscRing, FramePool, bounded 10ms i16 frames
- **Native SIP transport**: PJSIP integration with direct media
- **Agent runtime**: Epoch-based cancellation, thread-safe frame processing
- **JNI bridge**: Minimal JNI footprint, no GC on RT paths

## Components

### Audio Pipeline (`audio/`)
- `frame_pool.h` - Pre-allocated frame pool (32 frames, 320 bytes each)
- `audio_pipeline.h` - Capture/playback threads with SpscRing buffers
- Configuration: 16 kHz mono i16, 10ms frame duration, 160 samples/frame

### SIP Transport (`sip/`)
- `sip_transport.h` - PJSIP endpoint wrapper with registration/call management
- Direct RTP media path to Android audio pipeline
- Secure credential handling via JNI SecureStorage bridge

### Agent Runtime (`agent/`)
- `agent_runtime.h` - Epoch-based lifecycle, interruption handling
- State machine: IDLE → LISTENING → PROCESSING_ASR → PROCESSING_REASONING → SPEAKING → INTERRUPTED
- Latency tracking for ASR, reasoning, TTS phases

## Threading Model

| Thread | Responsibility | RT Constraints |
|--------|---------------|----------------|
| Capture | Read PCM from device, push to SpscRing | 5ms budget |
| Playback | Pop from SpscRing, write to device | 10ms budget |
| Agent | ASR, reasoning, TTS generation | 500ms budget |
| JNI | State updates via StateFlow | No RT work |

## Performance Gates

| Measure | Target (p95) |
|---------|--------------|
| Frame acquisition | 5 ms |
| Frame injection | 10 ms |
| Epoch cancellation | 40 ms |
| Dropped frames | 0 |

## Build

```bash
cd apps/android
./gradlew :app:assembleDebug
```

Native library: `libtriplex-native.so` (arm64-v8a, armeabi-v7a)

## JNI Methods

### NativeRuntime
- `nativeInitialize()` → Initialize runtime, get epoch
- `nativeShutdown()` → Cleanup resources
- `nativeGetTimestamp()` → Monotonic microsecond timestamp
- `nativeGetVersion()` → Library version string

### AudioPipeline
- `nativeStartPipeline(ptr, epoch)` → Start capture/playback threads
- `nativeStopPipeline(ptr)` → Stop threads, flush buffers

### SipTransport
- `nativeInitialize()` → Initialize PJSIP
- `nativeRegister(uri, user, pass, realm)` → Register SIP endpoint
- `nativeUnregister(uri)` → Remove registration
- `nativeMakeCall(dest, epoch, callId)` → Initiate outbound call
- `nativeHangup()` → End call

### AgentBridge
- `nativeAgentInitialize()` → Load agent models
- `nativeProcessFrame(buffer, size, ts, epoch)` → Process audio frame
- `nativeInterrupt(epoch, reason)` → Cancel current epoch
- `nativeAgentShutdown()` → Unload models

## Epoch Discipline

1. Each call/activity session has a unique epoch
2. All frames tagged with epoch during processing
3. Interruption increments epoch, cancels stale work
4. JNI bridge checks epoch before committing state

## Dependencies

- NDK r26+
- C++17
- PJSIP 2.14 (to be integrated)
- No allocation on RT paths
