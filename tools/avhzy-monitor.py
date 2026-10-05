#!/usr/bin/env python3
# Copyright (c) 2026 Crazy Capy Randonneur contributors
# SPDX-License-Identifier: Apache-2.0
#
# avhzy-monitor — record power from an AVHzY / YK-Lab "Shizuku" USB power
# meter (Korona YK003C, CT-3, Power-Z KT002, …) and compare runs.
#
# The meter exposes a USB-CDC serial port. Frames look like:
#   A5 | int32-LE payload length | payload | XOR checksum | 5A
# A sample payload is 28 bytes:
#   04 00 <reqId> 00 | float32 voltage | float32 current | 8 unknown | uint64 µs
# Power is voltage * current. Timestamps are the meter's own microsecond clock.
#
# Usage:
#   ./tools/avhzy-monitor.py record --label baseline --duration 60
#   ./tools/avhzy-monitor.py record --label "pre-cache off" --note "dark map" -d 30
#   ./tools/avhzy-monitor.py compare power-runs/*.csv
#
# Requires pyserial:  pip install pyserial

import argparse
import csv
import datetime
import glob
import os
import signal
import statistics
import struct
import sys
import time

try:
    import serial
except ImportError:
    sys.exit("pyserial is required: pip install pyserial")

BEGIN_DATA = 0xA5
END_DATA = 0x5A
CMD_STOP = 0x07
CMD_RESET_TIMER = 0x0C
CMD_START_SAMPLING = 0x09


def find_port():
    """Return the serial device for a Shizuku meter, or None."""
    candidates = []
    for link in sorted(glob.glob("/dev/serial/by-id/*")):
        name = os.path.basename(link).lower()
        if "korona" in name or "yk003c" in name or "shizuku" in name:
            candidates.append(link)
    candidates += sorted(glob.glob("/dev/ttyACM*"))
    return candidates[0] if candidates else None


