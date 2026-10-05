#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# ghost-power-run.sh — run a route in ghost mode on a connected device while
# recording power from an AVHzY/Shizuku USB power meter, so different builds
# or settings can be compared.
#
# Requires the debug APK (the hook it uses is debug-only) and pyserial.
#
# Examples:
#   tools/ghost-power-run.sh --label baseline --preset fast
#   tools/ghost-power-run.sh --label medium-run --preset medium
#   tools/ghost-power-run.sh --label whole-route --preset ultra
#   tools/ghost-power-run.sh --label custom -d 900 --screen-percent 25
#
# Presets set the ride length, screen-on percentage and ghost speed. All but
# fast use 15% screen at 20 km/h, so a longer run's prefix windows (see below)
# line up with the shorter presets. fast is a quick all-screen smoke test.
#   fast    5 min   100% screen, 40 km/h
#   nano   10 min   15% screen, 20 km/h
#   small  30 min   15% screen, 20 km/h
#   medium  2 h     15% screen, 20 km/h
#   long    4 h     15% screen, 20 km/h
#   ultra  whole route, 15% screen, 20 km/h

set -euo pipefail

# Keep the host awake for the duration of the run and release it afterwards
# (standard systemd/freedesktop inhibitor). Re-exec once under systemd-inhibit;
# use --no-inhibit to skip.
if [ "${CAPY_INHIBITED:-}" != "1" ]; then
    for a in "$@"; do
        [ "$a" = "--no-inhibit" ] && CAPY_INHIBITED=1
    done
fi
if [ "${CAPY_INHIBITED:-}" != "1" ] && command -v systemd-inhibit >/dev/null 2>&1; then
    export CAPY_INHIBITED=1
    exec systemd-inhibit --what=idle:sleep --why="ghost power run: $*" --mode=block "$0" "$@"
fi
CAPY_INHIBITED=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-adb}"
PKG="com.crazycapy.randonneur"
RECEIVER="$PKG/.debug.DebugControlReceiver"
A_START="$PKG.debug.START_GHOST"
A_STOP="$PKG.debug.STOP"
REMOTE_ROUTE="/data/local/tmp/ghost-power-route.gpx"

LABEL=""
NOTE=""
PRESET=""
ROUTE="$SCRIPT_DIR/routes/ystad-onnekoppinge-harlosa.gpx"
DURATION=""
TIME_SCALE=1
SPEED=""
REVERSE=false
DARK_MAP=true
SCREEN_PERCENT=""
SCREEN_PERIOD=60
SETTLE=5
START_KM=0
BRIGHTNESS=70
VOLUME=20
CHUNK=300
ALLOW_CHARGING=false
NO_WAIT=false
WAIT_DISCHARGE=3600
DEVICE=""
OUT="power-runs"
DO_COMPARE=true
DRY_RUN=false
PIN="${SCREEN_PIN:-}"

