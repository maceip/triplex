#!/usr/bin/env bash
set -euo pipefail
ADB="${ADB:-/opt/homebrew/bin/adb}"
PACKAGE="dev.triplex.qwenbench"
ACTIVITY="dev.triplex.qwenbench/.MainActivity"
TEXT="${QWEN_BENCH_TEXT:-Hello, this is Triplex. May I ask who is calling and what this is regarding?}"

if [[ $# -eq 0 ]]; then
  echo "usage: $0 SERIAL [SERIAL ...]" >&2
  exit 2
fi
for serial in "$@"; do
  "$ADB" -s "$serial" logcat -c
  "$ADB" -s "$serial" shell am force-stop "$PACKAGE"
  "$ADB" -s "$serial" shell am start -n "$ACTIVITY"     --es text "$TEXT" --ez autorun true --ez autoplay false --ez greedy false --el seed 7
  echo "Started benchmark on $serial; result tag: QWEN_BENCH_RESULT"
done
