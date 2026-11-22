#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"

for s in $("$ADB" devices 2>/dev/null | awk 'NR > 1 && $1 ~ /^emulator-/ { print $1 }'); do
    "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
done
pkill -TERM -f 'qemu-system' 2>/dev/null || true
sleep 2
pkill -KILL -f 'qemu-system' 2>/dev/null || true
timeout 20 bash -c "cd '$ROOT' && ./gradlew --stop" >/dev/null 2>&1 || true
pkill -f 'org.jetbrains.kotlin.daemon.KotlinCompileDaemon' 2>/dev/null || true
free -h | head -2