usage() {
    awk 'NR<4 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "$0"
    cat <<EOF

Options:
  -l, --label NAME        name of this run (required; e.g. build/setting)
  -n, --note TEXT         free-text note stored in the CSV header
  -p, --preset NAME       fast | nano | small | medium | long | ultra
  -r, --route FILE        GPX route (default: tools/routes/ystad-onnekoppinge-harlosa.gpx)
  -d, --duration SECONDS  ride length, overrides the preset
  -t, --time-scale N      ghost time scale (default $TIME_SCALE)
  -s, --speed KMH         ghost speed (default: preset, else 20)
      --screen-percent N  % of the ride the screen is on, 0-100 (default: preset, else 100)
      --screen-period SEC cycle length for the on/off pattern (default $SCREEN_PERIOD)
      --start-km N        start this far along the route (default 0 = route start)
      --brightness PCT    screen brightness %, 0-100 or 'off' (default $BRIGHTNESS)
      --volume PCT        media/TTS volume %, 0-100 or 'off' (default $VOLUME)
      --chunk SEC         condense samples into SEC-second chunks; 0 = raw (default $CHUNK)
      --allow-charging    run even if the device is charging (measurements may be capped)
      --no-wait           fail instead of waiting for the device to stop charging
      --wait-charge SEC   cap the pre-run charge wait (default $WAIT_DISCHARGE; 0 = wait forever)
      --reverse           ride the route in reverse
      --light             light map (default: dark)
      --settle SEC        settle time after starting the ride (default $SETTLE)
      --device SERIAL     adb device serial (default: the only attached device)
      --pin NNNN          PIN to unlock the screen after wake (or set SCREEN_PIN)
  -o, --out DIR           output directory (default $OUT)
      --no-compare        skip the comparison table at the end
      --no-inhibit        don't keep the host awake via systemd-inhibit
      --dry-run           print the plan (incl. computed ultra length) and exit
  -h, --help              show this help
EOF
}

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        -l|--label) LABEL="$2"; shift 2 ;;
        -n|--note) NOTE="$2"; shift 2 ;;
        -p|--preset) PRESET="$2"; shift 2 ;;
        -r|--route) ROUTE="$2"; shift 2 ;;
        -d|--duration) DURATION="$2"; shift 2 ;;
        -t|--time-scale) TIME_SCALE="$2"; shift 2 ;;
        -s|--speed) SPEED="$2"; shift 2 ;;
        --screen-percent) SCREEN_PERCENT="$2"; shift 2 ;;
        --screen-period) SCREEN_PERIOD="$2"; shift 2 ;;
        --start-km) START_KM="$2"; shift 2 ;;
        --brightness) BRIGHTNESS="$2"; shift 2 ;;
        --volume) VOLUME="$2"; shift 2 ;;
        --chunk) CHUNK="$2"; shift 2 ;;
        --allow-charging) ALLOW_CHARGING=true; shift ;;
        --wait-charge) WAIT_DISCHARGE="$2"; shift 2 ;;
        --no-wait) NO_WAIT=true; shift ;;
        --reverse) REVERSE=true; shift ;;
        --light) DARK_MAP=false; shift ;;
        --settle) SETTLE="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --pin) PIN="$2"; shift 2 ;;
        -o|--out) OUT="$2"; shift 2 ;;
        --no-compare) DO_COMPARE=false; shift ;;
        --no-inhibit) shift ;;
        --dry-run) DRY_RUN=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

[ -n "$LABEL" ] || die "--label is required (try --help)"
[ -f "$ROUTE" ] || die "route not found: $ROUTE"
command -v "$ADB" >/dev/null || die "adb not found (set ADB=/path/to/adb)"
command -v python3 >/dev/null || die "python3 not found"
[[ "$START_KM" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--start-km must be a number (km)"

# --- Preset resolution -------------------------------------------------------
# A preset fills in duration + screen percent; explicit flags still win.
PRESET_DUR=""
PRESET_PCT=""
PRESET_SPEED=""
case "$PRESET" in
    "") ;;
    fast)   PRESET_DUR=300;   PRESET_PCT=100; PRESET_SPEED=40 ;;
    nano)   PRESET_DUR=600;   PRESET_PCT=15;  PRESET_SPEED=20 ;;
    small)  PRESET_DUR=1800;  PRESET_PCT=15;  PRESET_SPEED=20 ;;
    medium) PRESET_DUR=7200;  PRESET_PCT=15;  PRESET_SPEED=20 ;;
    long)   PRESET_DUR=14400; PRESET_PCT=15;  PRESET_SPEED=20 ;;
    ultra)  PRESET_DUR=route; PRESET_PCT=15;  PRESET_SPEED=20 ;;
    *) die "unknown preset: $PRESET (fast|nano|small|medium|long|ultra)" ;;
esac
SPEED="${SPEED:-${PRESET_SPEED:-20}}"
START_M="$(python3 -c "print(int(float('$START_KM') * 1000))")"

