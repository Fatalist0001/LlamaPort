#!/usr/bin/env python3
"""Phase 1 validation: the streaming scenarios from the baseline, re-measured.

Reuses the helpers from run_measurements.py, but writes into separate folders so
the baseline raw data and the post-change data never mix.
"""
import json
import time
from pathlib import Path

import run_measurements as rm

rm.RAW = rm.ROOT / "perf/raw_phase1"
rm.TRACES = rm.ROOT / "perf/traces_phase1"
rm.RAW.mkdir(parents=True, exist_ok=True)
rm.TRACES.mkdir(parents=True, exist_ok=True)
META = []


def scenario_real(rep, trace=False):
    name = f"P1_real_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_real": True})
    if not rm.wait_marker("real_first_token", 180, "Measure:I"):
        print(f"  {name}: first token marker missing", flush=True)
    rm.gfx_reset()
    top = rm.top_start(name, samples=18, interval=2)
    trace_info = rm.perfetto_start(name, 35) if trace else None
    ok = rm.wait_marker("generateOnce: generate returned", 180, "ChatViewModel:I")
    if not ok:
        print(f"  {name}: completion marker missing", flush=True)
        time.sleep(10)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.top_stop(top)
    if trace_info:
        rm.perfetto_finish(name, trace_info[0], trace_info[1])
    return {"scenario": "P1_real", "rep": rep, "temp": rm.temp()}


def main():
    # Normalise the session to an empty chat, as the baseline run did.
    rm.force_stop(); rm.start({"measure_empty": True}); time.sleep(2); rm.force_stop()
    plan = []
    for rep in (1, 2, 3):
        plan.append(("fake10", lambda r=rep: rm.scenario_fake(
            r, {"measure_fake": 10, "measure_fake_tokens": 300}, "P1_fake10", 300, 10,
            trace=(r == 1))))
    for rep in (1, 2, 3):
        plan.append(("fake25", lambda r=rep: rm.scenario_fake(
            r, {"measure_fake": 25, "measure_fake_tokens": 750}, "P1_fake25", 750, 25,
            trace=False)))
    for rep in (1, 2, 3):
        plan.append(("real", lambda r=rep: scenario_real(r, trace=(r == 1))))

    for label, fn in plan:
        try:
            meta = fn()
            META.append(meta)
            (rm.ROOT / "perf/phase1_meta.json").write_text(
                json.dumps(META, ensure_ascii=False, indent=2))
            print(f"done {meta}", flush=True)
        except Exception as e:  # keep going: one bad scenario must not kill the run
            print(f"FAILED {label}: {e}", flush=True)
            META.append({"scenario": label, "error": str(e)})
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()
