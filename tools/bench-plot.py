#!/usr/bin/env python3
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
"""bench-plot.py — render the cache/render benchmark tables and charts.

Reads the committed aggregate docs/power/cache-bench.csv (or a raw
power-runs/bench/bench-matrix.csv, which is aggregated on the fly) and writes
docs/power/cache-bench.html with per-screen tables and SVG bar charts.
"""
import csv
import statistics
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else "docs/power/cache-bench.csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/power/cache-bench.html"
ORDER = [("cold", "maptiles(render) · turnpreview(render)"),
         ("warm", "maptiles(pre-cache) · turnpreview(render)"),
         ("full", "maptiles(pre-cache) · turnpreview(pre-cache)")]
COLORS = {"cold": "#d62728", "warm": "#1f77b4", "full": "#2ca02c"}


def load():
    rows = list(csv.DictReader(open(SRC)))
    if not rows:
        return []
    if "energy_j_op" in rows[0]:
        return [dict(screen=r["screen"], variant=r["variant"], n=int(r["n"]),
                     jop=float(r["energy_j_op"]), jop_sd=float(r["energy_j_op_sd"]),
                     cpop=float(r["cpu_ms_op"]), cpop_sd=float(r["cpu_ms_op_sd"]),
                     w=float(r["avg_w"]), idle=float(r["idle_w"])) for r in rows]
    # raw matrix -> aggregate
    def f(r, k):
        try: return float(r[k])
        except (KeyError, ValueError): return None
    idle = {s: statistics.mean([f(r, "avg_w") for r in rows if r["screen"] == s and r["variant"] == "idle"])
            for s in ("on", "off")}
    out = []
    for s in ("on", "off"):
        for v in ("cold", "warm", "full"):
            rr = [r for r in rows if r["screen"] == s and r["variant"] == v and (f(r, "ops") or 0) > 0]
            j = [f(r, "wh") * 3600 / f(r, "ops") for r in rr]
            c = [f(r, "cpu_ms") / f(r, "ops") for r in rr]
            w = [f(r, "avg_w") for r in rr]
            out.append(dict(screen=s, variant=v, n=len(rr), jop=statistics.mean(j),
                            jop_sd=statistics.pstdev(j), cpop=statistics.mean(c),
                            cpop_sd=statistics.pstdev(c), w=statistics.mean(w), idle=idle[s]))
    return out


def bar_svg(title, unit, labels, values, colors, width=820, height=300):
    pad_l, pad_b, pad_t = 70, 60, 64
    pw, ph = width - pad_l - 20, height - pad_b - pad_t
    vmax = max(values) or 1
    n = len(values)
    bw = pw / n * 0.6
    p = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" font-family="system-ui,sans-serif">',
         f'<text x="{width/2}" y="24" text-anchor="middle" font-size="16" font-weight="600">{title}</text>',
         f'<line x1="{pad_l}" y1="{pad_t}" x2="{pad_l}" y2="{pad_t+ph}" stroke="#999"/>',
         f'<line x1="{pad_l}" y1="{pad_t+ph}" x2="{pad_l+pw}" y2="{pad_t+ph}" stroke="#999"/>']
    for i in range(5):
        y = pad_t + ph - ph * i / 4
        p.append(f'<line x1="{pad_l}" y1="{y:.1f}" x2="{pad_l+pw}" y2="{y:.1f}" stroke="#eee"/>')
        p.append(f'<text x="{pad_l-8}" y="{y+4:.1f}" text-anchor="end" font-size="11" fill="#555">{vmax*i/4:.4g}</text>')
    for i, (label, val, col) in enumerate(zip(labels, values, colors)):
        x = pad_l + pw * (i + 0.5) / n - bw / 2
        h = ph * val / vmax
        y = pad_t + ph - h
        p.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bw:.1f}" height="{h:.1f}" fill="{col}"/>')
        p.append(f'<text x="{x+bw/2:.1f}" y="{y-4:.1f}" text-anchor="middle" font-size="12">{val:.3g}</text>')
        for li, ln in enumerate(label.split(" · ")):
            p.append(f'<text x="{x+bw/2:.1f}" y="{pad_t+ph+15+li*12}" text-anchor="middle" font-size="10">{ln}</text>')
    p.append(f'<text x="18" y="{pad_t+ph/2}" text-anchor="middle" font-size="12" transform="rotate(-90 18 {pad_t+ph/2})">{unit}</text>')
    p.append("</svg>")
    return "".join(p)


