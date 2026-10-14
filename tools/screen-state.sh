#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# screen-state.sh — sample a device's display on/off state over adb.
#
# For correlating a power measurement with what the screen was actually doing:
# some screens (and some apps) draw so little when "on" that a fixed power
# threshold mis-classifies them, so `on %` is derived from the real display
# state instead. App-agnostic: run it next to any meter recording / any app.
#
# Writes "<epoch_s>,<0|1>" lines (1 = screen on) to FILE. The report reads it
# via the CSV's `screen_log` meta and aligns by wall-clock time.
#
# Usage:
#   tools/screen-state.sh [-s SERIAL] [-o FILE] [--hz N] [--duration SEC]
#
# Stops on SIGTERM/SIGINT, or after --duration seconds (0 = until stopped).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SERIAL=""
OUT="screen-state.csv"
HZ=1
DURATION=0

usage() {
    echo "usage: tools/screen-state.sh [-s SERIAL] [-o FILE] [--hz N] [--duration SEC]"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--device) SERIAL="$2"; shift 2 ;;
        -o|--out) OUT="$2"; shift 2 ;;
        --hz) HZ="$2"; shift 2 ;;
        --duration) DURATION="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
    esac
done

if [ -z "$SERIAL" ]; then
    SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device"{if(n++){print "MULTI"; exit} print $1}')"
    if [ "$SERIAL" = "MULTI" ] || [ -z "$SERIAL" ]; then
        echo "error: pass -s SERIAL (multiple/no devices)" >&2
        exit 1
    fi
fi

d() { timeout 20 "$ADB" -s "$SERIAL" "$@" < /dev/null; }

# Sample and append until told to stop.
trap 'exit 0' TERM INT
start=$(date +%s)
: > "$OUT"
while :; do
    state="$(d shell dumpsys display 2>/dev/null | sed -n 's/.*mScreenState=\([A-Za-z]*\).*/\1/p' | head -1)"
    case "$state" in
        ON) on=1 ;;
        *)  on=0 ;;
    esac
    echo "$(date +%s),$on" >> "$OUT"
    if [ "$DURATION" -gt 0 ] && [ $(( $(date +%s) - start )) -ge "$DURATION" ]; then
        break
    fi
    sleep "$(awk "BEGIN{print 1/$HZ}")"
done
