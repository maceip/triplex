#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SERIAL=${1:-}
set -- adb
if [ -n "$SERIAL" ]; then set -- "$@" -s "$SERIAL"; fi

"$ROOT/gradlew" -p "$ROOT" :app:assembleDebug
"$@" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"

DEST=/sdcard/Android/data/dev.triplex.audio8bench/files
"$@" shell mkdir -p "$DEST/models" "$DEST/fixture"
for model in \
  audio8_codec_encoder_5s.tflite \
  audio8_slow_ar_prefill256_int4.tflite \
  audio8_slow_ar_decode_int4.tflite \
  audio8_fast_ar_kv_int4.tflite \
  audio8_codec_decoder_12f.tflite
do
  "$@" push "/Users/mac/litert-benchmark-work/audio8-litert/output/$model" "$DEST/models/$model"
done
"$@" push /Users/mac/litert-benchmark-work/audio8-litert/fixture/. "$DEST/fixture/"
"$@" shell am start -n dev.triplex.audio8bench/.MainActivity
