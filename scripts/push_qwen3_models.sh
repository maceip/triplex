#!/usr/bin/env bash
# Push the on-device Qwen3-TTS LiteRT + ECAPA bundle into the Triplex app filesDir.
set -euo pipefail
ADB="${ADB:-adb}"
SERIAL="${1:?usage: $0 SERIAL}"
MODEL_DIR="${QWEN_MODEL_DIR:-$HOME/.cache/triplex/qwen3-tts-0.6b-litert}"
PACKAGE="dev.triplex"
REMOTE_DIR="files/models/qwen3-tts"

FILES=(
  talker_int4.tflite
  mtp_folded_int8.tflite
  codec_partA.tflite
  codec_partB.tflite
  vocab.json
  merges.txt
  tables/codec_embedding_fp32.npy
  tables/mtp_embeddings_fp16.npy
  tables/text_embedding_fp16.npy
  tables/text_projection_fp32.npz
  speaker_encoder.tflite
  mel_basis_slaney_128.npy
)

for file in "${FILES[@]}"; do
  [[ -f "$MODEL_DIR/$file" ]] || { echo "missing $MODEL_DIR/$file" >&2; exit 1; }
done

echo "Ensuring package data dir exists on $SERIAL"
"$ADB" -s "$SERIAL" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell run-as "$PACKAGE" mkdir -p "$REMOTE_DIR"

for file in "${FILES[@]}"; do
  base="$(basename "$file")"
  tmp="/data/local/tmp/triplex_qwen3_$base"
  echo "pushing $base"
  "$ADB" -s "$SERIAL" push "$MODEL_DIR/$file" "$tmp"
  "$ADB" -s "$SERIAL" shell run-as "$PACKAGE" cp "$tmp" "$REMOTE_DIR/$base"
  "$ADB" -s "$SERIAL" shell rm -f "$tmp"
done

"$ADB" -s "$SERIAL" shell run-as "$PACKAGE" ls -la "$REMOTE_DIR"
echo "done"