class Meter:
    def __init__(self, port, timeout=0.1):
        self.ser = serial.Serial(port, 115200, timeout=timeout)

    def close(self):
        try:
            self.ser.close()
        except Exception:
            pass

    @staticmethod
    def _xor(data, start, end):
        value = 0
        for b in data[start:end + 1]:
            value ^= b
        return value

    def _frame(self, payload):
        frame = bytearray([BEGIN_DATA])
        frame += struct.pack("<i", len(payload))
        frame += payload
        frame.append(self._xor(frame, 5, len(frame) - 1))
        frame.append(END_DATA)
        return bytes(frame)

    def read_frame(self, timeout=2.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            b = self.ser.read(1)
            if not b:
                continue
            if b[0] != BEGIN_DATA:
                continue
            lb = self.ser.read(1)
            if not lb:
                continue
            length = lb[0]
            rest = self.ser.read(3 + length + 2)
            if len(rest) < 3 + length + 2:
                continue
            frame = b + lb + rest
            if frame[-1] != END_DATA:
                continue
            if self._xor(frame, 5, 4 + length) != frame[5 + length]:
                continue
            return frame
        return None

    def command(self, cmd, args=b""):
        payload = bytes([0x01, cmd, 0, 0]) + args
        self.ser.reset_input_buffer()
        self.ser.write(self._frame(payload))
        return self.read_frame(timeout=1.5)

    def stop(self):
        self.command(CMD_STOP)

    def reset_timer(self):
        self.command(CMD_RESET_TIMER)

    def start_sampling(self, interval_ms=1):
        self.command(CMD_START_SAMPLING, struct.pack("<I", interval_ms))

    def read_sample(self):
        frame = self.read_frame()
        if frame is None:
            return None
        length = frame[1]
        if length < 4 + 8 + 8:
            return None
        if frame[5] != 0x04 or frame[6] != 0x00:
            return None
        voltage = struct.unpack("<f", frame[9:13])[0]
        current = abs(struct.unpack("<f", frame[13:17])[0])
        ts_us = struct.unpack("<Q", frame[5 + length - 8:5 + length])[0]
        return voltage, current, voltage * current, ts_us


def cmd_record(args):
    port = args.port or find_port()
    if not port:
        sys.exit("No AVHzY/Shizuku meter found. Pass --port /dev/ttyACM0.")

    # Background jobs inherit SIGINT ignored from a non-interactive shell, so
    # accept SIGTERM too and turn both into a clean stop that still writes the CSV.
    def _stop(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y-%m-%d-%H%M%S")
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in args.label)
    path = os.path.join(out_dir, f"{stamp}-{safe}.csv")

    meter = Meter(port)
    try:
        meter.stop()
        meter.reset_timer()
        meter.start_sampling(args.interval_ms)
    except Exception as exc:
        meter.close()
        sys.exit(f"Failed to start sampling: {exc}")

    print(f"Recording from {port} → {path}")
    print(f"label: {args.label}" + (f"  note: {args.note}" if args.note else ""))
    print("Ctrl-C to stop." if args.duration <= 0 else f"Duration: {args.duration:g} s")

    # Stream rows straight to disk and keep only running aggregates: an 8 h run
    # is ~30M samples, far too much to hold in memory (it would be OOM-killed).
    start_wall = datetime.datetime.now()
    t0 = time.time()
    last_live = 0.0
    count = 0
    on_count = 0
    sum_v = sum_i = sum_p = sum_p2 = 0.0
    min_p = None
    max_p = None
    energy_wh = 0.0
    prev = None
    hist = {}
    bucket = 0.01
    base_ts = None
    first_out = last_out = None

    with open(path, "w", newline="") as fh:
        fh.write(f"# label,{args.label}\n")
        if args.note:
            fh.write(f"# note,{args.note}\n")
        fh.write(f"# port,{port}\n")
        fh.write(f"# started,{start_wall.isoformat(timespec='seconds')}\n")
        fh.write(f"# interval_ms,{args.interval_ms}\n")
        for kv in args.meta:
            key, _, val = kv.partition("=")
            fh.write(f"# {key.strip()},{val.strip()}\n")
        chunked = args.chunk > 0
        fh.write("elapsed_ms,chunk_ms,n,sum_w,sum_w2,min_w,max_w,on_n,sum_v,sum_a,wh\n"
                 if chunked else
                 "timestamp_us,elapsed_ms,voltage_v,current_a,power_w\n")
        writer = csv.writer(fh)
        cs = None; cn = 0; csw = csw2 = 0.0; cmin = cmax = None
        con = 0; csvv = csa = cwh = 0.0; cpe = None; cpp = 0.0

        def flush_chunk(end_e):
            if cn == 0:
                return
            writer.writerow([round(cs, 3), round(end_e - cs, 3), cn, f"{csw:.4f}",
                             f"{csw2:.4f}", f"{cmin:.5f}", f"{cmax:.5f}", con,
                             f"{csvv:.5f}", f"{csa:.5f}", f"{cwh:.6f}"])

        stalls = 0
        try:
            while True:
                sample = meter.read_sample()
                if sample is None:
                    # read_frame timed out (~2 s): tolerate a short gap rather
                    # than silently truncating a multi-hour run. Give up only
                    # after a long absence (device/meter gone).
                    stalls += 1
                    if stalls >= 15:
                        break
                    continue
                stalls = 0
                v, i, p, ts = sample
                if base_ts is None:
                    base_ts = ts
                if ts >= base_ts + args.warmup * 1_000_000:
                    if first_out is None:
                        first_out = ts
                    last_out = ts
                    e = (ts - base_ts) / 1000.0
                    if chunked:
                        if cs is None:
                            cs = e
                        cn += 1
                        csw += p
                        csw2 += p * p
                        cmin = p if cmin is None else min(cmin, p)
                        cmax = p if cmax is None else max(cmax, p)
                        con += 1 if p > 1.0 else 0
                        csvv += v
                        csa += i
                        if cpe is not None:
                            dt = (e - cpe) / 1000.0
                            if dt > 0:
                                cwh += (cpp + p) / 2.0 * dt / 3600.0
                        cpe = e
                        cpp = p
                        if e - cs >= args.chunk * 1000:
                            flush_chunk(e)
                            cs = None; cn = 0; csw = csw2 = 0.0
                            cmin = cmax = None; con = 0
                            csvv = csa = cwh = 0.0; cpe = None
                    else:
                        writer.writerow([ts, round(e, 3), f"{v:.5f}", f"{i:.5f}", f"{p:.5f}"])
                    count += 1
                    on_count += 1 if p > 1.0 else 0
                    sum_v += v
                    sum_i += i
                    sum_p += p
                    sum_p2 += p * p
                    min_p = p if min_p is None else min(min_p, p)
                    max_p = p if max_p is None else max(max_p, p)
                    if prev is not None:
                        dt = (ts - prev[3]) / 1_000_000.0
                        if dt > 0:
                            energy_wh += (prev[2] + p) / 2.0 * dt / 3600.0
                    prev = (v, i, p, ts)
                    hist[int(p / bucket)] = hist.get(int(p / bucket), 0) + 1
                elapsed = time.time() - t0
                if not args.quiet and time.time() - last_live >= 0.1:
                    last_live = time.time()
                    sys.stderr.write(f"\r  {elapsed:6.1f}s   {v:7.3f} V   {i:6.3f} A   "
                                     f"{p:7.3f} W   {count} samples")
                    sys.stderr.flush()
                if args.duration > 0 and elapsed >= args.duration:
                    break
        except KeyboardInterrupt:
            pass
        finally:
            if chunked and last_out is not None:
                flush_chunk((last_out - base_ts) / 1000.0)
            try:
                meter.stop()
            except Exception:
                pass
            meter.close()
            fh.flush()
            if not args.quiet:
                sys.stderr.write("\r" + " " * 78 + "\r")
                sys.stderr.flush()

    if count < 1:
        sys.exit("No samples captured.")

    # Median from a fine histogram (keeps memory flat over long runs).
    half = count // 2
    cum = 0
    median_w = 0.0
    for b in sorted(hist):
        cum += hist[b]
        if cum >= half:
            median_w = b * bucket
            break
    avg_p = sum_p / count
    sd_p = (max(sum_p2 / count - avg_p * avg_p, 0.0)) ** 0.5
    stats = {
        "samples": count,
        "duration_s": (last_out - first_out) / 1_000_000.0 if first_out else 0.0,
        "avg_v": sum_v / count,
        "avg_a": sum_i / count,
        "avg_w": avg_p,
        "median_w": median_w,
        "sd_w": sd_p,
        "min_w": min_p,
        "max_w": max_p,
        "energy_wh": energy_wh,
        "on_frac": on_count / count,
    }
    print_summary_stats(args.label, stats, path)


def print_summary_stats(label, s, path):
    print(f"\n{label}")
    if s["duration_s"] > 0:
        print(f"  {s['samples']} samples over {s['duration_s']:.2f} s "
              f"({s['samples'] / s['duration_s']:.0f} Hz, on {s['on_frac'] * 100:.0f}%)")
    else:
        print(f"  {s['samples']} samples")
    print(f"  voltage  avg {s['avg_v']:.4f} V")
    print(f"  current  avg {s['avg_a']:.4f} A")
    print(f"  power    avg {s['avg_w']:.4f} W   median {s['median_w']:.4f} W   "
          f"min {s['min_w']:.4f}   max {s['max_w']:.4f}")
    print(f"  energy   {s['energy_wh']:.4f} Wh")
    print(f"  saved    {path}")


def expand_paths(args):
    paths = []
    for a in args:
        if os.path.isdir(a):
            paths += sorted(glob.glob(os.path.join(a, "*.csv")))
        else:
            paths += sorted(glob.glob(a))
    return paths


def fmt_duration(seconds):
    if seconds >= 3600 and seconds % 3600 == 0:
        return f"{seconds // 3600}h"
    if seconds >= 60 and seconds % 60 == 0:
        return f"{seconds // 60}m"
    return f"{seconds}s"


def print_table_header():
    print(f"{'label':<34} {'dur s':>7} {'samples':>8} {'avg V':>8} {'avg A':>8} "
          f"{'avg W':>8} {'med W':>8} {'max W':>8} {'Wh':>8}")
    print("-" * 106)


def print_row(label, s):
    med = "-" if s["median_w"] is None else f"{s['median_w']:.4f}"
    print(f"{label[:34]:<34} {s['duration_s']:7.1f} {s['samples']:8d} "
          f"{s['avg_v']:8.4f} {s['avg_a']:8.4f} "
          f"{s['avg_w']:8.4f} {med:>8} "
          f"{s['max_w']:8.4f} {s['energy_wh']:8.4f}")


def cmd_compare(args):
    paths = expand_paths(args.files)
    if not paths:
        sys.exit("No CSV files matched.")

    runs = []
    for path in paths:
        meta, s, _ = scan_csv(path)
        if not s:
            continue
        runs.append((meta.get("label") or os.path.basename(path), meta, s))
    if not runs:
        sys.exit("No runs found.")

    if args.baseline:
        match = [r for r in runs if r[0] == args.baseline]
        base = match[0][2]["avg_w"] if match else runs[0][2]["avg_w"]
    else:
        base = runs[0][2]["avg_w"]

    print(f"{'label':<22} {'started':<19} {'scr%':>4} {'dur s':>7} {'samples':>8} "
          f"{'avg W':>8} {'med W':>8} {'max W':>8} {'mWh':>8} {'mWh/h':>8} {'vs base':>9}")
    print("-" * 126)
    for label, meta, s in runs:
        scr = meta.get("screen_percent", "-")
        started = meta.get("started", "")
        delta = (s["avg_w"] - base) / base * 100.0 if base else 0.0
        med = "-" if s["median_w"] is None else f"{s['median_w']:.4f}"
        print(f"{label[:22]:<22} {started:<19} {scr:>4} {s['duration_s']:7.1f} "
              f"{s['samples']:8d} {s['avg_w']:8.4f} {med:>8} "
              f"{s['max_w']:8.4f} {s['energy_wh'] * 1000.0:8.2f} {s['avg_w'] * 1000.0:8.1f} {delta:+8.1f}%")
    print("\nmWh = total energy; mWh/h = energy rate (avg W x 1000), time-normalised so "
          "runs of different lengths compare directly. 'vs base' compares avg W to the "
          f"baseline ({args.baseline or runs[0][0]}); negative = less power.")


def window_stats_for_run(path, durations, names):
    windows = [(name, d) for d, name in zip(durations, names)]
    meta, full, wins = scan_csv(path, windows=windows)
    if not full:
        return None, {}
    label = meta.get("label") or os.path.basename(path)
    out = dict(wins)
    out["full"] = full
    return label, out


def condense_file(src, dst, chunk_s):
    with open(src) as fh, open(dst, "w") as out:
        header_seen = False
        base = None
        rel = 0.0
        cs = None; cn = 0; csw = csw2 = 0.0; cmin = cmax = None
        con = 0; csvv = csa = cwh = 0.0; cpe = None; cpp = 0.0

        def flush(end_e):
            if cn == 0:
                return
            out.write(f"{cs:.3f},{end_e - cs:.3f},{cn},{csw:.4f},{csw2:.4f},"
                      f"{cmin:.5f},{cmax:.5f},{con},{csvv:.5f},{csa:.5f},{cwh:.6f}\n")

        for line in fh:
            if line.startswith("#"):
                out.write(line)
                continue
            if not header_seen:
                header_seen = True
                out.write("elapsed_ms,chunk_ms,n,sum_w,sum_w2,min_w,max_w,on_n,sum_v,sum_a,wh\n")
                continue
            p = line.split(",")
            if len(p) < 5:
                continue
            try:
                e = float(p[1]); v = float(p[2]); i = float(p[3]); w = float(p[4])
            except ValueError:
                continue
            if base is None:
                base = e
            rel = e - base
            if cs is None:
                cs = rel
            cn += 1; csw += w; csw2 += w * w
            cmin = w if cmin is None else min(cmin, w)
            cmax = w if cmax is None else max(cmax, w)
            con += 1 if w > 1.0 else 0
            csvv += v; csa += i
            if cpe is not None:
                dt = (rel - cpe) / 1000.0
                if dt > 0:
                    cwh += (cpp + w) / 2.0 * dt / 3600.0
            cpe = rel; cpp = w
            if rel - cs >= chunk_s * 1000:
                flush(rel)
                cs = None; cn = 0; csw = csw2 = 0.0; cmin = cmax = None
                con = 0; csvv = csa = cwh = 0.0; cpe = None
        if cn > 0:
            flush(rel)


def cmd_condense(args):
    paths = expand_paths(args.files)
    if not paths:
        sys.exit("No CSV files matched.")
    for path in paths:
        if ".chunk" in os.path.basename(path):
            continue
        out_dir = args.out or os.path.dirname(path) or "."
        os.makedirs(out_dir, exist_ok=True)
        base = os.path.basename(path)[:-4] if path.endswith(".csv") else os.path.basename(path)
        dst = os.path.join(out_dir, f"{base}.chunk{args.chunk:g}.csv")
        condense_file(path, dst, args.chunk)
        print(f"{path} -> {dst}")


def cmd_windows(args):
    paths = expand_paths(args.files)
    if not paths:
        sys.exit("No CSV files matched.")
    durations = [int(x) for x in args.durations.split(",") if x.strip()]
    names = args.labels.split(",") if args.labels else [fmt_duration(d) for d in durations]

    if not args.aggregate:
        print_table_header()
        for path in paths:
            label, wins = window_stats_for_run(path, durations, names)
            if not wins:
                continue
            for name, s in wins.items():
                print_row(f"{label} [{name}]", s)
        print("\nPrefix windows: each row is the first N seconds of the run.")
        return

    # Aggregate each (label, window) across runs that share a label.
    groups = {}
    order = []
    for path in paths:
        label, wins = window_stats_for_run(path, durations, names)
        if not wins:
            continue
        for name, s in wins.items():
            key = (label, name)
            groups.setdefault(key, []).append(s)
            if key not in order:
                order.append(key)

    print(f"{'label':<24} {'window':<8} {'n':>3} {'dur s':>7} {'samples':>8} "
          f"{'avg W':>8} {'sd W':>8} {'cv %':>6} {'med W':>8} {'max W':>8} {'mWh/h':>8}")
    print("-" * 116)
    for label, name in order:
        stats_list = groups[(label, name)]
        n = len(stats_list)
        avg = statistics.mean(s["avg_w"] for s in stats_list)
        sd = statistics.stdev([s["avg_w"] for s in stats_list]) if n > 1 else 0.0
        cv = sd / avg * 100.0 if avg else 0.0
        meds = [s["median_w"] for s in stats_list if s["median_w"] is not None]
        med = f"{statistics.mean(meds):.4f}" if meds else "-"
        print(f"{label[:24]:<24} {name:<8} {n:3d} "
              f"{statistics.mean(s['duration_s'] for s in stats_list):7.1f} "
              f"{sum(s['samples'] for s in stats_list):8d} "
              f"{avg:8.4f} {sd:8.4f} {cv:6.2f} "
              f"{med:>8} "
              f"{statistics.mean(s['max_w'] for s in stats_list):8.4f} {avg * 1000:8.1f}")
    print("\nPrefix windows aggregated across runs with the same label.")


# --- Reports and baselines ---------------------------------------------------
#
# A report is a plain markdown table (one row per run) that can be diffed
# against a committed baseline. Rows are sorted by label and carry no
# timestamps, so comparing two reports only shows what actually changed.

REPORT_HEADER = ["label", "device", "mode", "screen", "bright", "vol", "speed",
                 "start", "n", "dur s", "avg W", "sd W", "cv %", "on %", "med W",
                 "max W", "mWh", "mWh/h"]


STANDARD_LEVELS = [("nano", 600), ("small", 1800), ("medium", 7200), ("long", 14400)]


def _setup_key(meta):
    """Runs with the same settings accumulate together (differ only by length).

    Volume is recorded but deliberately not part of the key: small speaker-volume
    differences shouldn't split a setup (the voice/beeps are intermittent).
    """
    return (
        meta.get("device", "-"),
        meta.get("dark_map", "-"),
        meta.get("screen_percent", "-"),
        meta.get("brightness", "-"),
        meta.get("speed_kmh", "-"),
        meta.get("start_km", "-"),
        meta.get("route", "-"),
    )


def _mode_of(meta):
    if "dark_map" in meta:
        return "dark" if meta["dark_map"].lower() == "true" else "light"
    return "-"


def _on_valid(on_frac, screen_pct, delta=6.0):
    """Reject a level if its measured on-% is far from the screen target, which
    means the screen stuck on/off or the device started charging mid-run."""
    target = 100.0 if screen_pct >= 100 else screen_pct
    return abs(on_frac * 100.0 - target) <= delta


def _level_label(name, meta0):
    # Keep light/dark runs of the same level distinct in the accumulated table.
    mode = _mode_of(meta0)
    return f"{name} ({mode})" if mode != "-" else name


def _agg(label, items):
    """items = [(meta, stats)]; aggregate into a report row dict."""
    meta0 = items[0][0]
    stats_list = [s for _, s in items]
    n = len(stats_list)
    avg = statistics.mean(s["avg_w"] for s in stats_list)
    sd = statistics.stdev([s["avg_w"] for s in stats_list]) if n > 1 else 0.0
    cv = sd / avg * 100.0 if avg else 0.0
    if "dark_map" in meta0:
        mode = "dark" if meta0["dark_map"].lower() == "true" else "light"
    else:
        mode = "-"
    return {
        "label": label,
        "device": meta0.get("device", "-"),
        "mode": mode,
        "screen": meta0.get("screen_percent", "-"),
        "bright": meta0.get("brightness", "-"),
        "vol": meta0.get("volume", "-"),
        "speed": meta0.get("speed_kmh", "-"),
        "start": meta0.get("start_km", "-"),
        "n": n,
        "dur": statistics.mean(s["duration_s"] for s in stats_list),
        "avg": avg,
        "sd": sd,
        "cv": cv,
        "on": statistics.mean(s["on_frac"] for s in stats_list),
        "med": (statistics.mean([s["median_w"] for s in stats_list
                                 if s["median_w"] is not None])
                if any(s["median_w"] is not None for s in stats_list) else None),
        "max": statistics.mean(s["max_w"] for s in stats_list),
        "mwh": statistics.mean(s["energy_wh"] * 1000.0 for s in stats_list),
        "mwhh": avg * 1000.0,
    }


def _new_acc():
    return {"n": 0, "on": 0, "sv": 0.0, "si": 0.0, "sp": 0.0, "sp2": 0.0,
            "min": None, "max": None, "energy": 0.0, "prev": None, "prev_p": 0.0,
            "hist": {}, "first": None, "last": None}


def _upd(a, v, i, p, e):
    if a["first"] is None:
        a["first"] = e
    a["last"] = e
    a["n"] += 1
    a["on"] += 1 if p > 1.0 else 0
    a["sv"] += v
    a["si"] += i
    a["sp"] += p
    a["sp2"] += p * p
    a["min"] = p if a["min"] is None else min(a["min"], p)
    a["max"] = p if a["max"] is None else max(a["max"], p)
    if a["prev"] is not None:
        dt = (e - a["prev"]) / 1000.0
        if dt > 0:
            a["energy"] += (a["prev_p"] + p) / 2.0 * dt / 3600.0
    a["prev"] = e
    a["prev_p"] = p
    b = int(p / 0.01)
    a["hist"][b] = a["hist"].get(b, 0) + 1


def _acc_stats(a):
    n = a["n"]
    if n == 0:
        return None
    avg = a["sp"] / n
    sd = max(a["sp2"] / n - avg * avg, 0.0) ** 0.5
    med = 0.0
    if a["hist"]:
        half = n // 2
        cum = 0
        for b in sorted(a["hist"]):
            cum += a["hist"][b]
            if cum >= half:
                med = b * 0.01
                break
    else:
        med = None  # condensed data: the exact median isn't recoverable
    return {
        "samples": n,
        "duration_s": (a["last"] - a["first"]) / 1000.0 if a["first"] is not None else 0.0,
        "avg_v": a["sv"] / n, "avg_a": a["si"] / n, "avg_w": avg,
        "median_w": med, "sd_w": sd, "min_w": a["min"], "max_w": a["max"],
        "energy_wh": a["energy"], "on_frac": a["on"] / n,
    }


def _upd_chunk(a, row):
    """Fold one condensed chunk row into an accumulator."""
    e = float(row[0]); cm = float(row[1]); n = int(row[2])
    if n <= 0:
        return
    if a["first"] is None:
        a["first"] = e
    a["last"] = e + cm
    a["n"] += n
    a["on"] += int(row[7])
    a["sv"] += float(row[8]); a["si"] += float(row[9])
    a["sp"] += float(row[3]); a["sp2"] += float(row[4])
    mn = float(row[5]); mx = float(row[6])
    a["min"] = mn if a["min"] is None else min(a["min"], mn)
    a["max"] = mx if a["max"] is None else max(a["max"], mx)
    a["energy"] += float(row[10])


def scan_csv(path, windows=None):
    """Single streaming pass over a raw or condensed CSV.

    Returns (meta, full stats, {level: stats}); memory stays flat even for a
    multi-GB raw run. `windows` is a list of (name, seconds); defaults to the
    standard presets.
    """
    if windows is None:
        windows = STANDARD_LEVELS
    meta = {}
    full = _new_acc()
    levels = {name: _new_acc() for name, _ in windows}
    base = None
    header_seen = False
    chunked = False
    with open(path) as fh:
        for line in fh:
            if line.startswith("#"):
                k, _, val = line[1:].strip().partition(",")
                meta[k.strip()] = val.strip()
                continue
            if not header_seen:
                header_seen = True
                chunked = "sum_w" in line
                continue
            if chunked:
                p = line.split(",")
                if len(p) < 11:
                    continue
                try:
                    e = float(p[0])
                except ValueError:
                    continue
                if base is None:
                    base = e
                rel = e - base
                _upd_chunk(full, p)
                for name, lsec in windows:
                    if rel <= lsec * 1000:
                        _upd_chunk(levels[name], p)
            else:
                p = line.split(",")
                if len(p) < 5:
                    continue
                try:
                    e = float(p[1]); v = float(p[2]); i = float(p[3]); w = float(p[4])
                except ValueError:
                    continue
                if base is None:
                    base = e
                rel = e - base
                _upd(full, v, i, w, e)
                for name, lsec in windows:
                    if rel <= lsec * 1000:
                        _upd(levels[name], v, i, w, e)
    return meta, _acc_stats(full), {name: _acc_stats(a) for name, a in levels.items() if a["n"] > 0}


def build_report_rows(files):
    """Group runs by setup and emit one row per preset level (nano…ultra).

    Every run long enough to cover a level contributes (its first N seconds for
    a shorter level, the whole run for the matching/longest one). Full-screen
    setups (the `fast` preset) are a single whole-run row.
    """
    groups = {}
    for path in files:
        meta, full, windows = scan_csv(path)
        if not full:
            continue
        groups.setdefault(_setup_key(meta), []).append((meta, full, windows))

    out = []
    for items in groups.values():
        meta0 = items[0][0]
        try:
            screen = float(meta0.get("screen_percent", "0"))
        except ValueError:
            screen = -1.0

        if screen >= 100:
            valid = [(meta, f) for meta, f, _ in items if _on_valid(f["on_frac"], screen)]
            if valid:
                out.append(_agg(_level_label(meta0.get("label", "fast"), meta0), valid))
            continue

        for lname, lsec in STANDARD_LEVELS:
            win = [(m, ws[lname]) for m, full, ws in items
                   if lname in ws and full["duration_s"] >= lsec - 0.5
                   and _on_valid(ws[lname]["on_frac"], screen)]
            if win:
                out.append(_agg(_level_label(lname, meta0), win))

        # "ultra" means a completed whole-route run: require it to be the longest
        # ultra and at least as long as the `long` window (else it's just a
        # partial run and would be mislabelled).
        long_s = dict(STANDARD_LEVELS)["long"]
        ultras = [(m, f) for m, f, _ in items if m.get("preset") == "ultra"]
        if ultras:
            ref = max(f["duration_s"] for _, f in ultras)
            complete = [(m, f) for m, f in ultras
                        if f["duration_s"] >= 0.98 * ref and f["duration_s"] >= long_s
                        and _on_valid(f["on_frac"], screen)]
            if complete:
                out.append(_agg(_level_label("ultra", meta0), complete))

    out.sort(key=lambda r: r["label"])
    return out


def report_cells(r):
    return [r["label"], r["device"], r["mode"], str(r["screen"]), str(r["bright"]),
            str(r["vol"]), str(r["speed"]), str(r["start"]), str(r["n"]),
            f"{r['dur']:.1f}", f"{r['avg']:.4f}", f"{r['sd']:.4f}", f"{r['cv']:.2f}",
            f"{r['on'] * 100:.1f}",
            "-" if r["med"] is None else f"{r['med']:.4f}",
            f"{r['max']:.4f}", f"{r['mwh']:.2f}", f"{r['mwhh']:.1f}"]


def report_md(title, cells):
    lines = [f"# Power report: {title}", "",
             "| " + " | ".join(REPORT_HEADER) + " |",
             "|" + "|".join("---" for _ in REPORT_HEADER) + "|"]
    for row in cells:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines) + "\n"


