# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project

Crazy Capy Randonneur — open-source, voice-first, battery-extreme GPS bike
navigator for Android 17+ (API 37). No Play Services, no accounts, no cloud.

- App package / namespace: `com.crazycapy.randonneur`
- `minSdk = targetSdk = compileSdk = 37`
- Stack: Kotlin 2.1.21, Jetpack Compose (Material 3), MapLibre + OpenFreeMap,
  Gradle 8.13 / AGP 8.13. No Play Services.
- Package layout under `app/src/main/java/com/crazycapy/randonneur/`:
  - `gpx` — GPX/TCX/KML loading, track/waypoint model, GPX writer
  - `nav` — guidance core: `NavEngine`, `TurnAnalyzer`, `PoiTracker`, `Geo`
    (pure Kotlin, unit-tested; no Android deps)
  - `voice` — spoken phrases (`Phrases`, `TurnSummary`), beeps (`BeepPlanner`)
  - `service` — foreground `NavigationService`: GPS, ghost-ride driver, TTS,
    beeps, wake lock, living notification
  - `state` — `RideStore` (shared app state), `RouteStore` (on-device route
    library + settings/last-ride persistence)
  - `sim` — ghost ride simulator
  - `ui` — Compose overlays: HUD + next-turn preview, off-route ack, dialogs
  - `ble` — BLE heart-rate provider (stub by default)

## Build environment

JDK 21 is required and is NOT on the default PATH. Always set:

```bash
export JAVA_HOME=/home/zingo/.local/opt/jdk-21.0.12+8
export PATH=$JAVA_HOME/bin:$PATH
```

adb lives at `/home/zingo/Android/Sdk/platform-tools/adb`. Known devices:
- Phone (Pixel 7 Pro): serial `35181FDH3000QT`
- Emulator: `emulator-5554`

## Commands

```bash
# Build debug APK (always run before installing — the APK file is stale otherwise)
./gradlew :app:assembleDebug

# Lint
./gradlew :app:lintDebug

# Unit tests (pure Kotlin core — fast, no device)
./gradlew :app:testDebugUnitTest

# Instrumented tests (runs on ALL attached devices/emulators)
./gradlew :app:connectedDebugAndroidTest

# Install to phone / emulator
adb -s 35181FDH3000QT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

After changing code, run `./gradlew :app:assembleDebug :app:lintDebug` and the
unit tests; run the instrumented suite when behavior touching the service or
notification changed. Currently 72 unit tests + 4 instrumented ghost-ride tests.

Device test notes:
- The phone's notification is blocked at OS level (`dumpsys notification` shows
  `AppSettings: com.crazycapy.randonneur importance=NONE`) — a deliberate block,
  not an app bug; don't re-investigate as one.
- For no-network tests use the **per-app network deny** on the emulator (rooted),
  not airplane mode on the phone:
  `adb -s emulator-5554 shell cmd connectivity set-package-networking-enabled
  false com.crazycapy.randonneur` (verify with `get-package-networking-enabled` →
  `:deny`). The emulator app data is root-readable, so clone the phone's routes +
  pre-cache with `run-as`/`tar` to test offline navigation there.
- The emulator's software GL renders `MapSnapshotter` unreliably (90 s snapshot
  timeouts between successes), so full pre-cache runs belong on the phone; use the
  emulator for offline rides and pure-logic verification.
- Pre-cache is honest about partial runs: the dialog reports e.g. "Pre-cached
  34/347 · rest on next load" when some turns were missed, and later loads resume
  where they left off (skips existing files, retries the rest).

## Conventions

- Every source file carries the Apache-2.0 SPDX header:

  ```
  /*
   * Copyright (c) 2026 Crazy Capy Randonneur contributors
   * SPDX-License-Identifier: Apache-2.0
   */
  ```

- Do NOT add code comments unless asked; keep code self-explanatory.
- Pure logic goes in `nav`/`voice`/`gpx` (unit-testable, no Android imports).
  Keep `NavigationService` for Android-touching glue only.
- Distance phrasing: use `Phrases.formatDistance`/`formatShort` (units `m`/`km`,
  `0` → `"0 m"`). Speed shown as e.g. `32.0 km/h` via `formatKmh`.
- HUD is a 3×2 grid (Speed | distance covered | elapsed / Average | distance
  remaining | tap-to-cycle ETA mode) with a north-up `TurnPreview` beside it. The
  main map is always north-up.
- Beeps are decoupled from the turn popup via a `turnActive` flag in
  `NavigationService`; volume comes from `RideStore.beepVolume` /
  `navVolume` (0 = off), persisted by `RouteStore`.
- Settings UI lives in `MainActivity.kt` (dialogs); new settings need a
  `RideStore` field + a `RouteStore` save/load key.
- Tests: JUnit for JVM unit tests (currently 72), instrumented ghost-ride tests in
  `app/src/androidTest`.

## User shorthand

- "flash" or "/flash" = build the debug APK and install it on the phone
  (see `.agents/commands/flash.md` and `.agents/skills/flash/SKILL.md`).

## Docs

- `README.md` — user-facing features, build/run/test instructions, screenshots.
- `PLAN.md` — development plan / status. Keep both in sync when features change.
- Screenshot files are named `docs/screenshots/YYYY-MM-DD-NN-name.png` so stale captures are easy to spot (deleting the old file before capturing the new one).
