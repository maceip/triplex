# Triplex Android App

Phone-first agent runtime implementing local inference, VAD, ASR, turn detection, reasoning, task state, TTS, and interruption.

## Architecture

Per `UNIFICATION_PLAN.md`, the Android app runs as much of the agent as possible:

- **Local Agent Management** (agent/): VAD, ASR, reasoning, TTS modules
- **Telephony** (telephony/): SIP/WebRTC stack, provider adapters
- **Control** (control/): Device readiness, task state machine
- **Native Media** (native-media/): Frame processing, JNI audio injection

## Project Structure

```
app/src/main/java/dev/triplex/
├── agent/              # Local agent runtime
│   ├── audio/          # Audio capture and playout
│   ├── asr/            # Streaming ASR
│   ├── reasoning/      # Local inference
│   ├── tts/            # Cloned voice synthesis
│   └── task/           # Task execution engine
├── control/            # Device state management
├── data/               # Repository pattern
│   ├── local/          # Encrypted storage
│   ├── remote/         # Gateway API client
│   └── repository/     # Data abstraction
├── domain/             # Business logic
│   ├── model/          # Domain entities
│   └── usecase/        # Business rules
├── telephony/          # Provider integration
│   ├── sip/            # PJSIP/Linphone adapter
│   └── media/          # Direct media handling
├── ui/                 # Jetpack Compose UI
│   ├── screens/        # Screen composables
│   ├── components/     # Reusable components
│   ├── theme/          # Material theme
│   └── navigation/     # Navigation graph
└── di/                 # Dependency injection
```

## Build Requirements

- Android SDK 35
- Kotlin 2.0.21
- Gradle 8.10.2
- Spike build floor: API 29 (Android 10); the product minimum remains an open decision
- Target SDK 35

## Quick Start

### 1. Set Gateway URL

Create `local.properties`:
```properties
gateway.url=http://10.0.2.2:8000
```

For physical devices, use your machine's IP:
```properties
gateway.url=http://192.168.1.100:8000
```

### 2. Build and Install

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Or use Android Studio: Run > Run 'app'

## Dependencies

| Library | Purpose |
|---------|---------|
| Jetpack Compose | Declarative UI |
| Hilt | Dependency injection |
| Ktor | HTTP client |
| DataStore | Preference storage |
| Security-Crypto | Encrypted preferences |
| Kotlinx Serialization | JSON parsing |

## Screens

### Enrollment
- Register user account
- Generate and store device token
- Set device ready status

### Dashboard
- View agent status
- Active task card with Stop button
- Task history list
- Create new task FAB

### Create Task
- Select task type (appointment modification, reservation update)
- Enter task parameters
- Specify destination number

## Placement Tracking

All operations log placement (`LOCAL`, `REMOTE_*`) with latency. Check `LatencyTracker` for p50/p95 metrics.

The first direct Plivo/PJSIP and native-media spike lives in
`telephony-plivo/` and `native-media/`. It remains evidence-gated pending real
PSTN calls on physical Android devices; local agent modules are not yet wired.

## Performance Targets

Per `UNIFICATION_PLAN.md`, target p95 latencies:

| Measure | Target |
|---------|--------|
| Media callback to VAD/ASR enqueue | 5 ms |
| Speech onset to interruption decision | 40 ms |
| Speech onset to caller stop | 150 ms |
| Stable ASR partial | 120 ms |
| Reflex trigger to first audio | 200 ms |
| General turn to first audio | 500 ms |
| Local TTS first PCM | 100 ms |
| Queued audio | ≤ 60 ms |

## Security

- Device tokens stored in encrypted preferences
- Voice profiles encrypted with hardware-backed keys
- Clear-text traffic only for local development
- ProGuard enabled for release builds