def report_txt(title, cells):
    widths = [len(h) for h in REPORT_HEADER]
    for row in cells:
        for i, c in enumerate(row):
            widths[i] = max(widths[i], len(c))
    def fmt(row):
        return "  ".join(c.rjust(widths[i]) if i > 0 else c.ljust(widths[i])
                         for i, c in enumerate(row))
    lines = [f"Power report: {title}", "=" * (sum(widths) + 2 * (len(widths) - 1)),
             fmt(REPORT_HEADER), "-" * (sum(widths) + 2 * (len(widths) - 1))]
    lines += [fmt(row) for row in cells]
    return "\n".join(lines) + "\n"


def cmd_report(args):
    paths = expand_paths(args.files)
    if not paths:
        sys.exit("No CSV files matched.")
    rows = build_report_rows(paths)
    if not rows:
        sys.exit("No runs found.")

    # Start from the baseline (if given) so the local report is a full table;
    # runs present in the CSVs replace same-label baseline rows.
    merged = {}
    if args.merge:
        _, old = parse_report_md(args.merge)
        merged.update(old)
    for r in rows:
        merged[r["label"]] = report_cells(r)
    cells = [merged[label] for label in sorted(merged)]

    title = args.title or "run set"
    md = report_md(title, cells)
    txt = report_txt(title, cells)

    if args.out:
        with open(args.out, "w") as fh:
            fh.write(md)
        txt_path = args.txt or (args.out[:-3] + ".txt" if args.out.endswith(".md") else args.out + ".txt")
        with open(txt_path, "w") as fh:
            fh.write(txt)
        print(f"wrote {args.out}")
        print(f"wrote {txt_path}")
    print(md)


