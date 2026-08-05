# Voice-clone prototype (host-side, isolated)

Honest scope: local Mac proofs of the pinned Qwen3-TTS zero-shot cloning
pipeline (`src/triplex/synthesis/qwen3_tts_engine.py`). Not the Android
runtime, not a live call, no signed-artifact lifecycle yet.

## Smoke run (no human voice; already executed)

```
.venv/bin/python testlab/voice-clone-demo/smoke_run.py
```

Generates a synthetic Kokoro reference with fully known text, registers it
through the consent-gated engine, and synthesizes a never-before-spoken line
via real Qwen inference. Evidence in `artifacts/smoke/` (reference, output,
manifest with sha256s, model revision, timings). First proven run:
2026-08-03, 6.56 s of cloned audio in 6.2 s on MPS.

Listen: `afplay testlab/voice-clone-demo/artifacts/smoke/reference_kokoro_af_heart.wav`
then `afplay testlab/voice-clone-demo/artifacts/smoke/cloned_output.wav`.

## Local consented voice-clone prototype (your voice; interactive)

```
.venv/bin/python testlab/voice-clone-demo/record_and_clone.py [novel text...]
```

Records you speaking the consent statement (~10 s) — the recording is both
the auditable consent and the cloning reference — then synthesizes a line
you never said and plays reference + clone back-to-back. Artifacts under
`artifacts/consented/<timestamp>/`; delete the directory to revoke. Nothing
leaves the machine. First run triggers the macOS mic-permission prompt for
your terminal.

## Reference-quality investigation (2026-08-04)

A reference captured through the phone microphone made Qwen generate without
converging. Ten controlled trials (`diagnose_reference.py`, `diagnose_round2.py`,
90 s cap each, one subprocess per trial) tested one factor at a time against
the clean reference:

| Factor | Result |
|---|---|
| mismatched reference text | ok, 8.1 s |
| quiet (mic's level, ~10 dB down) | ok, 2.3 s |
| additive noise (mic's 25 dB SNR) | ok, 2.9 s |
| room reverberation | ok, 2.6 s |
| band-limited 200–6000 Hz | ok, 3.1 s |
| **real mic capture** (raw / normalized / fully conditioned) | **non-convergent, all three** |

No synthetic degradation reproduces the failure, and conditioning does not
repair it — so denoise/AGC/normalization is *not* a fix for this. Something
specific to the real device capture path (plausibly non-stationary noise or
the device's own spectral gating) puts the reference outside what the speaker
encoder handles, and the root cause is still open.

What ships instead is empirical verification: the service synthesizes a short
line before it will call a profile ready (45 s cap) and rejects the recording
with guidance if that fails. Bad reference → HTTP 422 in ~48 s; good reference
→ ready in ~5 s.

## Next stages toward the locked requirement

Android capture/preparation, encrypted+signed voice artifacts, on-device
synthesis behind the `TtsModel` seam in `apps/android/agent`, then cloned
PCM across the PJSIP route with interruption evidence.
