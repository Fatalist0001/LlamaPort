#!/usr/bin/env python3
import sys
sys.path.insert(0, str(__import__('pathlib').Path(__file__).resolve().parent))
import run_measurements as m

def has(name):
    return (m.RAW / f"{name}_gfxinfo.txt").exists()

def do(label, fn):
    try:
        meta = fn()
        m.META.append(meta)
        print("done", meta, flush=True)
    except Exception as e:
        print("FAILED", label, e, flush=True)

# Reps 2-3 for main scenarios whose rep 1 already exists.
for rep in (2, 3):
    for name, fn in [
        (f"S2_idle_{rep}", lambda r=rep: m.scenario_idle(r, {"measure_empty": True}, "S2_idle")),
        (f"S3_fake10_{rep}", lambda r=rep: m.scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300}, "S3_fake10", 300, 10)),
        (f"S4_fake25_{rep}", lambda r=rep: m.scenario_fake(r, {"measure_fake": 25, "measure_fake_tokens": 750}, "S4_fake25", 750, 25)),
        (f"S5_real_{rep}", lambda r=rep: m.scenario_real(r)),
    ]:
        if not has(name):
            do(name, fn)
for rep in (1, 2, 3):
    for name, fn in [
        (f"S6_scroll_{rep}", lambda r=rep: m.scenario_scroll(r, {"measure_seed_long": True}, "S6_scroll", 20)),
        (f"S7_scroll_stream_{rep}", lambda r=rep: m.scenario_scroll_stream(r)),
        (f"S8_sheets_{rep}", lambda r=rep: m.scenario_sheets(r)),
        (f"S9_thinking_{rep}", lambda r=rep: m.scenario_thinking(r)),
        (f"S3_no_logo_{rep}", lambda r=rep: m.scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300, "measure_no_logo": True}, "S3_no_logo", 300, 10)),
        (f"S3_plain_{rep}", lambda r=rep: m.scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300, "measure_plain": True}, "S3_plain", 300, 10)),
        (f"S3_publish15_{rep}", lambda r=rep: m.scenario_fake(r, {"measure_fake": 25, "measure_fake_tokens": 750, "measure_publish_hz": 15}, "S3_publish15", 750, 25)),
        (f"S2_no_logo_{rep}", lambda r=rep: m.scenario_idle(r, {"measure_empty": True, "measure_no_logo": True}, "S2_no_logo")),
    ]:
        if not has(name):
            do(name, fn)

# One trace per traced scenario, separate from the gfx reps.
for label, fn in [
    ("TRACE_S3", lambda: m.scenario_fake(101, {"measure_fake": 10, "measure_fake_tokens": 300}, "TRACE_S3", 300, 10, trace=True)),
    ("TRACE_S5", lambda: m.scenario_real(101, trace=True)),
    ("TRACE_S6", lambda: m.scenario_scroll(101, {"measure_seed_long": True}, "TRACE_S6", 20, trace=True)),
    ("TRACE_S7", lambda: m.scenario_scroll_stream(101, trace=True)),
]:
    do(label, fn)

print("MISSING DONE", flush=True)
