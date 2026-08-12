---
name: update-docs
description: Use when the user asks to update the README/screenshots or keep docs in sync with feature changes. Covers refreshing screenshots on a device/emulator, rewriting the README screenshot table and feature bullets, and syncing PLAN.md status.
---

# Keep README, screenshots, and PLAN.md in sync

When features change, update the docs so they describe the current app.

## Screenshots

Fresh screenshots belong in `docs/screenshots/` with the `YYYY-MM-DD-…` naming
convention (stale captures are easy to spot). The README embeds them via
markdown image links in a table under `## Screenshots`.

Capture on the emulator or phone:

```bash
# Install the latest build first (see the flash command).
# Drive the UI with adb and capture whatever is on screen:
adb -s <serial> exec-out screencap -p > docs/screenshots/YYYY-MM-DD-NN-name.png
```

The UI is driven via `adb shell input tap|swipe` and inspected with
`adb shell uiautomator dump /sdcard/ui.xml`. Useful states to capture:
- Route loaded on the map (import `app/src/androidTest/assets/ghost_ride.gpx`)
- Start ride dialog (reverse direction option)
- Settings dialog (volume sliders, ghost ride launcher)
- Ghost ride with the HUD and turn preview visible
- Lock-screen / notification shade (expand via
  `adb shell cmd statusbar expand-notifications`)
- Mid-ride resume offer (stop the ride)
- Saved routes dialog

Delete old screenshots the README no longer references.

## README.md

- The `## Screenshots` table: replace with the new images, keeping 2 rows of 3
  cells as it is today. Captions should describe what each shows.
- Feature bullets at the top: add/remove/rewrite to match current behavior
  (HUD layout, turn preview, beeps, volumes, notification, settings).
- The `Run` / `Manual / on-device` / `Project layout` sections: update any
  wording that no longer matches (e.g. where ghost ride launches from, what
  Settings contains).

## PLAN.md

Keep the `## Status` section current: test counts, shipped features, QA notes.
The `## Next` section holds remaining polish items. Both should stay consistent
with README.md and the actual code.

## Verify

- Every image referenced in README.md must exist:
  `grep -oE 'docs/screenshots/[^)]+' README.md` then check the files.
- No stale wording: grep for old feature names that no longer apply.
