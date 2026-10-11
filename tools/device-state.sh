#!/usr/bin/env bash
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# device-state.sh — snapshot and restore the on-device settings the power
# harness changes (brightness, volume, stay-awake, screensaver, wake gestures,
# battery whitelist), so a test device can be put back exactly as it was.
#
# The state is written to a per-device file (default
# power-runs/device-state-<serial>.env, git-ignored). ghost-power-run.sh saves
# before it touches anything and restores on exit; if a run is interrupted the
# file is left behind and you can restore later by hand:
#
#   tools/device-state.sh save                       # snapshot the connected device
#   tools/device-state.sh save -s SERIAL -o /tmp/x   # explicit device / file
#   tools/device-state.sh restore                    # re-apply the saved state
#   tools/device-state.sh show                       # print the live values
#
# Env: ADB (default ~/Android/Sdk/platform-tools/adb), ANDROID_SERIAL.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
device_given=""
file_given=""
PKG="com.crazycapy.randonneur"

# "namespace name" pairs captured/restored. Order matters: brightness_mode is
# written before screen_brightness so the raw value isn't clamped by auto mode.
SETTINGS=(
    "system screen_brightness_mode"
    "system screen_brightness"
    "system screen_off_timeout"
    "system volume_music"
    "global stay_on_while_plugged_in"
    "global ambient_touch_to_wake"
    "global ambient_tilt_to_wake"
    "secure screensaver_enabled"
    "secure double_tap_to_wake"
    "secure wake_gesture_enabled"
)

usage() {
    cat <<EOF
usage: tools/device-state.sh {save|restore|show} [-s SERIAL] [-o|-i FILE]

  save      snapshot the current settings to FILE
  restore   re-apply the settings saved in FILE
  show      print the current settings (no file written)

  -s, --device SERIAL   device serial (default: ANDROID_SERIAL or the only device)
  -o, --out FILE        save to FILE   (default power-runs/device-state-<serial>.env)
  -i, --in FILE         restore from FILE (default power-runs/device-state-<serial>.env)
      --file FILE       set both
EOF
}

cmd=""
while [ $# -gt 0 ]; do
    case "$1" in
        save|restore|show) cmd="$1"; shift ;;
        -s|--device) device_given="$2"; shift 2 ;;
        -o|--out|-i|--in|--file) file_given="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
    esac
done
[ -n "$cmd" ] || { usage; exit 1; }

pick_device() {
    if [ -n "$device_given" ]; then
        echo "$device_given"; return
    fi
    if [ -n "${ANDROID_SERIAL:-}" ]; then
        echo "$ANDROID_SERIAL"; return
    fi
    local one
    one="$("$ADB" devices | awk 'NR>1 && $2=="device"{if(n++){print "MULTI"; exit} print $1} END{ }')"
    if [ "$one" = "MULTI" ] || [ -z "$one" ]; then
        echo "error: multiple/no devices attached; pass -s SERIAL" >&2
        exit 1
    fi
    echo "$one"
}

SERIAL="$(pick_device)"
DEF_FILE="power-runs/device-state-${SERIAL}.env"
FILE="${file_given:-$DEF_FILE}"

d() { timeout 30 "$ADB" -s "$SERIAL" "$@" < /dev/null; }

get() {  # namespace name -> value
    d shell settings get "$1" "$2" 2>/dev/null | tr -d '\r' | head -1
}

show_state() {
    echo "device $SERIAL"
    for spec in "${SETTINGS[@]}"; do
        read -r ns name <<<"$spec"
        printf '  %-7s %-28s %s\n' "$ns" "$name" "$(get "$ns" "$name")"
    done
    echo "  pkg     $PKG (deviceidle)          $(d shell dumpsys deviceidle whitelist 2>/dev/null | grep -qi "$PKG" && echo whitelisted || echo not-whitelisted)"
}

save_state() {
    mkdir -p "$(dirname "$FILE")"
    {
        echo "# device-state for $SERIAL, saved $(date -Iseconds)"
        echo "device $SERIAL"
        for spec in "${SETTINGS[@]}"; do
            read -r ns name <<<"$spec"
            echo "setting $ns $name $(get "$ns" "$name")"
        done
        if d shell dumpsys deviceidle whitelist 2>/dev/null | grep -qi "$PKG"; then
            echo "whitelist yes"
        else
            echo "whitelist no"
        fi
    } > "$FILE"
    echo "saved device state -> $FILE"
}

restore_state() {
    [ -f "$FILE" ] || { echo "error: no state file at $FILE" >&2; exit 1; }
    while read -r tag a b c; do
        case "$tag" in
            setting)
                ns="$a"; name="$b"; val="$c"
                if [ -z "$val" ] || [ "$val" = "null" ]; then
                    d shell settings delete "$ns" "$name" >/dev/null 2>&1 || true
                else
                    d shell settings put "$ns" "$name" "$val" >/dev/null 2>&1 || true
                fi
                ;;
            whitelist)
                if [ "$a" = "yes" ]; then
                    d shell cmd deviceidle whitelist "+$PKG" >/dev/null 2>&1 || true
                else
                    d shell cmd deviceidle whitelist "-$PKG" >/dev/null 2>&1 || true
                fi
                ;;
        esac
    done < "$FILE"
    echo "restored device state from $FILE"
}

case "$cmd" in
    save)    save_state ;;
    restore) restore_state ;;
    show)    show_state ;;
esac
