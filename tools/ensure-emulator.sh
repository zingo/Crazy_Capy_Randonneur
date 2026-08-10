#!/usr/bin/env bash
# Reuse a running emulator when possible; otherwise start one. Prefers quickboot
# snapshots so re-booting is fast instead of a full Android cold boot.
# Kept light for the host: 1536MB / 1 core, max-nice'd. Resume if paused.
#
# Usage: tools/ensure-emulator.sh [serial]
#   serial   Emulator target to look for/use (default: emulator-5554)

set -u
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${CAPY_AVD:-Capy17}"
TARGET="${1:-emulator-5554}"

# 1) If the target already answers adb and has a working package manager, reuse it.
#    Resume first: the console works even when the guest is paused, but `adb shell`
#    would hang until the guest answers again.
"$ADB" -s "$TARGET" emu avd resume >/dev/null 2>&1 || true
sleep 2
if "$ADB" -s "$TARGET" shell pm path android >/dev/null 2>&1; then
    echo "[ensure-emulator] reusing running emulator: $TARGET"
    exit 0
fi

# 2) If the target shows up online but is still booting, wait for it.
if [ "$("$ADB" -s "$TARGET" get-state 2>/dev/null)" = "device" ]; then
    echo "[ensure-emulator] $TARGET present but not ready; waiting for boot..."
    timeout 180 bash -c "until '$ADB' -s '$TARGET' shell pm path android >/dev/null 2>&1; do sleep 5; done"
    if "$ADB" -s "$TARGET" shell pm path android >/dev/null 2>&1; then
        "$ADB" -s "$TARGET" emu avd resume >/dev/null 2>&1 || true
        echo "[ensure-emulator] ready."
        exit 0
    fi
    # Fell through => system wedged. Kill and cold-boot below.
    "$ADB" -s "$TARGET" emu kill >/dev/null 2>&1
    sleep 3
fi

start_new() {  # $1 = extra flags
    nohup nice -n 10 setsid "$EMULATOR" -avd "$AVD" \
        -no-audio -no-boot-anim \
        -memory 1536 -cores 1 \
        -gpu swiftshader_indirect \
        $1 \
        >/tmp/opencode/emulator.log 2>&1 &
    disown
}

# 3) Start a new one (quickboot snapshot first, cold boot if that fails).
echo "[ensure-emulator] starting $AVD (nice'd, 1536MB/1core)..."
start_new ""
echo "[ensure-emulator] waiting for Android to be ready..."
if timeout 240 bash -c "until '$ADB' -s '$TARGET' shell pm path android >/dev/null 2>&1; do sleep 5; done"; then
    echo "[ensure-emulator] $TARGET is ready."
    exit 0
fi

echo "[ensure-emulator] boot did not complete; cold-starting a snapshotless instance..."
start_new "-no-snapshot"
if timeout 300 bash -c "until '$ADB' -s '$TARGET' shell pm path android >/dev/null 2>&1; do sleep 5; done"; then
    echo "[ensure-emulator] $TARGET is ready (cold boot)."
    exit 0
fi
echo "[ensure-emulator] ERROR: emulator did not come up." >&2
exit 1