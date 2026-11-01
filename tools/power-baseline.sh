#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# power-baseline.sh — regenerate docs/power/baseline.{md,txt,html} from the
# recorded runs, deterministically. Run it when no measurement is in progress
# (a half-written CSV is skipped, but its prefix windows could otherwise skew a
# batch).
#
# The table is a pure function of the CSVs: same inputs -> same table. The only
# randomness in the whole pipeline is the device (charging/USB/screen state).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

OUT="docs/power/baseline.md"
mkdir -p "$(dirname "$OUT")"
# Built from the committed inputs under docs/power/data/ (the power CSVs plus the
# screen-state sidecars they reference), so the table is reproducible by anyone.
python3 tools/avhzy-monitor.py report 'docs/power/data/*.csv' \
    -o "$OUT" --title "official baseline" >/dev/null
echo "regenerated $OUT (+ ${OUT%.md}.txt, ${OUT%.md}.html)"
