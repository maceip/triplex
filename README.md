# Triplex

Triplex is a voice agent that runs on an Android phone. You give it a job (for
example, call a restaurant), and it places the call and talks for you. It can
also answer calls that reach your Triplex number.

Design rule: **do as much as possible on the phone.** Listening, deciding what
to say, and speaking stay on-device when the hardware can support them. The
gateway is control-plane only (accounts, routing XML, one-use outbound grants).
It is not a media relay.

## Where things are

| Folder | What it is | State |
|---|---|---|
| `apps/android/` | Product app: dialer UI, SIP, SODA, reasoner, TTS, dialogue wiring. | Builds and runs on physical phones. |
| `apps/android/dialogue/` | Pure-Kotlin conversation loop (no Android APIs). | JVM-tested: `./gradlew -p dialogue test`. |
| `apps/android/telephony-plivo/` | Plivo Direct PJSIP adapter (UDP REGISTER + authorized outbound). | Proven on device against live Plivo PSTN. |
| `apps/android/native-media/` | Rust real-time audio path (pools, epochs, mixer). | Covered by CI allocation / agent-core jobs. |
| `apps/android/agent/` | Rust agent-core (VAD/turn/epoch helpers used via FFI). | Unit-tested in CI; not a second Python agent. |
| `gateway/` | Accounts, Plivo `/answer` XML, outbound route grants. | Deployed at `https://bridge.secure.build`. |
| `services/` | Optional GPU helpers (`voice-clone`, `voice_models`). | Legacy / fallback only — product clone path is on-device. |
| `testlab/` | Measurement and validation scripts. | Not shipped to users. |
| `docs/` | Plans, invariants, TTS decisions. | Mixed age — some still govern runtime; some describe retired Python/Chime paths. |
| `experiments/` | Retired trees (e.g. Python agent). | Reference only; does not ship. |
| `notes/` | Scratch / background reading. | Not product docs. |

Details for the SIP adapter: `apps/android/telephony-plivo/README.md`.  
Android module map: `apps/android/README.md`.

## How a call actually works

```
Phone ──UDP SIP──► Plivo Direct (phone.plivo.com:5060)
                      │
Gateway ◄── HTTPS ──┤  /answer XML, route grants, device ready/media_ready
                      │
                   PSTN / DID
```

- **Outbound agent call.** App creates a task, fetches a one-use grant, dials
  `sip:{digits}@phone.plivo.com;transport=udp` with `X-PH-TriplexGrant`. Plivo
  hits gateway `/answer`, the grant is consumed, and Plivo places the PSTN leg
  using the Triplex DID as caller ID.
- **Inbound agent call.** Someone dials the Triplex Plivo DID. Gateway `/answer`
  returns Dial-to-endpoint XML when the device is `ready` **and** `media_ready`,
  so Plivo connects to the phone’s SIP Contact over Direct UDP. There is no
  Kamailio / host SIP edge in this path.
- **Default dialer ≠ agent media.** Holding Android’s dialer / call-screening
  roles lets Triplex show UI for SIM ringing. It does **not** give the agent the
  carrier PCM stream. Listen/speak still need the Plivo SIP leg (callers use the
  Triplex DID, or the personal SIM is carrier-forwarded into that DID).
- **On the phone after media is active.** SODA transcribes the SIP audio path;
  Inflect (LiteRT) or Qwen3 speaks; Gemini Nano via AICore is the production
  reasoner when the device has it. Dialogue starts from the SIP `MEDIA_STATE`
  media-status code (not the SIP status field).

## What works today

- **App on device.** Installs and runs; can be set as default dialer and call
  screening so SIM call *state* uses Triplex UI.
- **Outbound Plivo Direct.** Grant → UDP INVITE → `/answer` → PSTN. Proven on a
  physical phone with SIP `CONFIRMED` and billable Plivo Call API rows. Earlier
  `sips:…:5061` + optional SRTP grants were rejected with **488** before
  `/answer`; current grants use plain UDP + no SRTP + `ptime=20`.
- **Direct media on the phone.** After media goes active, SODA and the dialogue
  loop start on the SIP path. Opening speak via Inflect and barge-in have been
  observed on live calls.
- **Inbound Dial-to-endpoint XML.** Implemented in the gateway for registered,
  `media_ready` devices. Requires the device to advertise readiness correctly.
