#!/usr/bin/env bash
# Per-boot service reconciliation for Triplex. Runs on every environment start,
# tolerates restarts, avoids duplicate processes, and returns promptly.
set -euo pipefail

log() { printf '\n\033[1;32m[start]\033[0m %s\n' "$*"; }

PG_BIN="$(dirname "$(ls /usr/lib/postgresql/*/bin/pg_ctl | sort -V | tail -1)")"
export PGDATA="$HOME/.triplex/pgdata"

if [ ! -f "$PGDATA/PG_VERSION" ]; then
  echo "[start] PostgreSQL cluster missing at $PGDATA; run .cursor/install.sh first" >&2
  exit 1
fi

# A snapshot can preserve a stale postmaster.pid whose process is gone. Clear it
# so pg_ctl does not refuse to start.
if [ -f "$PGDATA/postmaster.pid" ] && ! "$PG_BIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  log "Removing stale postmaster.pid"
  rm -f "$PGDATA/postmaster.pid"
fi

if "$PG_BIN/pg_ctl" -D "$PGDATA" status >/dev/null 2>&1; then
  log "PostgreSQL already running"
else
  log "Starting PostgreSQL"
  "$PG_BIN/pg_ctl" -D "$PGDATA" -l "$HOME/.triplex/pg.log" -w start
fi

# Readiness check so downstream terminals can rely on the database.
if "$PG_BIN/pg_isready" -h 127.0.0.1 -p 5432 -U triplex >/dev/null 2>&1; then
  log "PostgreSQL is accepting connections on 127.0.0.1:5432"
else
  echo "[start] PostgreSQL did not become ready" >&2
  tail -n 20 "$HOME/.triplex/pg.log" >&2 || true
  exit 1
fi
