#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# Debug-build cache/render energy matrix at an EQUAL operation rate, screen
# on/off, with idle baselines and rotated order. Samples are spread over the
# whole route. Produces power-runs/bench/bench-matrix.csv (git-ignored), which
# tools/bench-plot.py aggregates into docs/power/cache-bench.html.
#   cold = all caches cleared, live render
#   warm = tiles prefilled, live render
#   full = tiles + previews prefilled, cached decode+draw
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"; DEVICE=""; HOURS=8; MAXC=0
OPS=400; RATE=10; IDLE_SEC=60
ROUTE="$HERE/routes/ystad-onnekoppinge-harlosa.gpx"
OUT="$HERE/../power-runs/bench"
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--device) DEVICE="$2"; shift 2 ;;
    -r|--route) ROUTE="$2"; shift 2 ;;
    -H|--hours) HOURS="$2"; shift 2 ;;
    -M|--max-cycles) MAXC="$2"; shift 2 ;;
    -o|--out) OUT="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -n "$DEVICE" ] || DEVICE="$("$ADB" devices | awk 'NR>1&&$2=="device"{print $1; exit}')"
[ -n "$DEVICE" ] || { echo "no device" >&2; exit 1; }
PKG=com.crazycapy.randonneur; REMOTE=/data/local/tmp/bench-route.gpx
mkdir -p "$OUT"; CSV="$OUT/bench-matrix.csv"
[ -f "$CSV" ] || echo "ts,cycle,variant,mode,screen,rate,ops,cpu_ms,dur_s,avg_w,wh,on_pct,level" > "$CSV"
adb() { "$ADB" -s "$DEVICE" "$@"; }
CUTOFF=$(( $(date +%s) + HOURS*3600 ))
wait_marker() { local f="$1" t=0 v; while [ $t -lt 1800 ]; do v=$(adb shell run-as "$PKG" cat "files/$f" 2>/dev/null | tr -d '\r' || true); [ -n "$v" ] && { echo "$v"; return 0; }; sleep 2; t=$((t+2)); done; return 1; }
screen_on() { adb shell svc power stayon true >/dev/null 2>&1 || true; adb shell input keyevent 224 >/dev/null 2>&1 || true; adb shell wm dismiss-keyguard >/dev/null 2>&1 || true; sleep 3; }
screen_off() { adb shell svc power stayon false >/dev/null 2>&1 || true; local w; for _ in 1 2 3 4 5; do adb shell input keyevent 223 >/dev/null 2>&1 || true; sleep 1; w=$(adb shell dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1); [ "$w" = "Asleep" ] && return 0; done; }
clear_all() { adb shell run-as "$PKG" rm -rf cache files/cache files/mbgl-offline.db files/prefill.done files/bench.done >/dev/null 2>&1 || true; }
prefill() { adb shell run-as "$PKG" rm -f files/prefill.done >/dev/null 2>&1 || true; adb shell am broadcast -a "$PKG".debug.PREFILL -n "$PKG"/.debug.DebugControlReceiver --es route "$REMOTE" --ez darkMap false --ez tilesOnly "$1" >/dev/null; wait_marker prefill.done >/dev/null || true; }
parse_dir() { python3 - "$1" <<'PY'
import csv,glob,sys
n=sw=on=wh=dur=0.0
for f in glob.glob(sys.argv[1]+"/*.csv"):
    for r in csv.DictReader([l for l in open(f) if not l.startswith('#')]):
        if 'sum_w' not in r: continue
        try:
            n+=float(r['n']); sw+=float(r['sum_w']); on+=float(r['on_n']); wh+=float(r['wh']); dur+=float(r['chunk_ms'])/1000.0
        except: pass
print(f"{sw/n if n else 0:.4f} {wh:.5f} {100*on/n if n else 0:.1f} {dur:.1f}")
PY
}
measure() {
  local variant="$1" mode="$2" screen="$3" ops="$4" rate="$5" tmp chk wh on dur level mark cpu
  if [ "$screen" = on ]; then screen_on; else screen_off; fi
  tmp=$(mktemp -d)
  python3 "$HERE/avhzy-monitor.py" record --label "mx-$variant" -d 3600 -q -o "$tmp" >"$tmp/out" 2>&1 &
  local rec=$!
  sleep 2
  adb shell run-as "$PKG" rm -f files/bench.done >/dev/null 2>&1 || true
  adb shell am broadcast -a "$PKG".debug.BENCH -n "$PKG"/.debug.DebugControlReceiver --es route "$REMOTE" --ez darkMap false --es benchMode "$mode" --ei iterations "$ops" --ei targetRps "$rate" --el benchSleepMs "$([ "$mode" = idle ] && echo 1000 || echo 0)" >/dev/null 2>&1 || true
  mark=$(wait_marker bench.done || true)
  kill -TERM "$rec" 2>/dev/null || true; wait "$rec" 2>/dev/null || true
  set -- ${mark:-x 0 0 0 0 x}; ops=${2:-0}; cpu=${4:-0}
  read -r chk wh on dur < <(parse_dir "$tmp")
  level=$(adb shell dumpsys battery 2>/dev/null | awk -F: '/level/{gsub(/ /,"",$2);print $2;exit}')
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$(date '+%Y-%m-%dT%H:%M:%S')" "$CYCLE" "$variant" "$mode" "$screen" "$rate" "$ops" "$cpu" "$dur" "$chk" "$wh" "$on" "$level" >> "$CSV"
  echo "  $variant screen=$screen ops=$ops -> ${chk}W ${wh}Wh cpu=${cpu}ms"
  rm -rf "$tmp"
}
adb push "$ROUTE" "$REMOTE" >/dev/null
adb shell am start -n "$PKG"/.MainActivity >/dev/null 2>&1 || true; sleep 2
CYCLE=0
while [ "$(date +%s)" -lt "$CUTOFF" ]; do
  CYCLE=$((CYCLE+1)); echo "=== cycle $CYCLE $(date '+%H:%M:%S') ==="
  [ "$MAXC" -gt 0 ] && [ "$CYCLE" -gt "$MAXC" ] && break
  for scr in on off; do measure idle idle "$scr" "$IDLE_SEC" 0; done
  case $((CYCLE % 3)) in 1) order="cold warm full" ;; 2) order="warm full cold" ;; 0) order="full cold warm" ;; esac
  for v in $order; do
    [ "$(date +%s)" -ge "$CUTOFF" ] && break 2
    case "$v" in cold) clear_all; mode=live ;; warm) clear_all; prefill true; mode=live ;; full) clear_all; prefill false; mode=cached ;; esac
    for scr in on off; do
      [ "$(date +%s)" -ge "$CUTOFF" ] && break 2
      measure "$v" "$mode" "$scr" "$OPS" "$RATE"
    done
  done
done
adb shell am broadcast -a "$PKG".debug.STOP -n "$PKG"/.debug.DebugControlReceiver >/dev/null 2>&1 || true
echo "ALL DONE $(date '+%H:%M:%S')"
