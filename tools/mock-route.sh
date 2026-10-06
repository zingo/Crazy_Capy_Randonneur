#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# mock-route.sh — play a GPX route as a mock GPS location over adb, so *any*
# app on the device (not just this project's) sees it riding the route. Used to
# power-test a third-party navigation app: start that app and its navigation,
# then run this while recording power with tools/avhzy-monitor.py.
#
# No root needed: it grants the adb shell the mock-location appop, registers a
# test location provider, and feeds the interpolated route at a fixed rate.
#
# Examples:
#   tools/mock-route.sh --speed 20 --hz 1                 # whole default route
#   tools/mock-route.sh -r loop.gpx -s 25 --hz 2 -d 600   # 10 min at 2 Hz
#   tools/mock-route.sh --dry-run                         # print the plan only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-adb}"
PROVIDER="gps"
ROUTE="$SCRIPT_DIR/routes/ystad-onnekoppinge-harlosa.gpx"
SPEED=20
HZ=1
START_KM=0
ACCURACY=5
DURATION=""
DEVICE=""
KEEP=false
DRY_RUN=false

usage() {
    sed -n '5,15p' "$0" | sed 's/^# \{0,1\}//'
    cat <<EOF

Options:
  -r, --route FILE     GPX route (default: tools/routes/ystad-onnekoppinge-harlosa.gpx)
  -s, --speed KMH      playback speed (default $SPEED)
      --hz N           location updates per second (default $HZ)
      --start-km N     start this far along the route (default $START_KM)
  -d, --duration SEC   stop after this long (default: the whole remaining route)
  -a, --accuracy M     reported accuracy in metres (default $ACCURACY)
      --device SERIAL  adb device serial (default: the only attached device)
      --keep           leave the test provider registered on exit
      --dry-run        print the plan (and the first points) without touching adb
  -h, --help           show this help
EOF
}

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        -r|--route) ROUTE="$2"; shift 2 ;;
        -s|--speed) SPEED="$2"; shift 2 ;;
        --hz) HZ="$2"; shift 2 ;;
        --start-km) START_KM="$2"; shift 2 ;;
        -d|--duration) DURATION="$2"; shift 2 ;;
        -a|--accuracy) ACCURACY="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --keep) KEEP=true; shift ;;
        --dry-run) DRY_RUN=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

[ -f "$ROUTE" ] || die "route not found: $ROUTE"
command -v "$ADB" >/dev/null || die "adb not found (set ADB=/path/to/adb)"
command -v python3 >/dev/null || die "python3 not found"

if [ -z "$DEVICE" ]; then
    mapfile -t DEVS < <("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
    [ "${#DEVS[@]}" -gt 0 ] || die "no device attached (adb devices)"
    [ "${#DEVS[@]}" -eq 1 ] || echo "warning: ${#DEVS[@]} devices attached; using ${DEVS[0]}" >&2
    DEVICE="${DEVS[0]}"
fi
adbd() { timeout 30 "$ADB" -s "$DEVICE" "$@"; }

# Interpolate the route into one "lat lon" per tick (route length / speed).
SCHED="$(mktemp)"
python3 - "$ROUTE" "$SPEED" "$HZ" "$START_KM" "$DURATION" > "$SCHED" <<'PY'
import math, sys, xml.etree.ElementTree as ET

def hav(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = math.radians(b[0] - a[0])
    dl = math.radians(b[1] - a[1])
    x = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(x))

route, speed_kmh, hz, start_km, dur = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
root = ET.parse(route).getroot()
pts = []
for el in root.iter():
    if el.tag.rsplit("}", 1)[-1] in ("trkpt", "rtept") and "lat" in el.attrib and "lon" in el.attrib:
        pts.append((float(el.attrib["lat"]), float(el.attrib["lon"])))
if len(pts) < 2:
    sys.exit("route has too few points")
cum = [0.0]
for i in range(len(pts) - 1):
    cum.append(cum[-1] + hav(pts[i], pts[i + 1]))
total = cum[-1]
v = speed_kmh / 3.6
start = start_km * 1000.0
remaining = max(0.0, total - start)
ndur = float(dur) if dur.strip() else remaining / v
n = int(ndur * hz) + 1
j = 0
for k in range(n):
    d = start + v * (k / hz)
    if d >= total:
        lat, lon = pts[-1]
        print(f"{lat:.6f} {lon:.6f}")
        break
    while j < len(cum) - 1 and cum[j + 1] < d:
        j += 1
    seg = cum[j + 1] - cum[j]
    f = (d - cum[j]) / seg if seg > 0 else 0.0
    lat = pts[j][0] + (pts[j + 1][0] - pts[j][0]) * f
    lon = pts[j][1] + (pts[j + 1][1] - pts[j][1]) * f
    print(f"{lat:.6f} {lon:.6f}")
PY

TOTAL=$(wc -l < "$SCHED")
[ "$TOTAL" -gt 0 ] || die "no points generated from $ROUTE"
INTERVAL=$(python3 -c "print(1.0 / float('$HZ'))")
echo "Device:   $DEVICE"
echo "Route:    $ROUTE  ($(( $(wc -c < "$SCHED") )) bytes of schedule)"
echo "Playback: ${TOTAL} fixes @ ${HZ} Hz, ${SPEED} km/h, start ${START_KM} km"

if [ "$DRY_RUN" = true ]; then
    echo "First points:"
    head -3 "$SCHED" | sed 's/^/  /'
    echo "  ..."
    tail -1 "$SCHED" | sed 's/^/  /'
    rm -f "$SCHED"
    echo "(dry run — nothing executed)"
    exit 0
fi

cleanup() {
    if [ "$KEEP" != true ]; then
        adbd shell cmd location providers set-test-provider-enabled "$PROVIDER" false >/dev/null 2>&1 || true
        adbd shell cmd location providers remove-test-provider "$PROVIDER" >/dev/null 2>&1 || true
    fi
    rm -f "$SCHED"
}
trap cleanup EXIT

# Let the adb shell act as a mock provider, then register + enable it.
adbd shell appops set --uid 2000 android:mock_location allow >/dev/null 2>&1 || true
adbd shell cmd location providers add-test-provider "$PROVIDER" >/dev/null 2>&1 || true
adbd shell cmd location providers set-test-provider-enabled "$PROVIDER" true >/dev/null

echo "Feeding locations... (Ctrl-C to stop)"
count=0
while read -r lat lon; do
    [ -z "$lat" ] && continue
    adbd shell cmd location providers set-test-provider-location "$PROVIDER" \
        --location "$lat,$lon" --accuracy "$ACCURACY" < /dev/null >/dev/null 2>&1 || true
    count=$(( count + 1 ))
    if [ $(( count % 30 )) -eq 0 ]; then
        echo "  fed $count/$TOTAL fixes"
    fi
    sleep "$INTERVAL"
done < "$SCHED"
echo "Done ($count fixes)."
