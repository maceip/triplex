# Triplex Android

Phone-first voice agent: Jetpack Compose dialer UI, Plivo Direct SIP, on-device
listen/speak, and a pure-Kotlin dialogue loop.

This module is the product. Python under `experiments/` does not ship here.

## Gradle modules

| Module | Role |
|---|---|
| `:app` | UI, Hilt wiring, SODA, AICore reasoner, Inflect/Qwen TTS, telephony controller, enrollment. |
| `:telephony-plivo` | PJSIP Plivo Direct adapter (JNI + C++). See `telephony-plivo/README.md`. |
| `:dialogue` (included build) | Conversation loop + spoken-reply sanitizer; JVM unit tests, no Android SDK. |
| `native-media/` | Rust RT audio (built via `scripts/prepare-native.sh`, linked from the app). |
| `agent/` | Rust agent-core (FFI used by native-media / app bridge). |
| `:telemetry-client` | Telemetry helpers. |

## App package map (`app/src/main/java/dev/triplex/`)

```
data/          # local prefs, gateway HTTP, repositories, telecom helpers
dialer/        # default-dialer / InCallService integration
domain/        # call models and shared domain types
nativebridge/  # JNI / Rust bridge surfaces
speech/        # SODA, AiCore reasoner, Inflect + Qwen3 TTS
telephony/sip/ # TelephonyController — SIP events → dialogue
tts/           # additional TTS helpers
ui/
  agent/       # agent home, run list/detail, voice clone
  call/        # in-call + incoming surfaces
  enrollment/  # account / device enrollment
  keypad/      # dialer keypad
  shell/       # app chrome
  theme/       # Liquid Glass / layout tokens
  components/  # shared composables
voice/         # voice profile state
di/            # Hilt modules
```

There is no separate `agent/audio|asr|reasoning` Kotlin tree and no Linphone
module in this app. Live call audio is Plivo Direct + SODA + LiteRT TTS.

## Build requirements

- Android SDK / compileSdk **35**, targetSdk **35**, minSdk **33**
- JDK suitable for the Android Gradle Plugin in this tree
- NDK for native stage (`ANDROID_NDK_HOME`)
- `local.properties` in `apps/android/`:

```properties
sdk.dir=/path/to/android/sdk
gateway.url=https://bridge.secure.build
```

Emulator-only gateway URLs (`http://10.0.2.2:8000`) are fine for local gateway
dev; production phones should use the deployed gateway.

## Build and install

```bash
# From apps/android/
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
scripts/prepare-native.sh arm64-v8a

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Dialogue only (no device)
./gradlew -p dialogue test

# Transport validation (PJSIP module)
./gradlew :telephony-plivo:transportValidation
```

## Call path (short)

1. Device registers SIP **UDP** to `phone.plivo.com`.
2. Outbound: task → one-use grant → `sip:…;transport=udp` INVITE with grant header.
3. Media active → SODA + dialogue; speak via Inflect/Qwen into the SIP port.
4. SIM dialer role alone does **not** feed agent PCM — need the Plivo SIP leg
   (DID or carrier CF into the DID) plus `media_ready` for inbound Dial XML.

Debug outbound (debug builds):

```bash
adb shell am broadcast \
  -a dev.triplex.debug.OUTBOUND_SMOKE \
  --es destination '+1XXXXXXXXXX' \
  -n dev.triplex/.debug.DevelopmentControlReceiver
```

## Screens (what exists)

- **Shell + keypad** — dialer chrome and number entry
- **Agent home / runs** — tasks and run history
- **Enrollment** — account / device registration toward the gateway
- **Voice setup** — consent capture and clone/profile flow
- **Incoming / in-call** — Triplex surfaces for SIP and dialer call state

## Dependencies (high level)

Jetpack Compose, Hilt, Ktor (gateway client), DataStore / encrypted prefs,
Kotlin serialization, PJSIP (via `:telephony-plivo`), LiteRT TTS models,
ML Kit / AICore for Gemini Nano when present.

## Performance targets

Product latency bars live in `docs/UNIFICATION_PLAN.md` /
`docs/RUNTIME_INVARIANTS.md`. Treat published p95 from **live answered SIP
calls** as the only claim that something meets those bars; CI proves allocation
and unit contracts, not end-to-end caller delay.

## Related docs

- Repo root `README.md` — what is proven vs gaps
- `telephony-plivo/README.md` — Direct UDP / SDP / security defaults
- `docs/RUNTIME_INVARIANTS.md` — interruption and epoch rules
