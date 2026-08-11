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
| `apps/android/dialogue/` | The conversation loop, with no Android in it. | Tested on a plain JVM: `cd apps/android && ./gradlew -p dialogue test`. |
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
- **The agent holds a conversation.** Not one question and one canned reply —
  several turns, in both directions, with the phone's own language model
  deciding what to say each time. It knows what it already asked, it stops
  when it has what it needs, and it says so out loud instead of going quiet.
  The whole loop is tested as a conversation, using replies recorded from the
  model on a phone.
- **It tells the truth when it cannot answer.** If the model on the phone
  fails mid-call, a screening call asks the caller to say that again — twice
  at most — and then explains that it will pass a message on. A call the agent
  is making *for* you stops at the first failure instead of guessing, because
  guessing there means agreeing to something on your behalf.
- **Voice cloning works.** You read one sentence out loud in the app. That
  recording is both your permission and the sample we copy your voice from.
  The app then speaks a sentence you never said, in your voice. Tested with a
  real person's voice.
- **The built-in voice runs on the phone.** We measured it: about six times
  faster than real time, and a short sentence is ready in 210 milliseconds.
  It is small enough to ship inside the app, so it works with no internet.

## What does not work yet

- **Making outgoing calls.** Waiting on Plivo to turn on the accounts we need.
  The code is finished and tested: the app asks the server for permission to
  make one specific call, the server issues a one-use pass, and the phone
  dials with that pass attached. We have run that whole exchange against a
  real database. What we have never done is hear it ring, because no account
  is switched on.
- **Handing a call to the phone.** Same reason. The phone now tells the server
  whether it can carry a caller's audio, and the server sends the call
  straight to the phone when it can. Nothing has said yes yet, so every
  incoming call still gets screened by the server or told, out loud, that the
  agent is unavailable.
- **Cloning your voice without a server.** Copying a voice still needs a
  computer with a GPU. We have measured the built-in voice on the phone and it
  is fast enough; we have not yet measured the voice-cloning models. Until we
  do, that part runs on a server, and the app says so on screen.
- **Proof that the phone's model is fast enough on a call.** The agent uses
  Gemini Nano, on the phone, through AICore. We have not published a
  measurement of how long it takes to answer a turn during a live call, which
  is the number that decides whether it feels like talking to someone. The
  code measures and logs it on every turn; we have not yet run enough real
  calls to say.

The Rust part of the runtime deliberately does not think. It carries audio,
decides whose turn it is, and stops the agent mid-word when someone talks over
it — and it does that with a fixed reply and a tone generator standing in for
the models, so those tests measure timing and never accidentally measure a
language model. The real reply comes from Gemini Nano, in the Kotlin layer.

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

# The conversation loop. Needs a JDK and nothing else — no Android SDK,
# no phone, no emulator. It is its own Gradle build, so it is run with
# `-p` through the wrapper that lives one directory up.
cd apps/android && ./gradlew -p dialogue test

# The server
cd gateway && docker compose up -d

# The server's tests. The call tests drive real webhooks against a real
# database, so point them at one you do not mind being emptied.
cd gateway
docker compose up -d postgres
TEST_DATABASE_URL=postgresql+asyncpg://triplex:triplex@localhost:5432/triplex \
  pytest tests/
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

## CI

GitHub Actions runs Triplex CI on every push and pull request to `main`
(workflow: `.github/workflows/ci.yml`). Independent jobs cover the RT
allocation guard, agent-core, transport validation, gateway tests, the
conversation loop, and Android JVM unit tests; an evidence gate aggregates
the artifacts. Details live in `.github/workflows/README.md`.

CI status probe: 4 (second green verification after full gate pass).

## A rule worth knowing

We do not claim something works until we have seen it work for real: on a
phone, on a real phone call, with the timings written down. If a thing is
half-done, the code and these documents say so.
