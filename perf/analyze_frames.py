#!/usr/bin/env python3
import csv, json, math, os, re, statistics as st
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# The baseline lives in perf/raw; PERF_RAW/PERF_OUT let a later run be analysed
# without mixing its files into the baseline.
RAW = Path(os.environ.get("PERF_RAW", ROOT / "perf/raw"))
OUT = Path(os.environ.get("PERF_OUT", ROOT / "perf"))

def pct(xs, p):
    if not xs: return 0.0
    a = sorted(xs)
    k = (len(a) - 1) * p
    f = math.floor(k); c = math.ceil(k)
    if f == c: return a[int(k)]
    return a[f] + (a[c] - a[f]) * (k - f)

def parse_summary(path):
    text = path.read_text(errors="replace")
    def num(pat, cast=float):
        m = re.search(pat, text)
        return cast(m.group(1)) if m else 0
    return {
        "total": num(r"Total frames rendered:\s*(\d+)", int),
        "janky": num(r"Janky frames:\s*(\d+)", int),
        "janky_pct": num(r"Janky frames:.*?\(([\d.]+)%\)"),
        "p50": num(r"50th percentile:\s*(\d+)ms", int),
        "p90": num(r"90th percentile:\s*(\d+)ms", int),
        "p95": num(r"95th percentile:\s*(\d+)ms", int),
        "p99": num(r"99th percentile:\s*(\d+)ms", int),
        "missed_vsync": num(r"Number Missed Vsync:\s*(\d+)", int),
        "slow_ui": num(r"Number Slow UI thread:\s*(\d+)", int),
        "slow_draw": num(r"Number Slow issue draw commands:\s*(\d+)", int),
        "slow_upload": num(r"Number Slow bitmap uploads:\s*(\d+)", int),
        "deadline_missed": num(r"Number Frame deadline missed:\s*(\d+)", int),
        **parse_hist(text),
    }

def parse_hist(text):
    m = re.search(r"HISTOGRAM:\s*([^\n]+)", text)
    if not m:
        return {"hist_total": 0, "hist_p50": 0, "hist_p90": 0, "hist_p95": 0,
                "hist_p99": 0, "hist_over_16": 0, "hist_over_8": 0}
    bins = []
    for part in m.group(1).split():
        mm = re.match(r"(\d+)ms=(\d+)", part)
        if mm:
            bins.append((int(mm.group(1)), int(mm.group(2))))
    bins.sort()
    total = sum(c for _, c in bins)
    def pct_h(p):
        if not total: return 0.0
        target = total * p
        acc = 0
        for val, c in bins:
            acc += c
            if acc >= target:
                return float(val)
        return float(bins[-1][0]) if bins else 0.0
    return {
        "hist_total": total,
        "hist_p50": pct_h(0.5), "hist_p90": pct_h(0.9),
        "hist_p95": pct_h(0.95), "hist_p99": pct_h(0.99),
        "hist_over_16": sum(c for val, c in bins if val > 16),
        "hist_over_8": sum(c for val, c in bins if val > 8),
    }

def parse_frames(path):
    lines = path.read_text(errors="replace").splitlines()
    header = None
    rows = []
    for i, line in enumerate(lines):
        if line.startswith("Flags,FrameTimelineVsyncId"):
            header = next(csv.reader([line]))
            continue
        if header and line.startswith("---PROFILEDATA---"):
            break
        if header and line and not line.startswith("---"):
            parts = next(csv.reader([line]))
            if len(parts) >= 22 and parts[0].isdigit():
                rows.append(parts)
    out = []
    for p in rows:
        try:
            intended = int(p[2]); vsync = int(p[3]); deadline = int(p[9])
            interval = int(p[10]); completed = int(p[16])
            if intended <= 0 or completed <= 0: continue
            dur = (completed - intended) / 1e6
            if dur <= 0 or dur > 5000: continue
            out.append({
                "dur": dur,
                "deadline_ms": (deadline - intended) / 1e6,
                "interval_ms": interval / 1e6,
                "late": dur > (deadline - intended) / 1e6 + 0.001,
                "over_16": dur > 16.67,
                "over_8": dur > 8.34,
            })
        except Exception:
            continue
    durs = [x["dur"] for x in out]
    intervals = [x["interval_ms"] for x in out if 1 < x["interval_ms"] < 100]
    missed = 0
    for x in out:
        iv = x["interval_ms"] or 8.33
        if x["dur"] > iv:
            missed += max(0, round(x["dur"] / iv) - 1)
    return {
        "frames": len(out),
        "p50": pct(durs, 0.5), "p90": pct(durs, 0.9),
        "p95": pct(durs, 0.95), "p99": pct(durs, 0.99),
        "over_16": sum(1 for x in out if x["over_16"]),
        "over_8": sum(1 for x in out if x["over_8"]),
        "late_deadline": sum(1 for x in out if x["late"]),
        "missed_vsync": missed,
        "interval_ms": st.median(intervals) if intervals else 0,
    }

