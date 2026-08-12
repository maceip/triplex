#!/usr/bin/env bash
# Stage the on-device Qwen3 LiteRT + ECAPA bundle into the :qwen3_tts asset pack.
# Binaries are not committed; release/CI must run this before assembling a release AAB.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL_DIR="${QWEN_MODEL_DIR:-$HOME/.cache/triplex/qwen3-tts-0.6b-litert}"
DEST="$REPO_ROOT/apps/android/qwen3_tts/src/main/assets"

FILES=(
  "talker_int4.tflite:talker_int4.tflite"
  "mtp_folded_int8.tflite:mtp_folded_int8.tflite"
  "codec_partA.tflite:codec_partA.tflite"
  "codec_partB.tflite:codec_partB.tflite"
  "vocab.json:vocab.json"
  "merges.txt:merges.txt"
  "tables/codec_embedding_fp32.npy:codec_embedding_fp32.npy"
  "tables/mtp_embeddings_fp16.npy:mtp_embeddings_fp16.npy"
  "tables/text_embedding_fp16.npy:text_embedding_fp16.npy"
  "tables/text_projection_fp32.npz:text_projection_fp32.npz"
  "speaker_encoder.tflite:speaker_encoder.tflite"
  "mel_basis_slaney_128.npy:mel_basis_slaney_128.npy"
)

mkdir -p "$DEST"
missing=0
for pair in "${FILES[@]}"; do
  src="${pair%%:*}"
  base="${pair##*:}"
  if [[ ! -f "$MODEL_DIR/$src" ]]; then
    echo "missing $MODEL_DIR/$src" >&2
    missing=1
    continue
  fi
  echo "staging $base"
  cp -f "$MODEL_DIR/$src" "$DEST/$base"
done

if [[ "$missing" -ne 0 ]]; then
  echo "stage failed: set QWEN_MODEL_DIR to a complete LiteRT bundle" >&2
  exit 1
fi

echo "staged into $DEST"
ls -lah "$DEST"
