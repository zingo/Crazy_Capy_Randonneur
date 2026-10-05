# Developer tools

Developer-only helpers. Nothing here ships in the APK. This is the index and
reference for everything under `tools/` and the agent commands/skills under
`.agents/`.

- [Power measurement toolkit](#power-measurement-toolkit)
  - [The meter](#the-meter)
  - [`avhzy-monitor.py`](#avhzy-monitorpy)
  - [`ghost-power-run.sh`](#ghost-power-runsh)
  - [`power-report.sh` and baselines](#power-reportsh-and-baselines)
  - [Consistency and trust](#consistency-and-trust)
  - [Meter protocol notes](#meter-protocol-notes)
- [Emulator helper](#emulator-helper)
- [Agent commands and skills](#agent-commands-and-skills)
- [Adding a tool](#adding-a-tool)

---

## Power measurement toolkit

Compare battery/power draw between app builds, settings or other apps. The
meter sits inline between a USB power source and the device under test; the
scripts drive a repeatable ghost ride on the device while logging power.

### The meter

An AVHzY / YK-Lab **"Shizuku"** USB power meter (Korona YK003C, CT-3, Power-Z
KT002, …). It exposes a USB-CDC serial port (`/dev/ttyACM0`) and streams
~1 kHz samples; power is `voltage × current` and timestamps come from the
meter's own microsecond clock. `avhzy-monitor.py` auto-detects it by device name
(`korona`/`yk003c`/`shizuku`), falling back to the first `/dev/ttyACM*`; override
with `--port`. Requires `pyserial`.

Recordings land in the git-ignored `power-runs/` (raw) with the condensed set in
`power-runs/condensed/`.

### `avhzy-monitor.py`

The recorder and analysis engine.

```bash
# Record for 60 s to a CSV under power-runs/
./tools/avhzy-monitor.py record --label baseline --duration 60

# Compare runs side by side (label, start, screen %, avg/median/max W, mWh,
# mWh/h, and % change vs the baseline)
./tools/avhzy-monitor.py compare power-runs/condensed/*.csv --baseline baseline

# Prefix windows: the first 10 min / 30 min / 2 h / 4 h of a longer run
./tools/avhzy-monitor.py windows power-runs/<run>.csv \
    --durations 600,1800,7200,14400 --labels nano,small,medium,long
```

Subcommands: `record`, `compare`, `windows`, `report`, `diff`, `promote`,
`condense`.

`record` prints a live `V / A / W` line to stderr; the summary and CSV path go
to stdout. `--warmup SEC` (default 1) discards the start. CSV columns:
`timestamp_us,elapsed_ms,voltage_v,current_a,power_w`, with `#` header lines
carrying label/note/port/start time and run metadata (preset, screen %, speed,
start km, map mode, brightness, battery, device).

**Condensed output.** At 1 kHz a raw run is ~45 KB/s (~2.7 MB/min, ~1.4 GB for
an 8.5 h `ultra`). Pass `--chunk SECONDS` (default **300**) to `record` and it
still samples at 1 kHz but writes one row per chunk of `n, sum_w, sum_w²,
min_w, max_w, on_n, sum_v, sum_a, wh` — so peaks are kept and mean/sd/min/max/
energy are exact. 5 min divides every preset window (fast 5, nano 10, small 30,
medium 120, long 240 min) evenly, so window boundaries land on chunk boundaries
and the windowed numbers come out the same as raw; files shrink ~1000×
(~1 KB per 10 min). `--chunk 0` writes raw 1 kHz rows as before. `condense`
shrinks existing raw CSVs the same way (`condense 'power-runs/*.csv'`). All
analysis (`compare`, `windows`, `report`) reads either format transparently.
Mean, min, max, energy and on % are exact for condensed files; the **median is
not recoverable** (a duty-cycled median needs the per-sample distribution) and
shows as `-`.

`compare` columns include `mWh` (total energy) and `mWh/h` (mean power × 1000,
time-normalised so different-length runs compare directly), plus `vs base`.

### `ghost-power-run.sh`

Automates a full run: pushes a route, brings the app to the foreground, starts a
ghost ride over adb, records power for a fixed duration while cycling the screen
to a target on-percentage, then stops and prints the report.

Presets set ride length, screen-on percentage and ghost speed. All but `fast`
use 15% screen at 20 km/h, so a longer run's prefix windows line up with the
shorter presets:

| preset | ride | screen on | speed |
|---|---|---|---|
| `fast` | 5 min | 100% | 40 km/h |
| `nano` | 10 min | 15% | 20 km/h |
| `small` | 30 min | 15% | 20 km/h |
| `medium` | 2 h | 15% | 20 km/h |
| `long` | 4 h | 15% | 20 km/h |
| `ultra` | whole route | 15% | 20 km/h |

```bash
tools/ghost-power-run.sh --label baseline --preset fast      # quick smoke test
tools/ghost-power-run.sh --label medium-run --preset medium  # 2 h, 15% screen
tools/ghost-power-run.sh --label whole-route --preset ultra  # ~8.5 h
tools/ghost-power-run.sh --label custom -d 900 --screen-percent 25 --light
tools/ghost-power-run.sh --label x --preset ultra --dry-run  # preview, no run
```

Key flags: `--preset`, `--duration`, `--screen-percent`, `--screen-period`,
`--start-km`, `--brightness`, `--volume`, `--chunk`, `--time-scale`, `--speed`,
`--reverse`, `--light`, `--device SERIAL`, `--pin`, `--wait-charge`,
`--allow-charging`, `--out DIR`, `--no-compare`, `--dry-run`.

Default route: `tools/routes/ystad-onnekoppinge-harlosa.gpx` (~170 km,
exported from RideWithGPS). Every run starts at the same point along the route
(`--start-km N`, default `0`), so the map draws the identical area between runs.

Notes:
- Requires the **debug APK**: it uses `DebugControlReceiver`, a debug-only adb
  hook (`app/src/debug/`) that loads a GPX and starts/stops a ghost ride. It is
  absent from release builds.
- **Unlocking.** A secure lock screen shows after wake, hiding the app and
  blocking the foreground-service start/stop. The script adds the app to the
  battery-optimization allowlist
  (`dumpsys deviceidle whitelist +com.crazycapy.randonneur`), which alone lets
  the ride run while locked. To have the screen-on phase actually show the app,
  either set the device's **Screen lock to None** (best for a dedicated test
  device) or pass a PIN with `--pin 1234` / `SCREEN_PIN=1234` (a pattern lock
  can't be entered this way).
- `--screen-percent 100` keeps the screen on; `0` keeps it off. Screen off is
  the biggest single lever on power, so test both.
- **Brightness** is forced to manual mode and set to `--brightness PCT`
  (percentage 0-100, default **70%** — a sensible daylight/riding level) via
  `settings put system screen_brightness`. Pass `--brightness off` to leave the
  device's current setting alone. The applied value is recorded in the report's
  `bright` column.
- **Volume** (the `STREAM_MUSIC` the TTS voice and beeps use) is set once at the
  start and then left alone, and recorded in the `vol` column. Default **20%**
  (= 3/15 on this device); override with `--volume PCT` (0-100) or `--volume
  off` to leave the device untouched. adb's `--set` is a no-op here, so volume
  is set by paced volume key events until the target index is reached.
- The recording stops the moment the ride time is up, so wake/stop overhead
  doesn't dilute the measurement.

### `power-report.sh` and baselines

`tools/power-baseline.md` (+ `.txt`) is the committed reference table.
`tools/power-report.sh` builds a local report, merges it into the baseline (so
it stays a full table), diffs the two, and can promote the local report to
become the new baseline — all driven off the `.md` files:

```bash
tools/power-report.sh                                  # all of power-runs/condensed
tools/power-report.sh 'power-runs/condensed/*nano*'    # just nano runs
tools/power-report.sh 'power-runs/condensed/*' --promote   # accept as new baseline
```

Under the hood (all in `avhzy-monitor.py`):

```bash
avhzy-monitor.py report power-runs/condensed/*.csv -o power-runs/latest.md --merge tools/power-baseline.md
avhzy-monitor.py diff tools/power-baseline.md power-runs/latest.md
avhzy-monitor.py promote power-runs/latest.md tools/power-baseline.md
```

The report is written as both `power-runs/latest.md` and `power-runs/latest.txt`.
Rows are sorted by label and carry no timestamps, so `diff` shows only real
changes (added / removed / changed, with Δ W and Δ %). The `on %` column is the
fraction of the run measured above 1 W; it should track the `screen` target and
reveals harness glitches (e.g. a screen that never slept reads ~100%). The report
**auto-excludes any level whose `on %` is more than ~6 points off the target**, so
a drifted or charging-contaminated run is dropped from that level (but still
contributes to the shorter levels whose window was clean). The
`device` column is auto-detected (`ro.product.manufacturer` + `model`, Android
version) so runs from different phones/tablets stay distinguishable, and the
`bright` / `vol` columns record the screen brightness and media volume used.

**Repeated runs are grouped by label.** Run a scenario N times with the *same*
`--label` and the report collapses them into one row: `n` = number of runs,
`avg W` = mean, `sd W` / `cv %` = run-to-run spread. Use distinct labels
(`small-r1`, …) to keep them as separate rows.

**Runs accumulate by setup.** The report groups runs by their settings (device,
mode, screen %, brightness, speed, start — volume is recorded but not part of
the key) and emits one row per preset level (`fast`/`nano`/`small`/`medium`/
`long`/`ultra`). Every run long enough to cover a level contributes: its first N
seconds for a shorter level, or the whole run for the matching/longest one. So a
batch of `small` runs plus one `ultra` gives `nano`/`small` with
`n = small + ultra` while `medium`/`long` keep `n = medium` / `1`.

Keep the CSVs (don't delete them) and rerunning the report over them accumulates
more and more runs:

```bash
for i in $(seq 1 10); do tools/ghost-power-run.sh --label small --preset small; done
tools/power-report.sh 'power-runs/condensed/*.csv'    # nano/small/medium/... accumulate
```

### Findings (this device)

From the committed baseline and interleaved tests on the Wacom DTHA116:

- **Light vs dark map: no measurable difference.** An interleaved light/dark
  `nano` test gave 0.6201 vs 0.6202 W, and a matched pair of full 8.5 h `ultra`
  runs gave 0.5408 (dark) vs 0.5412 (light) W. The ~1–2% "dark is lower" gaps
  seen in non-interleaved tables are measurement drift, not the map style.
- **Typical draw** at 15% screen / 70% brightness / 20 km/h: ~0.54 W (`long`,
  `ultra`), ~0.55 W (`medium`), ~0.58–0.64 W (`small`, `nano`). 100%-screen
  `fast` (40 km/h): ~2.31 W. The display dominates (screen-off draw ≈ 0.1–0.3 W).
- **Spread:** cv 0.02–0.04% (`fast`), ~0.1% (`long`/`ultra`), ~2% (duty-cycled
  short runs).

To compare two builds, run the same preset(s) for both and `diff` the rows; a
difference under ~1% is noise unless it's a stable `fast`/`long` comparison.

### Consistency and trust

From the committed baseline: `fast` repeats are extremely tight (**cv 0.03%**),
the long levels settle to ~0.1% (`long`/`ultra`), while the duty-cycled short
levels (`nano`/`small`/`medium`) spread a few percent run-to-run. Rules of thumb:

- with a stable load (fixed brightness, same route start, screen always on, or a
  long duty-cycled run) a difference **< ~0.5%** is noise;
- for short duty-cycled runs (`nano`/`small`) **~2%** can be run-to-run spread —
  take several repeats and compare means;
- anything several percent off is real — but sanity-check `on %` first: a stuck
  screen (on % far from the target) is a harness glitch, not app behaviour. The
  harness verifies each screen-off actually slept (and retries) because
  `KEYCODE_SLEEP` occasionally fails to take; `on %` still reads a few points
  above the target from wake/settle latency (e.g. ~17% for a 15% target).
- **Long duty-cycled runs can drift.** On some devices the screen re-wakes during
  the off-phase over multi-hour runs, pushing `on %` up (observed ~32% on two of
  three `medium` runs). Always check `on %` against the target and discard runs
  that are far off before comparing `avg W`. `fast` (100% screen) is immune, and
  a clean full `ultra` is possible (the committed one ran at on % 16 throughout).

`fast` is 100% screen (a stable load); 15%-cycling runs may vary slightly more.

**Control the confounds.** The meter measures USB-side power, which includes the
device's battery charge/discharge — so absolute numbers drift with battery
level, temperature and screen brightness. For build-to-build comparisons keep
these constant:

- **The device must be net-discharging.** While it is charging, the USB input is
  power-capped, so the meter reads the charger, not the app: screen-off and
  screen-on power come out identical (e.g. 2.31 W both). Every run made while
  charging is invalid. Before each run the harness measures the screen-off draw
  and, by default, **waits** until it drops below 1.5 W (i.e. the device has
  settled to net-discharging), polling every 15 s, capped at `--wait-charge SEC`
  (default **3600**; `0` waits forever). `--no-wait` fails instead, and
  `--allow-charging` skips the check. `on %` also goes to ~100% when a capped
  run slips through.
- note the battery level and set brightness explicitly (`--brightness N`, both
  recorded in the CSV metadata); run from a stable supply (ideally full battery
  on charge, but a device that is net-discharging will drift).
- the harness runs `svc power stayon true` during a run so the screen timeout
  can't cut a `--screen-percent 100` run short (explicit `KEYCODE_SLEEP` still
  turns it off in the off-phase).
- compare runs made close together; a same-day, same-battery baseline is more
  trustworthy than one from hours earlier.

### Meter protocol notes

Frames are `A5 | int32-LE length | payload | XOR checksum | 5A`. A 28-byte
sample payload is `04 00 <reqId> 00`, float32 voltage, float32 current, 8
unknown bytes, then a uint64 microsecond timestamp. Commands used: stop
(`0x07`), reset record timer (`0x0C`), start sampling (`0x09`).

---

## Emulator helper

`tools/ensure-emulator.sh` reuses a running emulator when possible, otherwise
starts one (`Capy17`, 1536 MB / 1 core, max-nice'd) preferring a quickboot
snapshot. Resumes a paused guest before use.

```bash
tools/ensure-emulator.sh            # use/create emulator-5554
tools/ensure-emulator.sh emulator-5556
```

Env: `ANDROID_HOME` (default `~/Android/Sdk`), `CAPY_AVD` (default `Capy17`).

---

## Agent commands and skills

Reusable workflows live in `.agents/` and are triggered by shorthand:

| shorthand | what it does | files |
|---|---|---|
| `flash` / `/flash` | build the debug APK and install it on the device | `.agents/commands/flash.md`, `.agents/skills/flash/SKILL.md` |
| `test` / `/test` | run the test suites | `.agents/commands/test.md` |
| `pr` / `/pr` | push the current branch and open a PR against `main` | `.agents/commands/pr.md`, `.agents/skills/pr/SKILL.md` |

---

## Adding a tool

- Put scripts in `tools/` (`<name>.sh` or `<name>.py`), make them executable.
- Every source file carries the Apache-2.0 SPDX header (see `AGENTS.md`).
- Document the tool here, and keep `--help` accurate.
- Keep repo-run artifacts git-ignored (`power-runs/`, `__pycache__/`).
- Prefer small, composable scripts; the power stack separates *record*
  (`avhzy-monitor.py`), *orchestrate* (`ghost-power-run.sh`) and *report*
  (`power-report.sh`).