def parse_report_md(path):
    header = None
    rows = {}
    with open(path) as fh:
        for line in fh:
            s = line.strip()
            if not s.startswith("|"):
                continue
            cells = [c.strip() for c in s.strip("|").split("|")]
            if header is None:
                header = cells
                continue
            if all(set(c) <= set("-: ") for c in cells):
                continue
            if not cells or not cells[0]:
                continue
            rows[cells[0]] = cells
    return header, rows


def cmd_diff(args):
    old_header, old_rows = parse_report_md(args.old)
    new_header, new_rows = parse_report_md(args.new)
    if not old_header or not new_header:
        sys.exit("Could not parse one of the report files.")
    oi = old_header.index("avg W")
    ni = new_header.index("avg W")

    labels = sorted(set(old_rows) | set(new_rows))
    print(f"{'label':<22} {'old avg W':>10} {'new avg W':>10} {'delta W':>9} {'delta %':>9}  status")
    print("-" * 78)
    changed = 0
    for label in labels:
        o = old_rows.get(label)
        n = new_rows.get(label)
        if o and not n:
            print(f"{label[:22]:<22} {float(o[oi]):10.4f} {'-':>10} {'-':>9} {'-':>9}  removed")
            changed += 1
        elif n and not o:
            print(f"{label[:22]:<22} {'-':>10} {float(n[ni]):10.4f} {'-':>9} {'-':>9}  added")
            changed += 1
        else:
            ov, nv = float(o[oi]), float(n[ni])
            dw = nv - ov
            dp = dw / ov * 100.0 if ov else 0.0
            status = "same" if abs(dw) < 1e-6 else "changed"
            if status == "changed":
                changed += 1
            print(f"{label[:22]:<22} {ov:10.4f} {nv:10.4f} {dw:+9.4f} {dp:+8.2f}%  {status}")
    print(f"\n{changed} row(s) differ. old={args.old}  new={args.new}")


