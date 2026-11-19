#!/usr/bin/env python3
"""Prints the phase 1 before/after table from the two summary files."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PAIRS = [
    ("S3_fake10", "P1_fake10", "10 tok/s"),
    ("S4_fake25", "P1_fake25", "25 tok/s"),
    ("S5_real", "P1_real", "real LFM3"),
]

KEYS = [
    ("hist_total", "frames", "{:.0f}"),
    ("janky_pct", "jank %", "{:.2f}"),
    ("hist_p50", "p50 ms", "{:.0f}"),
    ("hist_p90", "p90 ms", "{:.0f}"),
    ("hist_p95", "p95 ms", "{:.0f}"),
    ("hist_p99", "p99 ms", "{:.0f}"),
    ("hist_over_8", ">8 ms", "{:.0f}"),
    ("main_cpu_med", "main CPU %", "{:.1f}"),
    ("render_cpu_med", "Render CPU %", "{:.1f}"),
]


def load(path):
    return json.loads(Path(path).read_text())


def main():
    before = load(ROOT / "perf/summary.json")
    after = load(ROOT / "perf/summary_phase1.json")
    rows = []
    for old, new, title in PAIRS:
        b = before[old]["median"]
        a = after[new]["median"]
        for key, label, fmt in KEYS:
            bv, av = b.get(key, 0), a.get(key, 0)
            if key == "hist_over_8" and b.get("hist_total"):
                bv = bv * 100.0 / b["hist_total"]
                av = av * 100.0 / a["hist_total"] if a.get("hist_total") else 0
                fmt = "{:.1f}%"
            delta = ""
            if isinstance(bv, (int, float)) and bv:
                pct = (av - bv) * 100.0 / bv
                delta = f"{pct:+.0f}%"
            rows.append((title, label, fmt.format(bv), fmt.format(av), delta))

    w = max(len(r[1]) for r in rows)
    print(f"| {'scenario':<11} | {'metric':<{w}} | before | after | delta |")
    print(f"|{'-'*13}|{'-'*(w+2)}|--------|-------|-------|")
    scen = None
    for title, label, bv, av, delta in rows:
        head = title if title != scen else ""
        scen = title
        print(f"| {head:<11} | {label:<{w}} | {bv:>6} | {av:>5} | {delta:>5} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