route_length_m() {
    python3 - "$1" <<'PY'
import math, sys, xml.etree.ElementTree as ET

def hav(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print(0)
    sys.exit()
pts = []
for el in root.iter():
    if el.tag.rsplit("}", 1)[-1] in ("trkpt", "rtept") and "lat" in el.attrib and "lon" in el.attrib:
        pts.append((float(el.attrib["lat"]), float(el.attrib["lon"])))
print(int(sum(hav(*pts[i], *pts[i + 1]) for i in range(len(pts) - 1))))
PY
}

if [ -z "$DURATION" ]; then
    if [ "$PRESET_DUR" = "route" ]; then
        LEN_M="$(route_length_m "$ROUTE")"
        [ "$LEN_M" -gt 0 ] || die "could not read route length from $ROUTE"
        DURATION="$(python3 -c "print(max(1, round($LEN_M / 1000.0 / ($SPEED * $TIME_SCALE) * 3600)))")"
        echo "whole route: $(( LEN_M / 1000 )) km → ${DURATION}s at ${SPEED} km/h (x$TIME_SCALE)"
    elif [ -n "$PRESET_DUR" ]; then
        DURATION="$PRESET_DUR"
    else
        DURATION=120
    fi
fi

if [ -z "$SCREEN_PERCENT" ]; then
    SCREEN_PERCENT="${PRESET_PCT:-100}"
fi
[ "$SCREEN_PERCENT" -ge 0 ] && [ "$SCREEN_PERCENT" -le 100 ] || die "--screen-percent must be 0-100"

if [ -z "$DEVICE" ]; then
    mapfile -t DEVS < <("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
    [ "${#DEVS[@]}" -gt 0 ] || die "no device attached (adb devices)"
    [ "${#DEVS[@]}" -eq 1 ] || echo "warning: ${#DEVS[@]} devices attached; using ${DEVS[0]}" >&2
    DEVICE="${DEVS[0]}"
fi

# Bound every adb call: if the device drops mid-run, hung commands would
# otherwise stall the whole run (and any batch driving it) indefinitely.
adbd() { timeout 30 "$ADB" -s "$DEVICE" "$@"; }

# Best-effort: exempt the app from battery optimizations. This lets the debug
# hook start/stop the foreground service even while the screen is locked (the
# system logs this as code SYSTEM_ALLOW_LISTED), so long runs keep going.
adbd shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

# Record (and optionally set) the screen brightness so runs are comparable.
# Brightness is a percentage 0-100 (default 70 = bright enough for sunlight);
# 'off' or -1 leaves the device's current setting alone.
BRIGHTNESS_RAW="$BRIGHTNESS"
if [ "$BRIGHTNESS" = "off" ] || [ "$BRIGHTNESS" = "-1" ]; then
    raw="$(adbd shell settings get system screen_brightness 2>/dev/null | tr -d '\r')"
    case "$raw" in ''|*[!0-9]*) BRIGHTNESS_PCT="?" ;; *) BRIGHTNESS_PCT="$(( (raw * 100 + 127) / 255 ))%" ;; esac
elif [ "$BRIGHTNESS" -ge 0 ] 2>/dev/null && [ "$BRIGHTNESS" -le 100 ]; then
    BRIGHTNESS_RAW=$(( (BRIGHTNESS * 255 + 50) / 100 ))
    BRIGHTNESS_PCT="$BRIGHTNESS%"
    adbd shell settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
    adbd shell settings put system screen_brightness "$BRIGHTNESS_RAW" >/dev/null 2>&1 || true
else
    die "--brightness must be 0-100, or 'off'"
fi
BRIGHTNESS_NOW="$BRIGHTNESS_PCT"
BATTERY_NOW="$(adbd shell dumpsys battery 2>/dev/null | awk -F: '/level/{gsub(/ /,"",$2);print $2; exit}')"
BATTERY_STATUS="$(adbd shell dumpsys battery 2>/dev/null | awk -F: '/status:/{gsub(/ /,"",$2);print $2; exit}')"

# Identify the device under test so reports can be compared across devices.
DEVICE_NAME="$(adbd shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r') $(adbd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
DEVICE_NAME="$(printf '%s' "$DEVICE_NAME" | tr -s ' ' | sed 's/^ //; s/ $//')"
DEVICE_ANDROID="$(adbd shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
DEVICE_LABEL="${DEVICE_NAME:-unknown} (Android ${DEVICE_ANDROID:-?})"

