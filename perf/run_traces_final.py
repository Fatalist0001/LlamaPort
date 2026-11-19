#!/usr/bin/env python3
"""Re-captures two short, complete traces (128 MB buffer) for the phase 1 report."""
import time
from pathlib import Path

import run_measurements as rm

rm.RAW = rm.ROOT / "perf/raw_final"
rm.TRACES = rm.ROOT / "perf/traces_final"
rm.RAW.mkdir(parents=True, exist_ok=True)
rm.TRACES.mkdir(parents=True, exist_ok=True)


def perfetto(name, seconds=12, buffer="128mb"):
    path = f"/data/misc/perfetto-traces/{name}.pftrace"
    rm.sh(
        f"perfetto -d -o {path} -b {buffer} -t {seconds}s "
        "sched freq idle gfx view input am wm res binder_driver dalvik -a com.llamaport.app",
        timeout=60,
    )
    return path, seconds


def fake10():
    name = "F_fake10"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_fake": 10, "measure_fake_tokens": 300})
    rm.wait_marker("fake_start", 15)
    rm.gfx_reset()
    top = rm.top_start(name, samples=8, interval=2)
    trace = perfetto(name, 12)
    rm.perfetto_finish(name, trace[0], trace[1])
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.top_stop(top)


def real():
    name = "F_real"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_real": True})
    rm.wait_marker("real_first_token", 180, "Measure:I")
    rm.gfx_reset()
    top = rm.top_start(name, samples=8, interval=2)
    trace = perfetto(name, 12)
    rm.perfetto_finish(name, trace[0], trace[1])
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.top_stop(top)


if __name__ == "__main__":
    fake10()
    print("fake done", flush=True)
    real()
    print("real done", flush=True)
