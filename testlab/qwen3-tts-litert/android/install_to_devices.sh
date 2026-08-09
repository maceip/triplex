#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/opt/homebrew/bin/adb}"
MODEL_DIR="${QWEN_MODEL_DIR:-$HOME/.cache/triplex/qwen3-tts-0.6b-litert}"
PACKAGE="dev.triplex.qwenbench"
ROOT="$(cd "$(dirname "$0")" && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
FILES="talker_int4.tflite mtp_folded_int8.tflite codec_partA.tflite codec_partB.tflite vocab.json merges.txt tables/codec_embedding_fp32.npy tables/mtp_embeddings_fp16.npy tables/text_embedding_fp16.npy tables/text_projection_fp32.npz voices/demo_speaker.npy"

if [[ $# -eq 0 ]]; then
  echo "usage: $0 SERIAL [SERIAL ...]" >&2
  exit 2
fi
for file in $FILES; do
  [[ -f "$MODEL_DIR/$file" ]] || { echo "missing $MODEL_DIR/$file" >&2; exit 1; }
done
[[ -f "$APK" ]] || { echo "missing APK: build first" >&2; exit 1; }

for serial in "$@"; do
  echo "Installing benchmark on $serial"
  "$ADB" -s "$serial" install -r "$APK"
  "$ADB" -s "$serial" shell run-as "$PACKAGE" mkdir -p files
  for file in $FILES; do
    base="$(basename "$file")"
    remote="/data/local/tmp/triplex_qwen3_$base"
    "$ADB" -s "$serial" push "$MODEL_DIR/$file" "$remote"
    "$ADB" -s "$serial" shell run-as "$PACKAGE" cp "$remote" "files/$base"
  done
done
