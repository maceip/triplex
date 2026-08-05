# Triplex

A phone-first voice agent. The Android app is the product; the phone runs as
much of the live agent as possible, and work moves to the cloud only when it
must.

Read `UNIFICATION_PLAN.md` for the north star and locked decisions,
`FINAL_UNIFICATION.md` for what is live and what was retired, and
`DISPOSITION_LEDGER.md` before building anything.

## What is alive

| Directory | What it is |
|---|---|
| `apps/android/` | **The product.** Kotlin app, Rust runtime (`native-media`, `agent`), PJSIP telephony. The only user-facing surface. |
| `gateway/` | Cloud control plane: auth, numbers, credentials, task policy, Plivo webhooks. Deployed at `bridge.secure.build`. Never carries media. |
| `services/` | Model serving (`voice_models`, `voice-clone`). No conversation logic. |
| `testlab/` | Evaluation, measurement, and validation gates. Never ships. |

## What is not

| Directory | Status |
|---|---|
| `experiments/` | Read-only reference. Retired Python agent, Chime/KVS and AWS work. Does not ship, is not maintained, is not a fallback. |
| `mobile/` | Rejected legacy worktree. Separate git repo, preserved in place. |
| `notes/` | Background reading. |

## Build and run

```bash
# Android app (needs local.properties with sdk.dir and gateway.url)
cd apps/android && ./gradlew :app:assembleDebug

# Rust runtime tests
cd apps/android/native-media && cargo test
cd apps/android/agent && cargo test

# Gateway (production compose lives on the host)
cd gateway && docker compose up -d
```

The governing objective is minimum caller-perceived latency. Placement is
always explicit and recorded: LOCAL, REMOTE_ASR, REMOTE_REASONING,
REMOTE_TTS, or REMOTE_AGENT.
