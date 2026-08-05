# Decision record: TTS placement

Status: branded slot decided, cloned slot open
Date: 2026-08-05
Phase 4 of `FINAL_UNIFICATION.md`

## Decision rule, fixed before measuring

Real-time factor above 1.5 and first chunk under 300 ms **on device** means
synthesis ships on device. Below that, one-time preparation moves to the cloud
and returns a signed artifact, with synthesis still local. Only if synthesis
itself cannot run locally does anything per-utterance stay remote.

## Branded slot — DECIDED: on-device, Inflect-Micro-v2

Measured on the Pixel 10 Pro Fold (Android 17, arm64), models bundled as APK
assets so the measurement reflects how the product ships. ONNX Runtime
1.20.0, CPU execution provider, no NPU delegate.

| Turn | Audio | Synthesis | RTF (4 threads) | RTF (1 thread) |
|---|---|---|---|---|
| short (32 tokens) | 1.28 s | 210 ms | **6.08** | 3.62 |
| medium (96 tokens) | 3.43 s | 571 ms | **6.02** | 3.54 |
| long (192 tokens) | 6.72 s | 1168 ms | **5.75** | 3.48 |

**Both gates pass with large margin.** RTF is 5.75–6.08 against a threshold of
1.5, and the short turn synthesizes in 210 ms against a 300 ms first-chunk
budget. Even pinned to a **single core** the model holds RTF 3.48, so the
decision does not depend on thread availability, and no NPU delegate was
needed to reach it.

Model size is 37.7 MB (`decode.onnx` 30.4 MB, `duration.onnx` 7.3 MB), which
ships inside the APK. No download, no CDN, works offline on first launch.
Apache 2.0.

Host reference for comparison (Apple Silicon, ONNX Runtime CPU): RTF 15.7 on
four threads, 4.6 on one. The phone lands at roughly 40 % of host throughput,
which is the useful ratio to carry into the cloned-slot estimate.

### Consequences

- The branded voice is **LOCAL**, always, on every device. It never depends on
  the network and never bills GPU.
- Kokoro remains the multilingual fallback. It stays in `services/voice_models`
  for server use and as the reference the retired `DualVoiceRouter` contract
  was validated against; it is not the shipped branded engine.
- `RUNTIME_INVARIANTS.md` §7.6 still binds: engine selection is explicit per
  call and a missing cloned profile fails closed rather than silently
  substituting this branded voice.

## Cloned slot — OPEN

Not yet measured. Candidates, both with official ONNX exports and streaming:

| Candidate | Synthesis components (quantized) | Licence | Note |
|---|---|---|---|
| Chatterbox Turbo q4f16 | ~380 MB resident, 177 MB encoder used only at enrollment | MIT | Distilled 1-step decoder; beat ElevenLabs 63.75 % in blind preference |
| Audio8 INT4 | ~572 MB online files, 1.1–1.2 GiB RAM at synthesis, 1.55 GiB at registration | Apache 2.0 | Demonstrated on an M2 CPU; preview checkpoint |

The branded result is encouraging for this slot but does not settle it: these
models are 35–60× larger in parameter count and autoregressive, so the 40 %
host-to-phone ratio is a starting estimate, not a prediction.

Per Phase 1, per-word alignment marks are **desirable but no longer required**
— `TtsHost` emits a chunk-end mark unconditionally, so an engine without
trustworthy word timestamps degrades to whole-segment retention rather than
breaking heard-state.

Preparation placement follows the same rule. On-device enrollment is preferred;
if it does not fit, one-time cloud preparation returning a signed artifact is
the fallback, and a desktop companion app is not warranted for a single
ten-second operation.

## Reproducing

```bash
# Host reference
.venv/bin/python testlab/tts-bench/bench_onnx.py

# On device (models ship as APK assets)
adb shell am broadcast -a dev.triplex.TTS_BENCH --ei threads 4
adb logcat -d | grep TTS-BENCH
```

Harness: `apps/android/app/src/main/java/dev/triplex/tts/TtsBenchmark.kt`.