# Media/speaker volume (the stream TTS and beeps use). Read it always; set it
# with --volume PCT (0-100) or leave the device alone with 'off'.
read_media_vol()     { adbd shell dumpsys audio 2>/dev/null | awk '/^- STREAM_MUSIC:/{f=1} f&&/streamVolume:/{gsub(/.*streamVolume:/,"");print;exit}'; }
read_media_vol_max() { adbd shell dumpsys audio 2>/dev/null | awk '/^- STREAM_MUSIC:/{f=1} f&&/Max:/{gsub(/.*Max: */,"");print;exit}'; }
set_media_vol() {  # $1 = target index (key events only; --set is a no-op here)
    local target="$1" cur
    for _ in $(seq 1 25); do
        cur="$(read_media_vol)"
        [ -z "$cur" ] && return 0
        [ "$cur" -eq "$target" ] && return 0
        if [ "$cur" -lt "$target" ]; then adbd shell input keyevent 24 >/dev/null 2>&1
        else adbd shell input keyevent 25 >/dev/null 2>&1; fi
        sleep 0.4
    done
}
VOL_MAX="$(read_media_vol_max)"; VOL_MAX="${VOL_MAX:-15}"
if [ "$VOLUME" != "off" ] && [ -n "$VOLUME" ]; then
    case "$VOLUME" in ''|*[!0-9]*) die "--volume must be 0-100, or 'off'" ;; esac
    [ "$VOLUME" -le 100 ] || die "--volume must be 0-100, or 'off'"
    set_media_vol "$(( (VOLUME * VOL_MAX + 50) / 100 ))"
fi
VOL_IDX="$(read_media_vol)"
VOLUME_NOW="${VOL_IDX:-?}/$VOL_MAX ($(( ${VOL_IDX:-0} * 100 / VOL_MAX ))%)"

# Turn the screen off and make sure it actually slept: KEYCODE_SLEEP occasionally
# fails to take (the display wakes itself), which would silently inflate the
# on-percentage over a long run. Retry a few times.
screen_off() {
    adbd shell input keyevent 223 >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5; do
        sleep 1
        w="$(adbd shell dumpsys power 2>/dev/null | sed -n 's/.*mWakefulness=\([A-Za-z]*\).*/\1/p' | head -1)"
        [ "$w" = "Asleep" ] && return 0
        adbd shell input keyevent 223 >/dev/null 2>&1 || true
    done
    return 0
}
screen_on()  { adbd shell input keyevent 224 >/dev/null 2>&1 || true; adbd shell wm dismiss-keyguard >/dev/null 2>&1 || true; }

# Wake, unlock and make sure the app is the resumed activity. Android 12+ only
# lets an app start/stop its foreground service while it is actually in the
# foreground, so this must succeed before START and STOP.
bring_to_front() {
    screen_on
    if [ -n "$PIN" ]; then
        # Unlock a PIN keyguard so the screen-on phase really shows the app.
        adbd shell input text "$PIN" >/dev/null 2>&1 || true
        adbd shell input keyevent 66 >/dev/null 2>&1 || true
        sleep 1
    fi
    adbd shell input keyevent 82 >/dev/null 2>&1 || true
    adbd shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
    for _ in $(seq 1 6); do
        if adbd shell dumpsys activity activities 2>/dev/null | grep -q "ResumedActivity.*$PKG"; then
            return 0
        fi
        sleep 1
    done
    echo "warning: $PKG did not come to the foreground" >&2
    return 0
}

elapsed() { echo $(( $(date +%s) - RIDE_START )); }
remaining() { echo $(( DURATION - $(elapsed) )); }

echo "Device:  $DEVICE"
echo "Route:   $ROUTE"
echo "Label:   $LABEL${NOTE:+  ($NOTE)}"
echo "Ride:    ${DURATION}s @ x$TIME_SCALE, ${SPEED} km/h, reverse=$REVERSE, darkMap=$DARK_MAP, start=${START_KM} km"
if [ "$SCREEN_PERCENT" -eq 100 ]; then
    echo "Screen:  always on"
else
    echo "Screen:  ${SCREEN_PERCENT}% on, ${SCREEN_PERIOD}s cycle"
