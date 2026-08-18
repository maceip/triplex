# Journal

_generated 2026-08-18 09:05 UTC · 6 live entries (1 decisions · 3 findings · 1 questions · 1 intents) · 6 total in history_

## DECIDED

- `e01KZCPWDR508JDJATJYXPJNX5X` Compile TTS graphs with LiteRT AOT directly, not through LiteRT-LM — 11d · active

## LEARNED

- `e01KZCPWDR5P45T9ZDFG8EREP2D` Voice pipeline speaks on-device, but ASR and reasoning are placeholders — 11d · current
- `e01KZCPWDR508JDJATJYVB0X4Z9` ONNX Runtime cannot reach the Pixel Tensor TPU; LiteRT is the only path — 11d · current
- `e01KZ9GS8PCHJ5PDCGD62EXAR8N` No small TTS model clones; realistic cloning floor is ~350M params — 12d · suspect

## OPEN

- `e01KZCPWDR5HCA32H7R8YQH36A2` Sequence the five linked models before or after closing ASR/reasoner gaps? — 11d · open ★

## ALERTS

- suspect `e01KZ9GS8PCHJ5PDCGD62EXAR8N` No small TTS model clones; realistic cloning floor is ~350M params — 12d
- ★ human question aging `e01KZCPWDR5HCA32H7R8YQH36A2` Sequence the five linked models before or after closing ASR/reasoner gaps? — 11d

## Intent × reality

| Intent | Age | Reality | State |
|---|---:|---:|---|
| `e01KZ9GY8S9CYCGF15GXXCV1KR4` Implement all linked Hugging Face TTS models on-device only | 12d | 33 evidence | in_flight |

## Decisions

### e01KZCPWDR508JDJATJYXPJNX5X — Compile TTS graphs with LiteRT AOT directly, not through LiteRT-LM  `active`
> We compile the model graphs with LiteRT AOT directly, which sidesteps the packaging layer and its 8-bit size penalty entirely.

LiteRT-LM is the LLM packaging layer (.litertlm, text-in/text-out) and carries an 8-bit size penalty; TTS needs codec tokens out to a vocoder, which that layer does not express. Decision: use LiteRT AOT compilation on the model graphs directly. Note the earlier claim that Gemma 3n's audio support helps was wrong-directioned — Gemma takes audio in, TTS emits codec tokens out.

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 · confidence: 0.86 · tags: apps/android/**, testlab/litert-aot/** · evidence: 33_

## Findings

### e01KZCPWDR5P45T9ZDFG8EREP2D — Voice pipeline speaks on-device, but ASR and reasoning are placeholders  `current`
> ASR is a placeholder that detects sound but not words, and the reasoner always replies with one fixed sentence.

On the Pixel 10 Pro Fold the full loop runs: injected caller audio, VAD, turn FSM, Supertonic synthesis with flow matching (12.1 ms/step, 27x CPU) and vocoder on the TPU, paced egress; pulled output transcribes back as the intended sentence and barge-in interrupts mid-utterance. Duration and text-encoder stages stay on CPU pending Google bug LiteRT#9152. The agent still cannot understand words or…

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 · confidence: 0.88 · tags: apps/android/agent/**, apps/android/native-media/** · evidence: 1_

### e01KZCPWDR508JDJATJYVB0X4Z9 — ONNX Runtime cannot reach the Pixel Tensor TPU; LiteRT is the only path  `current`
> NNAPI *found* the TPU — `Manager: Found interface google-edgetpu (version = 2.0)` — then claimed not one op. Forcing `CPU_DISABLED` changed nothing, confirming it's a partitioning result, not a scheduling preference.

Measured on the Pixel 10 Pro Fold (Tensor G5): ONNX Runtime's Android providers are only CPU, NNAPI and XNNPACK. In a four-provider test NNAPI detected the EdgeTPU but offloaded 0 of 886 nodes (unchanged with CPU_DISABLED); RTF stayed ~5 across all providers. Causes: dynamic shapes and int64 tensors. Only LiteRT ships the Google Tensor dispatch bridge, so TPU access requires LiteRT.

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 · confidence: 0.90 · tags: apps/android/**, testlab/litert-aot/**, docs/**_

### e01KZ9GS8PCHJ5PDCGD62EXAR8N — No small TTS model clones; realistic cloning floor is ~350M params  `suspect`
> None of the small models can clone, and none of the cloning models is small.

Review of the five linked HF models: the tiny models (Inflect-Micro-v2 9.4M, Kokoro-82M) are small because they bake in fixed voices; Qwen CustomVoice (1.7B) has presets only despite its name. Only Chatterbox Turbo (350M, MIT) and Audio8 (601M) clone; fishaudio/s2-pro (5B) is research-licence only. Cloning runs on a server today because the integrated Qwen3-TTS has no mobile export — on-device wa…

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2331 · confidence: 0.90 · tags: docs/MODEL_REVIEW_TTS.md, docs/DECISION_TTS_PLACEMENT.md, apps/android/** · evidence: 2_

## Open questions

### e01KZCPWDR5HCA32H7R8YQH36A2 — Sequence the five linked models before or after closing ASR/reasoner gaps?  `open`
> The honest sequencing question is whether to push your five through that pipeline now, or first close the two gaps that make the agent not-an-agent

Open: whether to push the five linked HF models through the proven ONNX -> static bucket -> tflite -> AOT -> TPU pipeline next, or first replace the placeholder ASR and the fixed-sentence reasoner. Assistant recommends Audio8 first (only linked model that clones, official INT4 export) but left the ordering to the user. Unanswered in this slice.

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2481 · confidence: 0.85 · tags: apps/android/**_

## Intents

### e01KZ9GY8S9CYCGF15GXXCV1KR4 — Implement all linked Hugging Face TTS models on-device only  `in_flight`
> for all the hugging face models/projects i linked, set them up inside of our android app, faithfully implementinging them to the best of your ability. on-device only.

Standing user requirement: integrate every Hugging Face model/project the user linked into the Android app, faithfully, on-device only; anything that would otherwise need a server GPU must run through LiteRT with the phone's TPU/GPU. Currently unmet — the shipping voice is Supertonic, which came from the sibling ~/neural project and is not one of the linked models.

_source: session claude-code:/Users/mac/.claude/projects/-Users-mac-triplex/7e975131-5649-4463-9ca6-2e3c256af6a2.jsonl#L2334 · confidence: 0.95 · tags: apps/android/**, docs/MODEL_REVIEW_TTS.md · evidence: 33_
