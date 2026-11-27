# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project

Crazy Capy Randonneur — open-source, voice-first, battery-extreme GPS bike
navigator for Android 13+ (API 33). No Play Services, no accounts, no cloud.

- App package / namespace: `com.crazycapy.randonneur`
- `minSdk = 33`, `targetSdk = compileSdk = 35`
- Stack: Kotlin 2.1.21, Jetpack Compose (Material 3), MapLibre + OpenFreeMap,
  WorkManager, Gradle 8.13 / AGP 8.13. No Play Services.
- Package layout under `app/src/main/java/com/crazycapy/randonneur/`:
  - `gpx` — GPX/TCX/KML loading, track/waypoint model, GPX writer
  - `nav` — guidance core: `NavEngine`, `TurnAnalyzer`, `PoiTracker`, `Geo`
    (pure Kotlin, unit-tested; no Android deps)
  - `voice` — spoken phrases (`Phrases`, `TurnSummary`), beeps (`BeepPlanner`)
  - `service` — foreground `NavigationService`: GPS, ghost-ride driver, TTS,
    beeps, wake lock, living notification; `PrepareService`: background offline
    preparation with pause/resume/cancel; `PrepareRecoveryWorker`: unique
    WorkManager resume after process death (its `SystemForegroundService`
    manifest entry must keep `dataSync`)
  - `state` — `RideStore` (shared app state), `RouteStore` (on-device route
    library + settings/last-ride persistence)
  - `sim` — ghost ride simulator
  - `ui` — Compose overlays: HUD + next-turn preview, off-route ack, dialogs
  - `ble` — BLE heart-rate (`0x180D`/`0x2A37`) and cycling speed/cadence
    (`0x1816`/`0x2A5B`) plus power (`0x1818`/`0x2A63`) providers, parsers,
    reconnect policy; debug `SET_HR`, `SET_CADENCE` and `SET_POWER` can inject
    values without hardware
  - `coros` — optional read-only COROS: region endpoints, browser OAuth (CLI
    login + PKCE), MCP client (JSON/SSE), Keystore-encrypted token store, the
    read-only data controller, post-ride latest-activity parsers and saved-ride
    overlap matching/missing-only enrichment (no live in-ride polling)

## Build environment

JDK 21 is required and is NOT on the default PATH. Always set:

```bash
export JAVA_HOME=/home/zingo/.local/opt/jdk-21.0.12+8
export PATH=$JAVA_HOME/bin:$PATH
```

adb lives at `/home/zingo/Android/Sdk/platform-tools/adb`. Known devices:
- Tablet (Wacom DTHA116, Android 14 / API 34) — preferred dev device. Needs a
  udev rule for Wacom (vendor `056a`) or it shows "no permissions":
  `SUBSYSTEM=="usb", ATTR{idVendor}=="056a", MODE="0666", GROUP="plugdev"` in
  `/etc/udev/rules.d/51-android.rules`.
- Tablet (DOOGEE T20Mini) — second test device, the API 33 (minSdk) floor. Also
  needs a udev rule (it enumerates as USB vendor `18d1`):
  `SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"` in
  `/etc/udev/rules.d/51-android.rules`.
- Phone (Pixel 7 Pro)
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
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

After changing code, run `./gradlew :app:assembleDebug :app:lintDebug` and the
unit tests; run the instrumented suite when behavior touching the service or
notification changed.

Wrap emulator work so it cleans up after itself (this also stops Gradle/Kotlin
daemons, which otherwise hold several GB):

```bash
tools/with-emulator.sh ./gradlew :app:connectedDebugAndroidTest
tools/stop-dev.sh   # manual cleanup: emulators + Gradle/Kotlin daemons
```

Do not leave an emulator or build daemons running after tests; run only one
emulator at a time. Use `CAPY_EMU_KEEP=1` only while iterating. The Gradle JVM
is capped at 2 GB and the Kotlin daemon at 1 GB; raise those only if a build
actually runs out of memory.

Before opening a PR, verify behavior on the emulator; use a physical device
(T20Mini/phone) when emulator limits matter (BLE/radio, real GPS, hardware) or
when a spot-check is really needed. Put the evidence in the PR description. Currently 275 unit tests + 4 instrumented ghost-ride tests.

Device test notes:
- The phone's notification is blocked at OS level (`dumpsys notification` shows
  `AppSettings: com.crazycapy.randonneur importance=NONE`) — a deliberate block,
  not an app bug; don't re-investigate as one.
- For no-network tests use the **per-app network deny** on the emulator (rooted),
  not airplane mode on the phone:
  `adb -s emulator-5554 shell cmd connectivity set-package-networking-enabled
  false com.crazycapy.randonneur` (verify with `get-package-networking-enabled` →
  `:deny`). The emulator app data is root-readable, so clone the phone's routes +
  prepare cache with `run-as`/`tar` to test offline navigation there.
- The emulator's software GL renders `MapSnapshotter` unreliably (90 s snapshot
  timeouts between successes), so full prepare runs belong on the phone; use the
  emulator for offline rides and pure-logic verification.
- Prepare route offline is honest about partial runs: the dialog reports e.g.
  "Pre-cached 34/347 · rest on next load" when some turns were missed, and later
  runs resume where they left off (skips existing files, retries the rest).
