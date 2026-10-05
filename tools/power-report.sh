#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# power-report.sh — build a power report from recorded runs, diff it against the
# committed baseline, and optionally promote it to become the new baseline.
#
# The report is a markdown table (plus a .txt copy) with one row per run; rows
# are matched by label, so diffing two reports shows exactly what changed.
#
# Examples:
#   tools/power-report.sh                       # report over power-runs/*.csv
#   tools/power-report.sh 'power-runs/*nano*'   # only nano runs
#   tools/power-report.sh --promote             # accept the report as official

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

GLOB="power-runs/condensed/*.csv"
BASELINE="tools/power-baseline.md"
OUT_MD="power-runs/latest.md"
TITLE="local run set"
PROMOTE=false

usage() {
    echo "usage: tools/power-report.sh [GLOB] [--baseline FILE] [--out FILE] [--title T] [--promote]"
    echo
    echo "  GLOB           CSV glob/dir of runs (default: $GLOB)"
    echo "  --baseline F   baseline report to diff against (default: $BASELINE)"
    echo "  --out F        report output path (default: $OUT_MD; a .txt is written too)"
    echo "  --title T      report title"
    echo "  --promote      replace the baseline with this report"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --baseline) BASELINE="$2"; shift 2 ;;
        --out) OUT_MD="$2"; shift 2 ;;
        --title) TITLE="$2"; shift 2 ;;
        --promote) PROMOTE=true; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "unknown option: $1" >&2; usage; exit 1 ;;
        *) GLOB="$1"; shift ;;
    esac
done

MERGE_ARGS=()
[ -f "$BASELINE" ] && MERGE_ARGS=(--merge "$BASELINE")
python3 "$SCRIPT_DIR/avhzy-monitor.py" report "$GLOB" -o "$OUT_MD" --title "$TITLE" \
    ${MERGE_ARGS[@]+"${MERGE_ARGS[@]}"} >/dev/null
echo "report:  $OUT_MD (and ${OUT_MD%.md}.txt)"

if [ -f "$BASELINE" ]; then
    echo
    echo "=== diff vs baseline ($BASELINE) ==="
    python3 "$SCRIPT_DIR/avhzy-monitor.py" diff "$BASELINE" "$OUT_MD"
else
    echo "no baseline at $BASELINE to compare against."
fi

if [ "$PROMOTE" = true ]; then
    python3 "$SCRIPT_DIR/avhzy-monitor.py" promote "$OUT_MD" "$BASELINE"
fi
