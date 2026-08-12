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
- GPS: `LocationManager.GPS_PROVIDER` (no Google Play Services). Currently a
  flat 3 s interval; adaptive intervals are a future suggestion (below).
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

## Future suggestions

- **Adaptive GPS interval.** GPS duty cycle is the main screen-off drain; on
  a long straight we request ~10x more fixes than the guidance needs. Idea:
  vary `requestLocationUpdates` `minTime` on the next-turn distance with
  hysteresis, e.g. `nextTurnM > 800 → 10 s`, `250–800 → 5 s`, `<250 → 2.5 s`,
  and force the fast interval whenever `isOffRoute` so return-to-route is
  still detected quickly. Speed-based approach leads (~50 s ≈ 500 m at
  36 km/h) are far larger than one coarse step, so no missed announcements.
  Only re-request when crossing a threshold (not per fix; takes a second or
  two to take effect). Unit-test the pure decision function; real tuning on
  the physical phone with `dumpsys battery` before/after.

## Status

- **M1–M4 done & verified**, M5 polish largely shipped. 67 unit tests +
  instrumented ghost-ride tests pass on Capy17 (Android 17 AVD) via
  `./gradlew :app:connectedDebugAndroidTest`.
- Shipped: TrainingHud → compact 2×2 top-left HUD (speed | distance covered /
  average | distance remaining) with a north-up next-turn junction preview
  (real MapLibre snapshot zoomed to the route-ahead, matching the main map's
  style/tiles/brightened roads, rendered once per turn; direction arrow +
  route line drawn in geo-projected positions); POI waypoints
  (`<wpt>` parsing + `PoiTracker` route projections); dark/light map toggle
  (dark default for OLED); StubHrProvider hooked into the service loop.
- Turn guidance: speed-aware advance + near-turn notices that also announce the
  following turn, "go on for x.x km" heads-up, off-route / back-on-route prompts,
  and gentle **turn beeps** that shorten as the turn nears (decoupled from the
  popup via a `turnActive` flag; volume slider, 0 = off).
- Settings: toggles for next-turn popup, per-second live notification, and audio
  ducking; separate **turn-beep** and **navigation-voice** volume sliders;
  saved-routes manager; ghost-ride launcher; about + open-source licenses.
- Lock-screen notification: refreshes every second with next-turn guidance
  ("500 m left · 32.0 km/h", includes the following turn when near).
- Distances phrased as `m`/`km` (e.g. `400 m`, `2.6 km`) everywhere, including
  the HUD and notification.
- Camera controls: zoom +/− FABs; route auto-fits to bounds on load (and on style
  reload); riding keeps follow-zoom. `reset()` on RideStore; `mapVisible` drives
  UI redraws (headless = zero map work).
- **QA**: fixed missing `getMapAsync { map = it }` — without it MapLibre never
  handed over the map object, so `setStyle` never ran and the map showed only its
  default background. Now loads OpenFreeMap style + tiles (`Mbgl-HttpRequest 200`).
- **QA**: the junction preview is now a real `MapSnapshotter` render (offscreen
  MapLibre map, same style + `roadBrightenOverrides` as the main map) instead of
  a hand-drawn canvas, so roads/labels reach the card borders and match the big
  map. Fixed a top-to-bottom mirror (north was drawn at the bottom) — the
  projection now maps north upward and the rider arrow shares that orientation.
- **Route pre-cache**: `RouteCache` pre-renders turn preview images (PNG + 3×3
  anchor grid) and warms the global corridor tile cache when a route is loaded
  (prompt shown once per route). On the ride the HUD reads cached images via
  `TurnProjection` bilinear interpolation — zero tile fetches, zero GL work per
  turn during the ride. Misses (reverse direction, different style) fall back to
  the live `MapSnapshotter` path transparently. 5 JVM unit tests added for the
  pure projection math.

## Next (M5 polish)

- Real-ride tuning on the physical phone: beep/nav volumes, turn-window timing,
  pre-cache feedback.
- Edge cases: import a malformed GPX, empty route, repeated rides after reset.

## Verification

- `./gradlew assembleDebug testDebugUnitTest` green.
- `./gradlew :app:connectedDebugAndroidTest` green on AVD + physical phone.
- `adb shell dumpsys battery` before/after a headless run.

## License

Code: Apache-2.0 (to be added). Brand assets are owned by Crazy Capy
(`zingo@crazycapy.com`) and licensed to this app by their owner.