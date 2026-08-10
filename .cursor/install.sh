#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for Triplex.
#
# Prepares the components that run in a headless Linux VM without a GPU,
# an Android phone, or live telephony credentials:
#   * gateway/   FastAPI control API (Python) backed by PostgreSQL
#   * testlab/   Python transport/PSTN validation harness
#   * apps/android/{native-media,agent}  real-time Rust core (cargo test)
#
# This script must terminate and be safe to run repeatedly. It never starts a
# long-lived service; per-boot startup lives in start.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\n\033[1;34m[install]\033[0m %s\n' "$*"; }

# --- 1. System packages --------------------------------------------------
# PostgreSQL server binaries and the Python build toolchain. apt-get install
# is itself idempotent; the dpkg guard just skips the slow path on re-runs.
REQUIRED_PKGS=(postgresql postgresql-contrib python3-venv python3-dev build-essential pkg-config)
missing=()
for pkg in "${REQUIRED_PKGS[@]}"; do
  dpkg -s "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
done
if [ "${#missing[@]}" -gt 0 ]; then
  log "Installing system packages: ${missing[*]}"
  sudo DEBIAN_FRONTEND=noninteractive apt-get update
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}"
else
  log "System packages already present"
fi

# --- 2. Gateway Python environment --------------------------------------
log "Setting up gateway virtualenv"
python3 -m venv gateway/.venv
gateway/.venv/bin/pip install --upgrade pip >/dev/null
gateway/.venv/bin/pip install -r gateway/requirements.txt

# --- 3. testlab Python environment --------------------------------------
log "Setting up testlab virtualenv"
python3 -m venv testlab/.venv
testlab/.venv/bin/pip install --upgrade pip >/dev/null
testlab/.venv/bin/pip install -r testlab/requirements.txt

# --- 4. PostgreSQL cluster (user-owned, no systemd/root at runtime) ------
PG_BIN="$(dirname "$(ls /usr/lib/postgresql/*/bin/pg_ctl | sort -V | tail -1)")"
export PGDATA="$HOME/.triplex/pgdata"
PGSOCK="$HOME/.triplex/sock"
mkdir -p "$HOME/.triplex" "$PGSOCK"

if [ ! -f "$PGDATA/PG_VERSION" ]; then
  log "Initializing PostgreSQL cluster at $PGDATA"
  "$PG_BIN/initdb" -D "$PGDATA" -U triplex --auth-local=trust --auth-host=trust -E UTF8 >/dev/null
  {
    echo "listen_addresses = '127.0.0.1'"
    echo "port = 5432"
    echo "unix_socket_directories = '$PGSOCK'"
  } >> "$PGDATA/postgresql.conf"
else
  log "PostgreSQL cluster already initialized"
fi

# Create the application database. This needs a running server, so start one
# transiently when nothing is up yet, then restore the prior state: if the
# server was already running we leave it, otherwise we shut it back down so
# install leaves no stray process. start.sh owns the long-lived process.
log "Ensuring 'triplex' database exists"
started_here=0
if ! "$PG_BIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  "$PG_BIN/pg_ctl" -D "$PGDATA" -l "$HOME/.triplex/pg.log" -w start
  started_here=1
fi
if ! "$PG_BIN/psql" -h 127.0.0.1 -U triplex -d postgres -tAc \
      "SELECT 1 FROM pg_database WHERE datname='triplex'" | grep -q 1; then
  "$PG_BIN/createdb" -h 127.0.0.1 -U triplex -O triplex triplex
fi
if [ "$started_here" -eq 1 ]; then
  "$PG_BIN/pg_ctl" -D "$PGDATA" -w stop
fi

# --- 5. Warm the Rust test build ----------------------------------------
# Compiles (but does not run) the real-time core test binaries so the first
# `cargo test` in a fresh agent is fast. Non-fatal if offline.
if command -v cargo >/dev/null 2>&1; then
  log "Warming Rust test build (native-media + agent)"
  (cd apps/android/native-media && cargo test --release --no-run) || log "cargo warm skipped"
  (cd apps/android/agent && cargo test --release --no-run) || log "cargo warm skipped"
fi

log "Install complete"
