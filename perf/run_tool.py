#!/usr/bin/env python3
"""Tool-call rendering check: expand a 200 KB tool result and replay a tool round."""
import json
import os
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import run_measurements as rm

rm.RAW = Path(os.environ.get("TOOL_RAW", rm.ROOT / "perf/raw_tool"))
rm.TRACES = Path(os.environ.get("TOOL_TRACES", rm.ROOT / "perf/traces_tool"))
rm.RAW.mkdir(parents=True, exist_ok=True)
rm.TRACES.mkdir(parents=True, exist_ok=True)
META = []


def perfetto_start(name, seconds, buffer="128mb"):
    path = f"/data/misc/perfetto-traces/{name}.pftrace"
    rm.sh(
        f"perfetto -d -o {path} -b {buffer} -t {seconds}s "
        "sched freq idle gfx view input am wm res binder_driver dalvik -a com.llamaport.app",
        timeout=60,
    )
    return path, seconds


def tool_header():
    xml = rm.ui_dump("tool_before")
    root = ET.parse(xml).getroot()
    for node in root.iter("node"):
        if node.get("text") == "Инструмент":
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def result_node():
    """Bounds of the expanded result text, if the accessibility tree has it."""
    xml = rm.ui_dump("tool_after")
    root = ET.parse(xml).getroot()
    best = None
    for node in root.iter("node"):
        text = node.get("text") or ""
        if text.startswith("строка 1:"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                best = {"chars": len(text), "bounds": [x1, y1, x2, y2],
                        "height": y2 - y1}
    return best


def scenario_tool(rep):
    name = f"T1_tool_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_tool": True})
    time.sleep(3)
    header = tool_header()
    rm.gfx_reset()
    trace = perfetto_start(name, 14)
    info = {"name": name, "header": header}
    if header:
        rm.tap(header[0], header[1], 0.0)
        time.sleep(9.0)
        rm.tap(header[0], header[1], 0.0)
        time.sleep(1.0)
    else:
        info["error"] = "no tool header"
        time.sleep(10)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    # Pull the trace first: a UI dump of 200 KB of text would itself block the
    # main thread and contaminate the window.
    rm.perfetto_finish(name, trace[0], trace[1])
    if header:
        rm.tap(header[0], header[1], 0.0)
        time.sleep(1.5)
        info["expanded_result"] = result_node()
        rm.tap(header[0], header[1], 0.0)
    info["temp"] = rm.temp()
    return info


def scenario_round(rep):
    name = f"T2_round_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_tool_round": True})
    if not rm.wait_marker("tool_round_start", 20):
        print(f"  {name}: start marker missing", flush=True)
    rm.gfx_reset()
    trace = perfetto_start(name, 14)
    if not rm.wait_marker("tool_round_result", 20):
        print(f"  {name}: result marker missing", flush=True)
    time.sleep(4)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.perfetto_finish(name, trace[0], trace[1])
    return {"name": name, "temp": rm.temp()}


def scenario_scroll(rep):
    name = f"T3_scan_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_tool": True})
    time.sleep(3)
    header = tool_header()
    if not header:
        return {"name": name, "error": "no tool header"}
    rm.tap(header[0], header[1], 0.0)
    time.sleep(1.5)
    rm.gfx_reset()
    trace = perfetto_start(name, 14)
    for i in range(10):
        rm.swipe("up" if i % 2 == 0 else "down")
    time.sleep(max(0, 12 - 10 * 0.55))
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.perfetto_finish(name, trace[0], trace[1])
    return {"name": name, "temp": rm.temp()}


def main():
    for rep in (1, 2):
        try:
            META.append(scenario_tool(rep))
            print("done", META[-1], flush=True)
        except Exception as e:
            print(f"FAILED tool {rep}: {e}", flush=True)
    for rep in (1, 2):
        try:
            META.append(scenario_round(rep))
            print("done", META[-1], flush=True)
        except Exception as e:
            print(f"FAILED round {rep}: {e}", flush=True)
    for rep in (1, 2):
        try:
            META.append(scenario_scroll(rep))
            print("done", META[-1], flush=True)
        except Exception as e:
            print(f"FAILED scan {rep}: {e}", flush=True)
    Path(os.environ.get("TOOL_META", rm.ROOT / "perf/tool_meta.json")).write_text(json.dumps(META, ensure_ascii=False, indent=2))
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()
