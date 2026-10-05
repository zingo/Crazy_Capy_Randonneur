# routes

GPX routes used by `tools/ghost-power-run.sh` for repeatable power runs.
Keeping them in the repo makes runs reproducible offline.

## `ystad-onnekoppinge-harlosa.gpx`

- Ystad–Önneköpinge–Harlösa, ~170.8 km / 1069 m climb, Skåne, Sweden.
- Source: RideWithGPS route [56494755](https://ridewithgps.com/routes/56494755).
- Exported from `https://ridewithgps.com/routes/56494755.json` (`track_points`),
  since the GPX export endpoint requires a login. 4235 points.

Regenerate (or fetch another public route) with:

```bash
python3 - <<'PY'
import json, html, urllib.request
rid = 56494755
d = json.load(urllib.request.urlopen(f"https://ridewithgps.com/routes/{rid}.json"))
pts = d["track_points"]
out = ['<?xml version="1.0" encoding="UTF-8"?>',
       '<gpx version="1.1" creator="ridewithgps" xmlns="http://www.topografix.com/GPX/1/1">',
       "  <trk>", f"    <name>{html.escape(d['name'])}</name>", "    <trkseg>"]
for p in pts:
    out.append(f'      <trkpt lat="{p["y"]:.6f}" lon="{p["x"]:.6f}"><ele>{p.get("e", 0):.1f}</ele></trkpt>')
out += ["    </trkseg>", "  </trk>", "</gpx>"]
print("\n".join(out))
PY
```
