# Crazy Capy Randonneur — Plan

Open-source, voice-first, battery-extreme bike navigator for Android 17+.
Import a GPX/TCX route from popular cycling sites, start navigating, keep
the phone in the pocket with the screen off, and ride to spoken guidance.

## Vision

- Voice-first: spoken turn-by-turn directions ("Turn left in 200 metres") + beeps.
- Battery-extreme: headless screen-off navigation, adaptive GPS, no Play Services.
- Glanceable high-contrast screen when you look.
- No accounts, no ads, no telemetry.

## Decisions

- Target: Android 17 only — `minSdk = targetSdk = compileSdk = 37`
  (fallback 36 if SDK 37 download isn't available).
- Language/UI: Kotlin 2.x + Jetpack Compose (Material 3, dynamic color, edge-to-edge).
- Maps: **MapLibre Native (org.maplibre.gl:android-sdk:12.x)** — offline-capable vector maps
  (region download, MBTiles/PMTiles), the engine GraphHopper's navigation SDK builds on.
  Pivot note: Mapsforge/VTM 0.21 dropped the `.map` reader, so we use MapLibre instead.
- GPS: `LocationManager.GPS_PROVIDER` (no Google Play Services), adaptive 2–5 s intervals.
- Navigation: track-follow engine — snap to polyline, extract turn instructions from
  track geometry, Android TTS + beeps. Rerouting (GraphHopper/BRouter) deferred.
- Import: GPX v1.0/1.1 + basic TCX via system file picker; URL import later.
- Coros: post-MVP — `SensorManager` abstraction; standard BLE Heart Rate Service
  (0x180D) scanning + Coros-specific adapter (needs on-device testing).
- Branding: `Crazy Capy-D.png` from crazycapy.com; adaptive + legacy mipmaps via PIL;
  app label "Crazy Capy Randonneur". Notification icon is a separate monochrome silhouette.

## Repo layout

```
CrazyCapyRouting/
├── PLAN.md                          (this file)
├── branding/                        (downloaded logo + provenance note)
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew / gradle wrapper
└── app/
    ├── build.gradle.kts             (AGP 8.x, Kotlin 2.x, Compose BOM)
    └── src/main/
        ├── AndroidManifest.xml      (location fg service, BLE perms)
        ├── java/com/crazycapy/routing/
        │   ├── MainActivity.kt + ui/ (Compose screens)
        │   ├── map/                 (VTM MapManager, overlays)
        │   ├── gpx/                 (GpxParser, Track, TrackPoint)
        │   ├── nav/                 (NavEngine, NavState)
        │   ├── voice/               (TTS phrases + beeps)
        │   ├── gps/                 (GpsForegroundService)
        │   ├── settings/            (prefs)
        │   └── ble/                 (BtHeartRate + SensorManager stub)
        └── res/                     (themes, adaptive icon, nav composables)
```

## Milestones

- M1 — scaffold & toolchain: JDK 21 / AGP 8.x / platform 37; project boots;
  map screen shows a Mapsforge region; GPS center/follow; launcher icon from logo.
- M2 — import & overlay: pick GPX via SAF, parse, draw polyline.
- M3 — nav core: NavEngine (snap, "Turn left in 200 metres", distance/ETA), TTS.
- M4 — headless & battery: foreground service, screen-off nav, adaptive GPS,
  scoped wake-lock, notification.
- M5 — polish: settings UI, big-contrast nav screen, deviation recovery, edge cases.
- Future — Coros HR/speed; offline reroute (GraphHopper/BRouter); site URL import.

## Status

- **M1–M4 done & verified.** 31 unit tests + instrumented ghost-ride test
  (`GhostRideTest.fullGhostRideCompletes`) pass on Capy17 (Android 17 AVD) and
  on the physical phone via `./gradlew :app:connectedDebugAndroidTest`.
- M4 extras shipped: TrainingHud (speed/HR/covered/remaining), POI waypoints
  (`<wpt>` parsing + `PoiTracker` route projections), dark/light map toggle
  (dark default for OLED), StubHrProvider hooked into the service loop.
- Camera controls: zoom +/− FABs; route auto-fits to bounds on load (and on style
  reload); riding keeps follow-zoom. `reset()` on RideStore; `mapVisible` drives
  UI redraws (headless = zero map work).
- **QA**: fixed missing `getMapAsync { map = it }` — without it MapLibre never
  handed over the map object, so `setStyle` never ran and the map showed only its
  default background. Now loads OpenFreeMap style + tiles (`Mbgl-HttpRequest 200`).

## Next (M5 polish)

- Settings UI (voice on/off, units, map style persistence).
- Big-contrast nav screen + deviation recovery (off-route snap-back path).
- Edge cases: import a malformed GPX, empty route, repeated rides after reset.

## Verification

- `./gradlew assembleDebug testDebugUnitTest` green.
- `./gradlew :app:connectedDebugAndroidTest` green on AVD + physical phone.
- `adb shell dumpsys battery` before/after a headless run.

## License

Code: Apache-2.0 (to be added). Brand assets are owned by Crazy Capy
(`zingo@crazycapy.com`) and licensed to this app by their owner.