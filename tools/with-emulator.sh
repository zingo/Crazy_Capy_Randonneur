#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEEP="${CAPY_EMU_KEEP:-0}"

cleanup() {
    if [ "$KEEP" != "1" ]; then
        "$ROOT/tools/stop-dev.sh" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT INT TERM

"$ROOT/tools/ensure-emulator.sh" || exit 1
"$@"
