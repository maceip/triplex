#!/usr/bin/env bash
# Supervisor for the voice-clone service.
#
# A reference recording the model cannot converge on wedges a generation
# thread that cannot be cancelled, so the service answers 504 and exits 75.
# Restarting is the only way to release the model lock; profiles persist on
# disk and their clone prompts are rebuilt lazily.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${TRIPLEX_VOICE_PORT:-8801}"

cd "$REPO"
while true; do
    "$REPO/.venv/bin/python" -m uvicorn service:app \
        --host 0.0.0.0 --port "$PORT" --app-dir services/voice-clone
    status=$?
    if [[ $status -eq 0 ]]; then
        echo "voice service exited cleanly"
        break
    fi
    echo "voice service exited ($status); restarting in 2s"
    sleep 2
done