- Power measurement: an AVHzY/YK-Lab "Shizuku" USB power meter (Korona YK003C,
  `/dev/ttyACM0`) can log ~1 kHz V/A/W for comparing builds/settings/other apps;
  use `tools/avhzy-monitor.py` (`record` / `compare` / `windows` / `report` /
  `diff` / `promote` / `condense`, see `tools/README.md`). Runs are chunk-
  condensed by default (`--chunk 300`) and live in the git-ignored
  `power-runs/condensed/`. For repeatable ghost-ride runs use
  `tools/ghost-power-run.sh` (drives the ride over adb, starts at the same route
  point, cycles the screen to a target on-%, waits out charging, and has
  `fast`/`nano`/`small`/`medium`/`long`/`ultra` presets; default route
  `tools/routes/ystad-onnekoppinge-harlosa.gpx`). It relies on the debug-only
  `DebugControlReceiver` in `app/src/debug/`, which never ships in release.
  Runs default to `data/power/<version>/`; `docs/power/baseline.md` is the committed reference table (also `.txt` and a
  colour-coded, sortable `.html`, regenerated with it);
  `tools/power-report.sh` builds a local report, diffs it against the baseline,
  and can promote it. Light vs dark map makes no measurable power difference.

## Conventions

- Every source file carries the Apache-2.0 SPDX header:

  ```
  /*
   * Copyright (c) 2026 Crazy Capy Randonneur contributors
   * SPDX-License-Identifier: Apache-2.0
   */
  ```

  The six files under `es/jjrh/bikeradar/ipc/` (in both `aidl/` and `java/`)
  are the exception. They are unmodified copies from android-bike-radar-overlay
  and keep their own Apache-2.0 header and copyright line. Do not edit them and
  do not normalise their headers: keeping that notice is what the licence asks
  of a copy, and changing one line makes it a modified file with obligations of
  its own. See THIRD_PARTY_NOTICES.md.

- Do NOT add code comments unless asked; keep code self-explanatory.
- Pure logic goes in `nav`/`voice`/`gpx` (unit-testable, no Android imports).
  Keep `NavigationService` for Android-touching glue only.
- Distance phrasing: use `Phrases.formatDistance`/`formatShort` (units `m`/`km`,
  `0` → `"0 m"`). Speed shown as e.g. `32.0 km/h` via `formatKmh`.
- HUD is a 3×2 grid (Speed | distance covered | time / Average | distance
  remaining | tap-to-cycle ETA mode) with a north-up `TurnPreview` beside it.
  Tapping the covered cell switches between on-route distance and the actual
  ridden total (two dots show which). The main map is always north-up.
- `RideMode.FREE` starts a route-less ride: no NavEngine/green line/guidance;
  stats, blue breadcrumb and history work as for GPS rides (HUD shows max speed
  and straight-line distance home instead of left/ETA, and starting free clears
  any loaded route from the state).
- The path actually ridden is drawn as a blue line (`refreshRidden`), separate
  from the green route line, so off-route detours remain visible.
- Ride history: GPS rides are saved on Finish to `files/rides/` (GPX + `index.txt`);
  Routes → Rides shows distance/time/avg and can load one as a route. Pause
  freezes fixes/stats, Resume continues; paused time is excluded from elapsed
  time and average speed; stops/longest stop/time-in-motion are derived from point gaps. Finish shows a summary card. GPS/free rides auto-pause after 10 s below 2 km/h
  (resume after 3 s above 5 km/h), and ascent/descent are recorded from
  altitude (1 m deadband). Checkpoints every 5 min append new points to a log
  (plus a tiny atomic meta line) and are promoted on the next launch; the final
  file is GPX 1.1 with time, elevation, Garmin `gpxtpx:hr` and any BLE
  cadence/power (`gpxtpx:cad`, `gpxtpx:power`) ("cycling").
  Both Rides and saved Routes can be exported via a FileProvider
  (`<applicationId>.files`) and renamed; rename keeps the storage id stable.
- Radar targets are drawn whenever the radar is connected, not only during a
  ride; when idle the heading is derived from successive GPS fixes so dots keep
  their correct orientation (no ride/NavEngine needed).
- On-map controls: pinch to zoom (no zoom buttons); a bottom-centre `MapScaleBar`
  sized from MapLibre's `Projection.getMetersPerPixelAtLatitude`, and a
  top-right `MapCompass` shown only when the map is rotated (tap = snap north).
  The bottom-right button stack is centre-on-me (when following is released),
  dark/light toggle, then the settings gear at the very bottom.
- Beeps are decoupled from the turn popup via a `turnActive` flag in
  `NavigationService`; volume comes from `RideStore.beepVolume` /
  `navVolume` (0 = off), persisted by `RouteStore`.
- Settings UI lives in `MainActivity.kt` (dialogs); new settings need a
  `RideStore` field + a `RouteStore` save/load key.
- Tests: JUnit for JVM unit tests (currently 275), instrumented ghost-ride tests in
  `app/src/androidTest`.

## User shorthand

- "flash" or "/flash" = build the debug APK and install it on the phone
  (see `.agents/commands/flash.md` and `.agents/skills/flash/SKILL.md`).
- "pr" or "/pr" = push the current branch and open a GitHub PR against `main`
  (see `.agents/commands/pr.md` and `.agents/skills/pr/SKILL.md`).

## Docs

- `README.md` — user-facing features, build/run/test instructions, screenshots.
- `PLAN.md` — development plan / status. Keep both in sync when features change.
- `tools/README.md` — developer tools: the power-measurement toolkit, emulator
  helper, and agent commands. Document new tools there.
- Screenshot files are named `docs/screenshots/YYYY-MM-DD-NN-name.png` so stale captures are easy to spot (deleting the old file before capturing the new one).
