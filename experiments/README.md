# Experiments — read-only reference

Nothing in this directory ships. It is preserved for evidence, provenance, and
the decisions it established. See `FINAL_UNIFICATION.md` for the retirement
rationale and what was carried forward.

- `python-agent/` — the retired Python agent (pipeline, ASR, LLM, reasoning,
  interruption, VAD, reflex, Chime/KVS transport) and its tests. Superseded by
  `apps/android/agent` (Rust) and `apps/android/app` (Kotlin). Its contracts
  are being ported into `RUNTIME_INVARIANTS.md`; the code is not maintained.

Live code lives in `apps/android/` (the product), `gateway/` (cloud control),
and `services/` + `testlab/` (model serving and measurement).