def main():
    data = load()
    labels = [c for _, c in ORDER]
    colors = [COLORS[k] for k, _ in ORDER]
    h = ['<!doctype html><meta charset="utf-8"><title>Cache cost benchmark</title>',
         '<style>body{font:14px system-ui,sans-serif;margin:24px;color:#111}'
         'table{border-collapse:collapse;margin:10px 0 22px}th,td{border:1px solid #bbb;padding:4px 8px}'
         'th{background:#222;color:#fff}td.n{text-align:right;font-variant-numeric:tabular-nums}h2{margin-top:28px}</style>',
         '<h1>Cache / render cost benchmark (T20-mini)</h1>',
         '<p>Equal operation rate (10 ops/s), 400 operations per sample, samples spread across the route; '
         'screen on/off; lower is better.</p>',
         '<ul>'
         '<li><b>maptiles</b> — the map library\'s tile cache. <i>render</i> = emptied, tiles fetched/downloaded '
         'during the test; <i>pre-cache</i> = already downloaded and stored.</li>'
         '<li><b>turnpreview</b> — the small map picture at a turn. <i>render</i> = generated now, on the spot; '
         '<i>pre-cache</i> = generated earlier (the app\'s “Pre-cache” action), only decoded and shown now.</li>'
         '</ul>',
         '<p><b>Why this matters:</b> on a real ride the energy saved per preview is small next to the display, '
         'which dominates total draw. The larger practical wins of pre-caching are <b>much less CPU work</b> and '
         '<b>far fewer network/tile fetches</b> (a quieter radio, less data, and it keeps working offline) — '
         'which is what the per-operation numbers below show.</p>']
    for s in ("on", "off"):
        h.append(f"<h2>Screen {s}</h2>")
        h.append("<table><tr><th>variant</th><th>n</th><th>J/op</th><th>CPU ms/op</th><th>avg W</th><th>idle W</th></tr>")
        vals = {"jop": [], "cpop": [], "w": []}
        for key, label in ORDER:
            a = next((r for r in data if r["screen"] == s and r["variant"] == key), None)
            if not a:
                h.append(f'<tr><td>{label}</td><td colspan="5">no data</td></tr>')
                vals["jop"].append(0); vals["cpop"].append(0); vals["w"].append(0); continue
            h.append(f'<tr><td>{label}</td><td class="n">{a["n"]}</td>'
                     f'<td class="n">{a["jop"]:.4f} ± {a["jop_sd"]:.4f}</td>'
                     f'<td class="n">{a["cpop"]:.2f} ± {a["cpop_sd"]:.2f}</td>'
                     f'<td class="n">{a["w"]:.4f}</td><td class="n">{a["idle"]:.4f}</td></tr>')
            vals["jop"].append(a["jop"]); vals["cpop"].append(a["cpop"]); vals["w"].append(a["w"])
        h.append("</table>")
        h.append(bar_svg(f"Energy per operation — screen {s}", "J / op", labels, vals["jop"], colors))
        h.append(bar_svg(f"CPU per operation — screen {s}", "CPU ms / op", labels, vals["cpop"], colors))
        h.append(bar_svg(f"Average power at 10 ops/s — screen {s}", "W", labels, vals["w"], colors))
    open(OUT, "w").write("\n".join(h))
    print(f"wrote {OUT} from {len(data)} aggregate rows")


if __name__ == "__main__":
    main()
