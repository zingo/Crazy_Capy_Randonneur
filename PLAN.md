# Crazy Capy Randonneur — Plan

Open-source, voice-first, battery-extreme bike navigator for Android 13+.
Import a GPX/TCX route from popular cycling sites, start navigating, keep
the phone in the pocket with the screen off, and ride to spoken guidance.

## Vision

- Voice-first: spoken turn-by-turn directions ("Turn left in 200 metres") + beeps.
- Battery-extreme: headless screen-off navigation, adaptive GPS, no Play Services.
- Glanceable high-contrast screen when you look.
- No accounts, no ads, no telemetry.

## Decisions

- Target: Android 13+ — `minSdk = 33`, `targetSdk = compileSdk = 35`.
  (Lowered from 34 in 0.2.0; verified on a DOOGEE T20Mini, Android 13.)
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

- M1 — scaffold & toolchain: JDK 21 / AGP 8.x / platform 35; project boots;
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

- **Radar target world-motion (maybe not needed).** Today live radar targets
  are projected to absolute lat/lon once per ~5 Hz snapshot and frozen between
  samples, so they don't rotate with the bike. We don't yet derive each
  target's own world direction/speed from consecutive positions (no heading
  arrow, no extrapolation). Low value if the radar is fast enough already —
  and any per-frame dead-reckoning costs battery, so guard it behind the same
  "freeze-then-snap" approach rather than a continuous projection loop. A
  cheaper fallback worth considering first: keep the last snapshot's data and
  only use it to prolong/dead-reckon the fading targets when the stream
  vanishes, instead of computing motion for every frame.

## Status

- **Release:** `v0.2.0` (2026-10-11) — see the README [Releases](README.md#releases)
  section. `versionName = "0.2.0"`, `minSdk = 33` (Android 13+).
- **M1–M4 done & verified**, M5 polish largely shipped. 199 unit tests +
  instrumented ghost-ride tests pass on Capy15 (Android 15 AVD) via
  `./gradlew :app:connectedDebugAndroidTest`.
- Shipped: TrainingHud → compact 3×2 top-left HUD (speed | covered | elapsed /
  avg | left | tap-to-cycle ETA/left/total) with a north-up next-turn junction preview
  (real MapLibre snapshot zoomed to the route-ahead, matching the main map's
  style/tiles/brightened roads, rendered once per turn; direction arrow +
  route line drawn in geo-projected positions); POI waypoints
  (`<wpt>` parsing + `PoiTracker` route projections, `<desc>/<cmt>` text kept on
  the model); **checkpoint display** (named markers + labels on the map, tap to
  read the checkpoint text; HUD distance cell switches to km-left / km-CP-x
  toward the next checkpoint and the ETA cycle gains an ETA-CP mode); dark/light
  map toggle (dark default for OLED); StubHrProvider hooked into the service
  loop.
- **Rear radar**: **live-radar** integration with android-bike-radar-overlay,
  built on that app's published AIDL contract (six Apache-2.0 files copied in
  verbatim), with `RadarClient` consuming the stream onto the same map layer and
  a HUD `RadarStatusBar` (battery %, connected state, tail-light toggle). The
  rider allows this app on the overlay app's own screen first; until then the
  bar carries that prompt and no radar data arrives. ghost-ride `RadarSimulator`
  (fake cars/trucks/bikes overtaking
  from 175 m behind, drawn as coloured dots behind the rider, toggle persisted)
  plus an optional. **Radar-lost handling**: a dropped stream fades the last
  targets out over 20 s with a red blinking ring and shows a red blinking
  triangle at the rider pointing back, while the voice says "Radar lost" three
  times (5 s apart) and "Radar back" once on reconnect. The ghost sim feeds
  through the same `RadarClient` path so the dropout is testable without a real
  radar (radar on/off button in the ghost controls). See
  `Integration_android-bike-radar-overlay.plan`.
- **Ghost-radar path-following**: the ghost-ride `RadarSimulator` now rides the
  route's actual geometry (via `Track.pointAtDistance`) with a ±10 m lateral
  offset, so simulated traffic hugs the road through corners instead of
  projecting straight back along a bearing. A "Show ghost controls" setting
  hides the in-ride speed/scale/radar buttons for clean captures.
