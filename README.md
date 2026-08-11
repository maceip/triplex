# Triplex

Triplex is a voice agent that runs on an Android phone. You give it a job to
do on the phone, like calling a restaurant, and it makes the call and talks
for you. It can also answer calls to your number.

The main idea: **do as much as possible on the phone itself.** The phone
handles the microphone, listening, deciding what to say, and speaking. We only
send work to a server when the phone genuinely cannot do it. This keeps the
delay short, because the person on the other end of the call is waiting for an
answer.

## Where things are

| Folder | What it is | State |
|---|---|---|
| `apps/android/` | The Android app (dialer UI, SIP, SODA, Inflect/Qwen TTS, dialogue). | Builds, installs, and runs on real phones. |
| `apps/android/dialogue/` | The conversation loop, with no Android in it. | Tested on a plain JVM: `cd apps/android && ./gradlew -p dialogue test`. |
| `apps/android/telephony-plivo/` | Plivo Direct PJSIP adapter (UDP REGISTER + authorized outbound). | Proven on device against live Plivo PSTN. |
| `gateway/` | Accounts, Plivo XML routing, one-use outbound route grants. | Running at `bridge.secure.build`. |
| `services/` | Optional remote TTS / voice-clone helpers. | GPU-backed; used when on-device clone prep is not enough. |
| `testlab/` | Measurement and validation scripts. | Never shipped to users. |
| `docs/` | Plans, decisions, and the rules the code follows. | Current. |
| `experiments/` | Retired code kept for reference. | Does not ship. |
| `notes/` | Background reading. | — |

## What works today

- **The app runs on a phone.** Installs, starts, and shows its screens. It can
  hold the Android dialer + call-screening roles so SIM ringing uses Triplex UI.
- **Outbound agent calls work on Plivo Direct.** The app asks the gateway for a
  one-use grant, dials `sip:{digits}@phone.plivo.com;transport=udp` with the
  `X-PH-TriplexGrant` header, Plivo hits `/answer`, consumes the grant, and
  places the PSTN leg. Proven on a physical Xiaomi → live DID/PSTN path with
  SIP `CONFIRMED` and billable Plivo Call API rows.
- **Direct media is on the phone.** After media goes active, SODA starts on the
  SIP audio path and the dialogue loop begins (opening speak via Inflect /
  LiteRT). Barge-in is observed on live calls.
- **Inbound routing to the device works when the phone is media-ready.** The
  gateway returns Dial-to-endpoint XML so Plivo connects the caller to the
  phone’s SIP Contact over Direct UDP — no Kamailio / host SIP edge.
- **Talking and being interrupted works.** The app can speak and, when a
  person starts talking over it, stop and keep only what the caller heard.
- **The conversation loop is real.** Several turns, both directions, with a
  local reasoner deciding what to say; truthful fallbacks when the reasoner
  cannot answer. Exercised in JVM dialogue tests and on-device smoke.
- **Voice enrollment works.** Read one consent sentence; the app can synthesize
  new lines in that voice (placement still may be remote for clone prep).
- **Built-in / on-device TTS paths exist.** Inflect (LiteRT) and Qwen3 streaming
  with smaller emit strides and codec reuse for lower first-audio latency.

## What does not work yet / known gaps

- **Gemini Nano via AICore is not available on every device.** Xiaomi smoke
  saw `FEATURE_NOT_FOUND` for Nano Full; dialogue then stops with
  `REASONER_UNAVAILABLE`. Prefer LiteRT-LM (or another on-device reasoner) on
  those phones — do not assume llama.cpp is in this stack.
- **SIM-leg audio is not agent media.** A third-party default dialer gets call
  *state* through `InCallService`, not the PCM stream. Agent listen/speak still
  requires the Plivo SIP leg (Triplex number or carrier forward into SIP).
- **Callee reachability can still fool smoke tests.** PSTN can answer at
  voicemail or another multi-device endpoint while the target Pixel never shows
  `SET_RINGING`. Dialer role on the Pixel is necessary but not sufficient.
- **Published live-call reasoner latency.** The code logs Nano/reasoner turn
  timing; we have not yet published a p95 from a clean answered conversation.
- **Fully on-device voice cloning.** Clone preparation may still need a GPU
  server; the app is honest about placement on screen.

## Telephony notes (Plivo Direct)

- Register: UDP to `sip:phone.plivo.com:5060` with Contact rewrite + keepalives.
- Outbound grants: `sip:…;transport=udp` (not `sips:5061`). TLS + optional SRTP
  in the INVITE was rejected with **488** before `/answer`.
- SDP: PCMU/PCMA + `telephone-event`, `ptime=20`, SRTP **disabled** on Direct.
- Account transport is unpinned so URI/`transport=` selects UDP vs TLS.
- Debug smoke (debug builds):  
  `adb shell am broadcast -a dev.triplex.debug.OUTBOUND_SMOKE --es destination '+1…' -n dev.triplex/.debug.DevelopmentControlReceiver`

Details: `apps/android/telephony-plivo/README.md`.

## How to build and run

```bash
# Android app. First create apps/android/local.properties with:
#   sdk.dir=/path/to/android/sdk
#   gateway.url=https://bridge.secure.build
cd apps/android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Native PJSIP / media stage (once per ABI)
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
apps/android/scripts/prepare-native.sh arm64-v8a

# Dialogue loop (JDK only)
cd apps/android && ./gradlew -p dialogue test

# Gateway
cd gateway && docker compose up -d

# Gateway tests (uses a real Postgres; point at a disposable DB)
cd gateway
docker compose up -d postgres
TEST_DATABASE_URL=postgresql+asyncpg://triplex:triplex@localhost:5432/triplex \
  pytest tests/
```

## Reading order

Start with `docs/FINAL_UNIFICATION.md`. Then:

- `docs/UNIFICATION_PLAN.md` — goals and rules we agreed not to break.
- `docs/RUNTIME_INVARIANTS.md` — phone runtime rules (interruption, epochs).
- `docs/DISPOSITION_LEDGER.md` — keep / change / retire. Read before new code.
- `docs/MODEL_REVIEW_TTS.md` / `docs/DECISION_TTS_PLACEMENT.md` — TTS choices.

## CI

GitHub Actions runs Triplex CI on every push and pull request to `main`
(workflow: `.github/workflows/ci.yml`). Independent jobs cover the RT
allocation guard, agent-core, transport validation, gateway tests, the
conversation loop, and Android JVM unit tests; an evidence gate aggregates
the artifacts. Details live in `.github/workflows/README.md`.

## A rule worth knowing

We do not claim something works until we have seen it work for real: on a
phone, on a real phone call, with the timings written down. If a thing is
half-done, the code and these documents say so.