- **Conversation loop contract.** Multi-turn listen/speak with interruption and
  fail-closed reasoner handling is real in `dialogue/` (JVM tests) and wired on
  device. Live reasoned turns still depend on an available on-device reasoner.
- **Voice enrollment + on-device cloned TTS.** Consent capture, ECAPA enrollment,
  and Qwen3 LiteRT synthesis all run on the phone. Release installs the ~1.6 GB
  LiteRT bundle via Play Asset Delivery (`qwen3_tts` fast-follow); debug can
  HTTP-download or `adb`-push the same files.
- **CI.** GitHub Actions on `main` / `develop`: RT allocation guard, agent-core,
  transport validation, gateway tests, dialogue tests, Android JVM/unit build,
  evidence gate. See `.github/workflows/README.md`.

## What does not work yet / known gaps

- **Gemini Nano is not on every phone.** Devices without AICore Nano Full report
  `FEATURE_NOT_FOUND`; dialogue then exits with `REASONER_UNAVAILABLE` after the
  scripted opening. LiteRT-LM (or another on-device reasoner) is the intended
  path there — this stack does **not** ship llama.cpp for calls.
- **Personal SIM → agent media needs carrier CF.** Ringing Triplex as dialer on
  a SIM call is not enough. Forward the SIM into the Triplex Plivo DID (and keep
  `media_ready` / bridge-to-phone enabled) before the agent can own audio.
- **Smoke-test traps.** PSTN can answer voicemail or another endpoint while the
  handset never shows ringing. Dialer role is necessary but not sufficient.
- **Published live reasoner latency.** Turn timings are logged; no clean answered
  multi-turn p95 is published yet.
- **Cloned-voice RTF on phone.** Packaging and enrollment are on-device; published
  RTF / first-chunk numbers for Qwen3 LiteRT on Pixel-class hardware are still
  outstanding (see `docs/DECISION_TTS_PLACEMENT.md`).
- **Stale docs under `docs/`.** Prefer `RUNTIME_INVARIANTS.md` and the TTS
  decision docs for product rules. `QUICKSTART.md` and parts of the unification
  series still describe retired Python / Chime worker paths — do not follow those
  to run today’s app.

## Telephony notes (Plivo Direct)

- Register: UDP to `sip:phone.plivo.com:5060` with Contact rewrite + keepalives.
- Outbound grants: `sip:…;transport=udp` (not `sips:5061`).
- SDP: PCMU/PCMA + `telephone-event`, `ptime=20`, SRTP **disabled**.
- Account transport is unpinned so URI/`transport=` selects UDP vs TLS.
- Debug smoke (debug APK):

```bash
adb shell am broadcast \
  -a dev.triplex.debug.OUTBOUND_SMOKE \
  --es destination '+1XXXXXXXXXX' \
  -n dev.triplex/.debug.DevelopmentControlReceiver
```

## How to build and run

```bash
# apps/android/local.properties — required:
#   sdk.dir=/path/to/android/sdk
#   gateway.url=https://bridge.secure.build

cd apps/android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Native PJSIP / media stage (once per ABI)
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
apps/android/scripts/prepare-native.sh arm64-v8a

# Dialogue loop (JDK only)
cd apps/android && ./gradlew -p dialogue test

# Gateway locally
cd gateway && docker compose up -d

# Gateway tests (disposable Postgres)
cd gateway
docker compose up -d postgres
TEST_DATABASE_URL=postgresql+asyncpg://triplex:triplex@localhost:5432/triplex \
  pytest tests/
```

## Reading order (for the shipping stack)

1. This file and `apps/android/telephony-plivo/README.md`
2. `docs/RUNTIME_INVARIANTS.md` — interruption / epoch rules
3. `docs/DECISION_TTS_PLACEMENT.md` / `docs/MODEL_REVIEW_TTS.md` — TTS placement
4. `docs/DISPOSITION_LEDGER.md` — keep / change / retire (verify against code;
   some rows lag the Direct UDP reality)
5. Unification docs (`FINAL_UNIFICATION.md`, `UNIFICATION_PLAN.md`) — historical
   north star; not a runbook for the current dialer

## A rule worth knowing

We do not claim something works until we have seen it work for real: on a phone,
on a real phone call, with timings written down. If a thing is half-done, the
code and these documents say so.