- **RWGPS import**: `RwGpsImport` fetches a route from its public JSON
  (`routes/<id>.json`) and `RwGpsParser` rebuilds it as a `Track`, lifting the
  route's POIs (incl. brevet `control` POIs) into waypoints — so checkpoints
  survive even though the GPX download omits them. A user profile
  (`users/<id>/routes.json`) lists public routes to pick from; both are exposed
  in the Routes dialog ("RWGPS"), no account or premium required.
- Turn guidance: speed-aware advance + near-turn notices that also announce the
  following turn, "go on for x.x km" heads-up, off-route / back-on-route prompts,
  and gentle **turn beeps** that shorten as the turn nears (relaxed 2 s minimum
  interval, 150 m window; decoupled from the popup via a `turnActive` flag;
  volume slider, 0 = off).
- Settings: toggles for next-turn popup, per-second live notification, and audio
  ducking; separate **turn-beep** and **navigation-voice** volume sliders;
  saved-routes manager; ghost-ride launcher; about + open-source licenses.
- Lock-screen notification: refreshes every second with next-turn guidance
  ("500 m left · 32.0 km/h", includes the following turn when near).
- Distances phrased as `m`/`km` (e.g. `400 m`, `2.6 km`) everywhere, including
  the HUD and notification.
- Camera controls: pinch to zoom (no on-screen zoom buttons); a bottom-centre
  scale bar sized from MapLibre's `Projection.getMetersPerPixelAtLatitude`, and
  a top-right compass that appears only when rotated and snaps back to north-up
  when tapped. Route auto-fits to bounds on load (and on style
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
- **Pre-cache speed/robustness**: per-use snapshot timeouts (90 s turn renders,
  20 s corridor warming) instead of one shared 45 s budget; a fresh
  `MapSnapshotter` per snapshot; an in-run retry pass for turns that timed out on
  the first attempt; and an honest end-of-run status ("Pre-cached 34/347 · rest
  on next load") when some turns were skipped. Later loads resume where a run
  left off. Removed the shared snapshotter (reuse after `start` is unsafe).
- Test helpers fixed this pass: `Geo.pointSegmentDistance` now scales longitude
  by `cos(latitude)` (regression test added), and `RouteSimulator.speedKmh` /
  `timeScale` are `@Volatile`.
- Offline navigation verified on the emulator with the app's network denied at
  the package level (`cmd connectivity set-package-networking-enabled false`):
  cached turn previews + tiles render with zero network requests.
- **Offline pack identity & manager**: map packs carry JSON metadata
  (`routeId`, `routeName`, `trackHash`, style/theme, downloaded bytes,
  `createdAt`); re-preparing reuses a pack only when route + geometry hash +
  style match, and stale regions for the same route are removed instead of
  stacking. Turn/overview previews keep a per-route hash marker, so an edited
  route regenerates snapshots while partial runs still resume. Settings →
  **Offline map packs** lists every region (name, theme/style, size, date,
  status) with delete-one and delete-all.
- **Corridor segments & two-tier detail**: a route pack is split into ~10 km
  corridor segments (1.5 km padding) with adjacent boxes merged when they
  mostly overlap or their union is cheap. Each segment is its own MapLibre
  offline region with `routeId`, `trackHash`, style/theme, `strategy`,
  `segmentIndex` and `segmentCount` metadata; completed segments are reused,
  stale ones replaced, and cancel removes all partial regions for the run.
  Standard detail uses zoom 8–14 over the whole corridor; turn-focused uses
  8–12 over the whole route plus zoom 13–14 in 1 km boxes around detected
  turns. The prepare dialog offers the strategy (remembered in Settings →
  Offline map detail), and the manager groups a route's segments by strategy
  and style, showing segment count and summed bytes. Segmentation and turn-box
  math are pure Kotlin with unit tests; legacy metadata-less packs still list
  and delete.
- **Background prepare, pause and resume**: preparation is hosted by a
  `dataSync` foreground service, so closing the prepare dialog or leaving the
  app does not cancel it. A low-importance "Offline preparation" notification
  shows the current phase and progress and offers Pause/Resume and Cancel;
  pause stops the active MapLibre segment and preview renderer without deleting
  completed work, and Resume reuses finished segments, deletes/re-fetches the
  interrupted one, and skips cached previews. Cancel keeps the existing
  semantics (stop and delete partial map regions). The dialog reflects
  paused/background progress and closing it only hides it. Phase/percent
  formatting and pause/resume/cancel transitions are pure Kotlin with unit
  tests.
- **Auto-prune and process-death recovery**: an optional setting (default off)
  removes expired offline pack groups once at app start using the existing
  expiry metadata, never touching unexpired or legacy packs, and reports the
  count in the manager. Prepare state (route id/hash, strategy, both-themes) is
  persisted so a unique WorkManager recovery job with a network constraint
  resumes an interrupted prepare after process death (and on next launch),
  skipping already-complete segments/previews. Serialization and prune
  decisions are pure Kotlin with unit tests.
- **Both map themes, size estimate and limits**: the prepare dialog can also
  download the other map theme (last choice remembered; Settings → Offline map
  theme offers Off/Always/Ask). Each theme creates its own metadata-tagged
  regions with the same route/hash/strategy/segments, so dedupe and reuse stay
  per theme; deleting a saved route removes both themes. The dialog shows a
  documented conservative estimate (tile counts by corridor bounds and zoom
  range, 128 kB per tile). Settings store a storage cap (MB, 0 = unlimited) and
  pack expiry (days, 0 = never); a prepare that would exceed the cap is blocked
  with a clear message and never deletes packs. The manager shows total vs cap,
  age and expired flags with **Delete expired**. Cap/expiry decisions and the
  estimator are pure Kotlin with unit tests.
- **Stable offline identity and dedupe-aware cap**: packs and turn-preview
  caches now use the saved route's stable storage id, so renaming a route
  reuses its pack/previews instead of orphaning them; ad-hoc tracks fall back to
  a geometry-derived content id. Legacy packs keyed by the old name-derived id
  are still listed/deletable and are reused and re-tagged by geometry hash on
  the next prepare. The storage cap subtracts bytes that the prepare will
  replace (same route/hash family, strategy and theme) before adding the
  estimate, and the over-cap warning reports the reclaimable amount; the
  manager still totals actual sizes. Identity/reclaim/migration decisions are
  pure Kotlin with unit tests.
- **Advanced tuning, estimate range and manager refresh**: persisted settings
  tune segment length (2–50 km, default 10), corridor padding (0.5–10 km,
  default 1.5), high-detail turn boxes (0.25–5 km, default 1) and the
  high-detail zoom ceiling (13–16, default 14; standard keeps 8–max). The
  sanitized tuning flows through segmentation, turn boxes, estimator and region
  metadata as a signature, so packs from a different tuning become stale and
  are replaced on the next prepare while unrelated packs stay. The prepare
  dialog shows a low–high estimate range labelled as an estimate and the active
  tuning; cap checks use the high end. The manager groups by tuning and theme,
  shows the tuning summary plus total vs cap, labels metadata-less regions
  **Legacy / unknown** (delete only), offers **Refresh** for groups whose route
  is still in the library (deletes that group, then re-prepares its strategy,
  tuning and style within the cap without duplicates), and shows **Route not
  found** otherwise. Tuning/signature, grouping and refresh/reclaim decisions
  are pure Kotlin with unit tests.
- **BLE heart rate**: the `ble` package now has a real standard Heart Rate
  Service client (`0x180D` / measurement `0x2A37`, notifications), a pure
  flags/8–16-bit parser, scan filtering by service, device picker with runtime
  Bluetooth permissions, connection status/error, persisted last strap and a
  battery-friendly reconnect backoff (5/15/30/60 s, then stop). The foreground
  navigation service declares `location|connectedDevice` and writes available
  bpm into `RideStore.hr`, so saved GPX/TCX rides and history keep the existing
  HR path. Debug builds expose `SET_HR` to inject bpm over adb where no BLE
  hardware exists. Real straps still need physical BLE hardware for validation.
- **QA (checkpoint markers)**: a checkpoint whose GPX `<wpt>` has no `<desc>`
  used to produce malformed GeoJSON (a stray quote), so MapLibre rejected the
  whole POI source and the markers silently vanished while the HUD's km-CP kept
  working. Markers now use a valid hand-built FeatureCollection fed through
  `setGeoJson` (labels are pre-rendered icon bitmaps — text-on-geojson glyphs
  blank the layer on some GL stacks), and the tap popup uses a zoom-aware
  tolerance (25 px, clamped 60–2000 m) so it opens at route-fit zoom too.
  Verified on the phone (Pixel 7 Pro) and emulator.

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