def top_cpu(path):
    if not path.exists(): return 0.0, 0.0, 0.0, 0.0
    main_vals = []; render_vals = []
    for line in path.read_text(errors="replace").splitlines():
        parts = line.split()
        if len(parts) < 13 or not parts[0].isdigit():
            continue
        try:
            cpu = float(parts[8])
        except ValueError:
            continue
        thread = parts[11]; process = parts[12]
        if thread == "RenderThread":
            render_vals.append(cpu)
        elif process == "com.llamaport.app" and thread in ("m.llamaport.app", "com.llamaport.app"):
            main_vals.append(cpu)
    return (st.median(main_vals) if main_vals else 0.0,
            max(main_vals) if main_vals else 0.0,
            st.median(render_vals) if render_vals else 0.0,
            max(render_vals) if render_vals else 0.0)

def log_events(path):
    if not path.exists(): return {}
    text = path.read_text(errors="replace")
    return {
        "gc": len(re.findall(r"\bGC(?:_|\s|:)", text)),
        "skipped": sum(int(x) for x in re.findall(r"Skipped (\d+) frames", text)),
        "skipped_events": len(re.findall(r"Skipped \d+ frames", text)),
    }

def main():
    scenarios = {}
    for gfx in sorted(RAW.glob("*_gfxinfo.txt")):
        name = gfx.name[:-len("_gfxinfo.txt")]
        m = re.match(r"(.+)_(\d+)$", name)
        if not m: continue
        label, rep = m.group(1), int(m.group(2))
        if rep >= 100:
            continue
        summary = parse_summary(gfx)
        frames_path = RAW / f"{name}_framestats.txt"
        frames = parse_frames(frames_path) if frames_path.exists() else {}
        cpu_med, cpu_max, render_med, render_max = top_cpu(RAW / f"{name}_top.txt")
        ev = log_events(RAW / f"{name}_logcat.txt")
        row = {"name": name, "rep": rep, **summary, **{f"f_{k}": v for k, v in frames.items()},
               "main_cpu_med": cpu_med, "main_cpu_max": cpu_max,
               "render_cpu_med": render_med, "render_cpu_max": render_max, **ev}
        scenarios.setdefault(label, []).append(row)
    out = {}
    for label, rows in scenarios.items():
        keys = ["janky_pct", "p50", "p90", "p95", "p99", "missed_vsync", "slow_ui", "slow_draw",
                "hist_p50", "hist_p90", "hist_p95", "hist_p99", "hist_over_16", "hist_over_8",
                "hist_total", "f_frames", "f_p50", "f_over_16", "f_over_8",
                "main_cpu_med", "main_cpu_max", "render_cpu_med", "render_cpu_max", "gc", "skipped", "skipped_events"]
        med = {}
        for k in keys:
            vals = [r.get(k, 0) for r in rows if isinstance(r.get(k, 0), (int, float))]
            med[k] = st.median(vals) if vals else 0
        out[label] = {"reps": rows, "median": med}
    (OUT / "summary.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(json.dumps({k: v["median"] for k, v in out.items()}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