def cmd_promote(args):
    import shutil
    shutil.copyfile(args.new, args.official)
    print(f"promoted {args.new} -> {args.official}")


def main():
    parser = argparse.ArgumentParser(
        description="Record and compare power from an AVHzY/Shizuku USB power meter.")
    sub = parser.add_subparsers(dest="command", required=True)

    rec = sub.add_parser("record", help="capture a power run to CSV")
    rec.add_argument("-l", "--label", required=True, help="name of this run (e.g. app version)")
    rec.add_argument("-n", "--note", default="", help="free-text note stored in the CSV header")
    rec.add_argument("-d", "--duration", type=float, default=0.0,
                     help="seconds to record; 0 = until Ctrl-C (default 0)")
    rec.add_argument("-p", "--port", default=None, help="serial port (default: auto-detect)")
    rec.add_argument("-o", "--out", default="power-runs", help="output directory (default power-runs)")
    rec.add_argument("-i", "--interval-ms", type=int, default=1, help="sampling interval in ms (default 1)")
    rec.add_argument("-w", "--warmup", type=float, default=1.0,
                     help="seconds to discard at the start (default 1)")
    rec.add_argument("-q", "--quiet", action="store_true", help="suppress the live readout")
    rec.add_argument("--chunk", type=float, default=300.0,
                     help="condense output into N-second chunks of n/mean/min/max/etc. "
                          "(0 = raw 1 kHz rows; default 300)")
    rec.add_argument("--meta", action="append", default=[],
                     help="extra CSV header metadata as key=value (repeatable)")
    rec.set_defaults(func=cmd_record)

    cmp_ = sub.add_parser("compare", help="print a table comparing CSV runs")
    cmp_.add_argument("files", nargs="+", help="CSV files, globs, or directories")
    cmp_.add_argument("--baseline", default=None,
                      help="label to compare against (default: first run in the list)")
    cmp_.set_defaults(func=cmd_compare)

    win = sub.add_parser("windows", help="prefix-window stats from a run (e.g. 10m/2h/4h of a long run)")
    win.add_argument("files", nargs="+", help="CSV files, globs, or directories")
    win.add_argument("--durations", required=True,
                     help="comma-separated window lengths in seconds, e.g. 600,7200,14400")
    win.add_argument("--labels", default=None,
                     help="comma-separated window names, e.g. small,medium,long")
    win.add_argument("--aggregate", action="store_true",
                     help="group windows by run label and show n / mean / sd / cv")
    win.set_defaults(func=cmd_windows)

    rep = sub.add_parser("report", help="write a markdown/text report for a set of runs")
    rep.add_argument("files", nargs="+", help="CSV files, globs, or directories")
    rep.add_argument("-o", "--out", default=None, help="markdown output path (also writes a .txt)")
    rep.add_argument("--txt", default=None, help="explicit .txt output path")
    rep.add_argument("--title", default=None, help="report title")
    rep.add_argument("--merge", default=None,
                     help="baseline .md to merge into, so the report stays a full table")
    rep.set_defaults(func=cmd_report)

    dif = sub.add_parser("diff", help="compare two report .md files by label")
    dif.add_argument("old", help="baseline report .md")
    dif.add_argument("new", help="new report .md")
    dif.set_defaults(func=cmd_diff)

    pro = sub.add_parser("promote", help="replace the official baseline with a new report")
    pro.add_argument("new", help="new report .md")
    pro.add_argument("official", help="official baseline path to overwrite")
    pro.set_defaults(func=cmd_promote)

    con = sub.add_parser("condense", help="shrink raw 1 kHz CSVs into per-chunk aggregates")
    con.add_argument("files", nargs="+", help="raw CSV files, globs, or directories")
    con.add_argument("--chunk", type=float, default=300.0, help="chunk length in seconds (default 300)")
    con.add_argument("-o", "--out", default=None,
                     help="output directory (default: alongside each file)")
    con.set_defaults(func=cmd_condense)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
