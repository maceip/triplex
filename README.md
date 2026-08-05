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
| `apps/android/` | The Android app. This is the only thing users see. | Builds, installs, and runs on a real phone. |
| `gateway/` | The server that handles accounts, phone numbers, and call routing. | Running at `bridge.secure.build`. Answers real calls. |
| `services/` | Servers that run the text-to-speech models. | Works, but needs a computer with a good GPU. |
| `testlab/` | Scripts we use to measure things and check our work. | Never shipped to users. |
| `docs/` | Plans, decisions, and the rules the code follows. | Current. |
| `experiments/` | Old code we no longer use, kept so we can look things up. | Does not ship. Not maintained. |
| `notes/` | Background reading. | — |

## What works today

- **The app runs on a phone.** Installs, starts, and shows its screens.
- **Answering calls works.** We called our number from a phone and the call
  reached our server, which answered and spoke a message. Right now it says
  the assistant is not available, because we cannot yet hand the call to the
  phone. That part is waiting on Plivo.
- **Talking and being interrupted works.** The app can speak and, when a
  person starts talking over it, stop almost immediately and remember only the
  words the caller actually heard. Tested on a real phone.
- **Voice cloning works.** You read one sentence out loud in the app. That
  recording is both your permission and the sample we copy your voice from.
  The app then speaks a sentence you never said, in your voice. Tested with a
  real person's voice.
- **The built-in voice runs on the phone.** We measured it: about six times
  faster than real time, and a short sentence is ready in 210 milliseconds.
  It is small enough to ship inside the app, so it works with no internet.

## What does not work yet

- **Making outgoing calls.** Waiting on Plivo to turn on the accounts we need.
- **Handing a call to the phone.** Same reason. Until then, incoming calls get
  a spoken message instead of the agent.
- **Cloning your voice without a server.** Copying a voice still needs a
  computer with a GPU. We have measured the built-in voice on the phone and it
  is fast enough; we have not yet measured the voice-cloning models. Until we
  do, that part runs on a server, and the app says so on screen.
- **The thinking part.** The app currently uses a simple stand-in that always
  gives the same reply. Real understanding and answering is not wired in yet.

## How to build and run

```bash
# Android app. First create apps/android/local.properties with:
#   sdk.dir=/path/to/android/sdk
#   gateway.url=http://127.0.0.1:8000
cd apps/android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# If the phone is not on the same Wi-Fi as your computer, connect it
# to the server over the USB cable:
adb reverse tcp:8000 tcp:8000

# The parts of the runtime written in Rust
cd apps/android/native-media && cargo test
cd apps/android/agent && cargo test

# The server
cd gateway && docker compose up -d
```

## Reading order

Start with `docs/FINAL_UNIFICATION.md`. It says what is live, what we stopped
using, and why. Then:

- `docs/UNIFICATION_PLAN.md` — the goals and the rules we agreed not to break.
- `docs/RUNTIME_INVARIANTS.md` — the detailed rules the phone code follows,
  such as how it stops speaking when interrupted.
- `docs/DISPOSITION_LEDGER.md` — every part of the code and whether we keep
  it, change it, or have retired it. **Read this before writing new code**, so
  you do not rebuild something that already exists.
- `docs/MODEL_REVIEW_TTS.md` — every speech model we looked at, including the
  ones we turned down and why.
- `docs/DECISION_TTS_PLACEMENT.md` — the speech measurements and what we chose.

## A rule worth knowing

We do not claim something works until we have seen it work for real: on a
phone, on a real phone call, with the timings written down. If a thing is
half-done, the code and these documents say so.
