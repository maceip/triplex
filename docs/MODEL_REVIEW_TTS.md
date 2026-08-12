# Speech model review

Status: review complete; branded Inflect on-device; cloned Qwen3 LiteRT on-device (RTF unpublished)
Date: 2026-08-11
Feeds: `DECISION_TTS_PLACEMENT.md`

Records every speech model we evaluated, including the ones we rejected and
why. Written after the fact: the review was done on 2026-08-04 but only the
adopted model reached a document, so the rejections lived only in
conversation. That is the failure this file exists to prevent.

## The two slots

Engine choice is per call and explicit (`RUNTIME_INVARIANTS.md` §7.6):

- **Branded** — the built-in assistant voice. Everyone gets it. Must run on
  the phone with no network.
- **Cloned** — the user's own voice, made from a short consented recording.

## Candidates reviewed

| Model | Params | Clones a voice? | On-device path | Licence | Verdict |
|---|---|---|---|---|---|
| **Inflect-Micro-v2** | **9.4M** | No — one fixed English voice | ONNX, **37.7 MB total** | Apache 2.0 | **Adopted, branded slot.** Measured on a Pixel 10 Pro Fold: RTF 6.08, 210 ms short turn. Ships inside the APK. |
| **Kokoro-82M** | 82M | No — 54 preset voices | community ONNX | Apache 2.0 | Kept. Incumbent branded engine from the earlier Python work; retained for multilingual and as the reference the dual-router contract was validated against. |
| **Audio8-TTS-Preview-0.6b** | 601M | **Yes, zero-shot** | **Official INT4 ONNX**, ~572 MB files, streaming PCM, 1.1–1.2 GiB RAM at synthesis on an M2 CPU | Apache 2.0 | **Leading cloned-slot candidate. Not yet benchmarked on a phone.** Preview checkpoint. Requires the reference transcript to match the audio exactly, which our consent-sentence flow already guarantees. |
| **Chatterbox Turbo** (found separately) | **350M** | **Yes, zero-shot from ~5 s** | **Official ONNX q4f16**: ~380 MB synthesis + 177 MB encoder used only at enrollment | MIT | **Smallest true cloning candidate. Not yet benchmarked.** Decoder distilled from 10 steps to 1. Original Chatterbox beat ElevenLabs 63.75 % in blind preference. |
| **Qwen3-TTS-12Hz-1.7B-CustomVoice** | 1.7B | **No** | none | — | **Rejected.** The name misleads: "CustomVoice" is 9 preset timbres with instruction-based style control, not cloning. The cloning variant is the 0.6B Base model we already use server-side. |
| **fishaudio/s2-pro** | **5B** | unclear | none | **Research only — commercial use needs a separate licence** | **Rejected.** Server-scale even before the licence, and the licence alone disqualifies it for a shipped product. |
| **Qwen3-TTS-12Hz-0.6B-Base** | 601M | Yes, zero-shot | **LiteRT on device** (`talker_int4` + folded MTP + split codec + ECAPA TFLite) | — | **Adopted for cloned slot (on-device).** Ships via PAD fast-follow / debug download. Phone RTF gate still outstanding. |

## The finding that matters

**None of the small models can clone a voice, and none of the cloning models
is small.** Among everything reviewed:

- The genuinely tiny models — Inflect-Micro-v2 at 9.4M and Kokoro at 82M —
  have **fixed voices**. They cannot copy a person.
- Every model that can clone starts at **350M parameters** and goes up.

So the hope of a tiny cloning model is not available in the current
literature. The realistic floor is **Chatterbox Turbo at 350M**, whose
quantized synthesis components total about 380 MB.

That is not a reason for pessimism. 380 MB resident on a 16 GB phone is
plausible; it simply has to be measured rather than assumed.

## Why cloning used to run on a server

The earlier Python path had no mobile export. That is no longer true for the
product app: Android now runs a LiteRT host-orchestrated decode loop plus an
on-device ECAPA speaker encoder. Cloud `services/voice-clone` remains as a
legacy helper, not the enrollment path.

## What has and has not been measured

| Claim | Evidence |
|---|---|
| Branded voice runs on the phone | **Measured.** RTF 6.08 at 4 threads, 3.48 at 1 thread, 210 ms short turn, Pixel 10 Pro Fold, models as APK assets. |
| Cloning runs on the phone | **Integrated.** Enrollment + synthesis use on-device LiteRT. **RTF / first-chunk not yet published** against the placement gate. |

The branded result does not transfer: Inflect is 9.4M and feedforward, while
the cloning stack is ~0.6B and autoregressive, generating token by token. The
cost profiles are different in kind, not degree.

## Outstanding work

Publish Qwen3 LiteRT RTF and first-chunk numbers on the Pixel against the rule
already fixed in `DECISION_TTS_PLACEMENT.md`: **real-time factor above 1.5 and
first chunk under 300 ms means cloning ships on the phone.** Chatterbox Turbo
and Audio8 remain alternate candidates if Qwen3 misses the gate.