fi
echo "Device:  brightness=${BRIGHTNESS_NOW:-?} battery=${BATTERY_NOW:-?}%"
echo "Model:   $DEVICE_LABEL"
echo "Volume:  media ${VOLUME_NOW}"

if [ "$DRY_RUN" = true ]; then
    echo "(dry run — nothing executed)"
    exit 0
fi

REC_PID=""
RIDE_STARTED=false
RIDE_START=0
TMP_LOG="$(mktemp -t ghost-power-XXXXXX.log)"
cleanup() {
    if [ "$RIDE_STARTED" = true ]; then
        adbd shell am broadcast -a "$A_STOP" -n "$RECEIVER" >/dev/null 2>&1 || true
    fi
    adbd shell svc power stayon false >/dev/null 2>&1 || true
    if [ -n "$REC_PID" ] && kill -0 "$REC_PID" 2>/dev/null; then
        kill -TERM "$REC_PID" 2>/dev/null || true
        wait "$REC_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# 1. Push the route somewhere the app can read.
adbd push "$ROUTE" "$REMOTE_ROUTE" >/dev/null

# 2. Wake the device and bring the app to the foreground. The activity must be
#    visible when we start the ride: that is the FGS "activity starter"
#    exemption, and without it Android 12+ blocks the background FGS start.
bring_to_front
sleep 2

# Keep the screen awake while USB-powered so the on-phase isn't cut short by the
# system screen timeout (crucial for --screen-percent 100). Explicit KEYCODE_SLEEP
# still turns it off during the off-phase.
adbd shell svc power stayon true >/dev/null 2>&1 || true

# The device must be net-discharging for the meter to see the app: while it is
# charging the USB input is power-capped, so screen-off and screen-on read the
# same and the run is useless. Measure the screen-off draw and wait for it to
# settle (up to --wait-charge seconds), unless told to skip.
if [ "$ALLOW_CHARGING" != true ]; then
    waited=0
    while :; do
        screen_off
        CHK_DIR="$(mktemp -d)"
        CHK="$(python3 "$SCRIPT_DIR/avhzy-monitor.py" record --label charging-check -d 4 -q -o "$CHK_DIR" 2>&1 \
            | sed -n 's/.*power *avg *\([0-9.]*\) W.*/\1/p')"
        rm -rf "$CHK_DIR"
        if [ -z "$CHK" ] || python3 -c "import sys; sys.exit(0 if float('$CHK') <= 1.5 else 1)"; then
            break
        fi
        if [ "$NO_WAIT" = true ]; then
            die "screen-off draw is ${CHK} W → the device is charging (USB input power-capped). Pass --allow-charging to run anyway."
        fi
        if [ -n "$WAIT_DISCHARGE" ] && [ "$WAIT_DISCHARGE" != "0" ] && [ "$waited" -ge "$WAIT_DISCHARGE" ]; then
            die "screen-off draw is ${CHK} W → still charging after ${waited}s. Pass --allow-charging / --no-wait, or raise --wait-charge."
        fi
        echo "  waiting for the device to stop charging (screen-off ${CHK} W; waited ${waited}s${WAIT_DISCHARGE:+ / ${WAIT_DISCHARGE}s})"
        # Leave the screen OFF between checks: with a 500 mA host the surplus
        # goes to the battery, so a lit screen would stall the charge.
        sleep 15
        waited=$(( waited + 15 ))
    done
    screen_on
fi

# 3. Start the power recording in the background (runs until we SIGTERM it).
python3 "$SCRIPT_DIR/avhzy-monitor.py" record \
    --label "$LABEL" ${NOTE:+--note "$NOTE"} \
    --meta "preset=${PRESET:-custom}" --meta "duration_s=$DURATION" \
    --meta "screen_percent=$SCREEN_PERCENT" --meta "screen_period=$SCREEN_PERIOD" \
    --meta "speed_kmh=$SPEED" --meta "time_scale=$TIME_SCALE" \
    --meta "start_km=$START_KM" --meta "dark_map=$DARK_MAP" \
    --meta "brightness=${BRIGHTNESS_NOW:-?}" --meta "battery=${BATTERY_NOW:-?}" \
    --meta "battery_status=${BATTERY_STATUS:-?}" \
    --meta "volume=$VOLUME_NOW" \
    --meta "device=$DEVICE_LABEL" \
    --meta "route=$(basename "$ROUTE")" \
    --warmup 0 --chunk "$CHUNK" --out "$OUT" --quiet > "$TMP_LOG" 2>&1 &
REC_PID=$!
sleep 1
kill -0 "$REC_PID" 2>/dev/null || { cat "$TMP_LOG"; die "power meter recorder exited early"; }

# 4. Start the ghost ride.
adbd shell am broadcast -a "$A_START" -n "$RECEIVER" \
    --es route "$REMOTE_ROUTE" \
    --ed timeScale "$TIME_SCALE" --ed speedKmh "$SPEED" \
    --ed startM "$START_M" \
    --ez reverse "$REVERSE" --ez darkMap "$DARK_MAP" >/dev/null
RIDE_STARTED=true
RIDE_START=$(date +%s)
sleep "$SETTLE"

# 5. Screen on/off cycling to hit the target on-percentage, plus a periodic
#    status line so a multi-hour run is visibly alive.
ON_S=$(( SCREEN_PERIOD * SCREEN_PERCENT / 100 ))
OFF_S=$(( SCREEN_PERIOD - ON_S ))
[ "$SCREEN_PERCENT" -gt 0 ] && [ "$ON_S" -lt 1 ] && ON_S=1
[ "$SCREEN_PERCENT" -lt 100 ] && [ "$OFF_S" -lt 1 ] && OFF_S=1
NEXT_STATUS=30

if [ "$SCREEN_PERCENT" -eq 0 ]; then
    screen_off
fi

while :; do
    rem=$(remaining)
    [ "$rem" -le 0 ] && break
    if ! kill -0 "$REC_PID" 2>/dev/null; then
        echo "error: power-meter recorder died mid-run; aborting" >&2
        break
    fi
    if [ "$SCREEN_PERCENT" -gt 0 ] && [ "$SCREEN_PERCENT" -lt 100 ]; then
        sleep $(( rem < ON_S ? rem : ON_S ))
        rem=$(remaining); [ "$rem" -le 0 ] && break
        screen_off
        sleep $(( rem < OFF_S ? rem : OFF_S ))
        rem=$(remaining); [ "$rem" -le 0 ] && break
        screen_on
    else
        sleep $(( rem < 5 ? rem : 5 ))
    fi
    if [ "$(elapsed)" -ge "$NEXT_STATUS" ]; then
        echo "  ... $(elapsed)s / ${DURATION}s  (screen ${SCREEN_PERCENT}%)"
        NEXT_STATUS=$(( NEXT_STATUS + 30 ))
    fi
done

# 6. The ride time is up: stop the recording first so the wake/stop overhead
#    doesn't dilute the measurement, then bring the app forward and stop the
#    ride (the stop goes through startService, blocked while backgrounded).
kill -TERM "$REC_PID" 2>/dev/null || true
wait "$REC_PID" 2>/dev/null || true
REC_PID=""
bring_to_front
sleep 1
adbd shell am broadcast -a "$A_STOP" -n "$RECEIVER" >/dev/null
RIDE_STARTED=false
screen_on

# 7. Show the run summary and (optionally) compare all runs.
cat "$TMP_LOG"
rm -f "$TMP_LOG"

# For 15%-screen runs, also break the run into the standard preset windows, so a
# single long run yields the nano/small/medium/long numbers too.
if [ "$SCREEN_PERCENT" -eq 15 ]; then
    NEW_CSV="$(ls -t "$OUT"/*.csv 2>/dev/null | head -1)"
    if [ -n "$NEW_CSV" ]; then
        echo
        echo "=== prefix windows (15% screen) ==="
        python3 "$SCRIPT_DIR/avhzy-monitor.py" windows "$NEW_CSV" \
            --durations 600,1800,7200,14400 --labels nano,small,medium,long
    fi
fi

if [ "$DO_COMPARE" = true ]; then
    echo
    echo "=== comparison ==="
    python3 "$SCRIPT_DIR/avhzy-monitor.py" compare "$OUT"
fi
