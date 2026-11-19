#!/usr/bin/env python3
"""Final check on the shipped build: real generation plus one rep of each
streaming scenario, and the tool-block scenarios with a screen that stays on.
"""
import json
import os
import time
from pathlib import Path

import run_measurements as rm

rm.RAW = Path(os.environ.get("FINAL_RAW", rm.ROOT / "perf/raw_final2"))
rm.TRACES = Path(os.environ.get("FINAL_TRACES", rm.ROOT / "perf/traces_final2"))
rm.RAW.mkdir(parents=True, exist_ok=True)
rm.TRACES.mkdir(parents=True, exist_ok=True)
META = []


def stay_awake():
    rm.sh("svc power stayon true")
    rm.sh("settings put system screen_off_timeout 1800000")


def perfetto(name, seconds=12, buffer="128mb"):
    path = f"/data/misc/perfetto-traces/{name}.pftrace"
    rm.sh(
        f"perfetto -d -o {path} -b {buffer} -t {seconds}s "
        "sched freq idle gfx view input am wm res binder_driver dalvik -a com.llamaport.app",
        timeout=60,
    )
    return path, seconds


def frames(name):
    import re
    text = (rm.RAW / f"{name}_gfxinfo.txt").read_text(errors="replace")
    m = re.search(r"Total frames rendered:\s*(\d+)", text)
    return int(m.group(1)) if m else 0


def real(rep):
    name = f"F_real_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_real": True})
    ok = rm.wait_marker("real_first_token", 180, "Measure:I")
    rm.gfx_reset()
    top = rm.top_start(name, samples=8, interval=2)
    trace = perfetto(name, 12)
    done = rm.wait_marker("generateOnce: generate returned", 180, "ChatViewModel:I")
    rm.perfetto_finish(name, trace[0], trace[1])
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.top_stop(top)
    return {"scenario": name, "first_token": ok, "completed": done,
            "frames": frames(name), "temp": rm.temp()}


def fake(rep, rate, tokens):
    label = f"F_fake{rate}"
    name = f"{label}_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_fake": rate, "measure_fake_tokens": tokens})
    rm.wait_marker("fake_start", 15)
    rm.gfx_reset()
    top = rm.top_start(name, samples=10, interval=2)
    time.sleep(tokens / float(rate) + 2.0)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.top_stop(top)
    return {"scenario": name, "frames": frames(name), "temp": rm.temp()}


def tool_expand(rep):
    name = f"T1_tool_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_tool": True})
    time.sleep(3)
    xml = rm.ui_dump("tool_before")
    import re
    import xml.etree.ElementTree as ET
    header = None
    for node in ET.parse(xml).getroot().iter("node"):
        if node.get("text") == "Инструмент":
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                header = ((x1 + x2) // 2, (y1 + y2) // 2)
    if not header:
        return {"scenario": name, "error": "no header"}
    rm.gfx_reset()
    trace = perfetto(name, 12)
    rm.tap(header[0], header[1], 0.0)
    time.sleep(9)
    rm.tap(header[0], header[1], 0.0)
    time.sleep(1.5)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.perfetto_finish(name, trace[0], trace[1])
    return {"scenario": name, "frames": frames(name), "header": header, "temp": rm.temp()}


def tool_scroll(rep):
    name = f"T4_tool_scroll_{rep}"
    rm.force_stop(); rm.wait_cool(); rm.wake(); rm.logcat_clear()
    rm.start({"measure_tool": True})
    time.sleep(3)
    import re
    import xml.etree.ElementTree as ET
    header = None
    for node in ET.parse(rm.ui_dump("tool_scroll_before")).getroot().iter("node"):
        if node.get("text") == "Инструмент":
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                header = ((x1 + x2) // 2, (y1 + y2) // 2)
    if not header:
        return {"scenario": name, "error": "no header"}
    rm.tap(header[0], header[1], 0.0)
    time.sleep(2)
    rm.gfx_reset()
    trace = perfetto(name, 14)
    # Swipe above the tool block so the chat list moves, not the inner text box.
    for i in range(12):
        rm.sh("input swipe 540 700 540 1900 180" if i % 2 == 0 else "input swipe 540 1900 540 700 180")
        time.sleep(0.45)
    time.sleep(1.0)
    rm.gfx_dump(name); rm.meminfo(name); rm.logcat_dump(name)
    rm.perfetto_finish(name, trace[0], trace[1])
    out = {"scenario": name, "frames": frames(name), "temp": rm.temp()}
    if out["frames"] < 50:
        out["warning"] = "too few frames: check screen state"
    return out


def main():
    stay_awake()
    rm.force_stop(); rm.start({"measure_empty": True}); time.sleep(2); rm.force_stop()
    plan = [
        ("real", lambda: real(1)),
        ("fake10", lambda: fake(1, 10, 300)),
        ("fake25", lambda: fake(1, 25, 750)),
        ("tool", lambda: tool_expand(1)),
        ("tool_scroll", lambda: tool_scroll(1)),
        ("tool_scroll", lambda: tool_scroll(2)),
    ]
    for label, fn in plan:
        try:
            meta = fn()
            META.append(meta)
            print("done", meta, flush=True)
        except Exception as e:
            print(f"FAILED {label}: {e}", flush=True)
            META.append({"scenario": label, "error": str(e)})
        (rm.ROOT / "perf/final_meta.json").write_text(
            json.dumps(META, ensure_ascii=False, indent=2))
    print("ALL DONE", flush=True)


if __name__ == "__main__":
    main()