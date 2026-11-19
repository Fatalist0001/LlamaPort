#!/usr/bin/env python3
import json, os, re, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = str(ROOT / ".sdk/platform-tools/adb")
PKG = "com.llamaport.app"
RAW = ROOT / "perf/raw"
TRACES = ROOT / "perf/traces"
RAW.mkdir(parents=True, exist_ok=True)
TRACES.mkdir(parents=True, exist_ok=True)
BASELINE = 37.4
META = []

def run(cmd, timeout=120, check=False):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd} -> {p.returncode}\n{p.stdout}\n{p.stderr}")
    return p.stdout

def sh(cmd, timeout=120):
    return run([ADB, "shell", cmd], timeout=timeout)

def adb(*args, timeout=120):
    return run([ADB, *args], timeout=timeout)

def temp():
    out = sh("dumpsys battery")
    m = re.search(r"temperature:\s*(\d+)", out)
    return int(m.group(1)) / 10.0 if m else -1.0

def wake():
    sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    time.sleep(0.3)

def wait_cool(timeout=240):
    start = time.time()
    while time.time() - start < timeout:
        t = temp()
        if 0 < t <= BASELINE + 0.6:
            return t
        print(f"  cooling: {t:.1f}C (baseline {BASELINE:.1f})", flush=True)
        wake()
        time.sleep(10)
    return temp()

def force_stop():
    sh(f"am force-stop {PKG}")

def start(extras=None, wait=False):
    args = [ADB, "shell", "am", "start"]
    if wait:
        args.append("-W")
    args += ["-n", f"{PKG}/.MainActivity"]
    for k, v in (extras or {}).items():
        if isinstance(v, bool):
            args += ["--ez", k, "true" if v else "false"]
        elif isinstance(v, int):
            args += ["--ei", k, str(v)]
        else:
            args += ["--es", k, str(v)]
    return run(args, timeout=180)

def logcat_clear():
    adb("logcat", "-c")

def logcat_dump(name, tags=None):
    args = [ADB, "logcat", "-d", "-v", "threadtime"]
    if tags:
        args += ["-s", tags]
    out = run(args, timeout=120)
    (RAW / f"{name}_logcat.txt").write_text(out)

def wait_marker(marker, timeout=30, tags="Measure:I"):
    end = time.time() + timeout
    while time.time() < end:
        out = run([ADB, "logcat", "-d", "-s", tags], timeout=30)
        if marker in out:
            return True
        time.sleep(0.5)
    return False

def gfx_reset():
    sh(f"dumpsys gfxinfo {PKG} reset")

def gfx_dump(name):
    summary = sh(f"dumpsys gfxinfo {PKG}", timeout=120)
    frames = sh(f"dumpsys gfxinfo {PKG} framestats", timeout=120)
    (RAW / f"{name}_gfxinfo.txt").write_text(summary)
    (RAW / f"{name}_framestats.txt").write_text(frames)

def meminfo(name):
    (RAW / f"{name}_meminfo.txt").write_text(sh(f"dumpsys meminfo {PKG}", timeout=120))

def ui_dump(name):
    sh("uiautomator dump /sdcard/window.xml", timeout=60)
    adb("pull", "/sdcard/window.xml", str(RAW / f"{name}_ui.xml"), timeout=60)
    return RAW / f"{name}_ui.xml"

def top_start(name, samples=16, interval=2):
    pid = sh(f"pidof {PKG}").strip()
    if not pid:
        return None
    cmd = f"pid={pid}; for i in $(seq 1 {samples}); do top -H -b -n 1 -p $pid; sleep {interval}; done"
    f = open(RAW / f"{name}_top.txt", "w")
    p = subprocess.Popen([ADB, "shell", cmd], stdout=f, stderr=subprocess.STDOUT)
    p._out = f
    return p

def top_stop(p):
    if p is None:
        return
    try:
        p.terminate()
        p.wait(timeout=5)
    except Exception:
        p.kill()
    try:
        p._out.close()
    except Exception:
        pass

def perfetto_start(name, seconds):
    path = f"/data/misc/perfetto-traces/{name}.pftrace"
    sh(f"perfetto -d -o {path} -t {seconds}s sched freq idle gfx view input am wm res binder_driver dalvik -a {PKG}", timeout=60)
    return path, seconds

def perfetto_finish(name, path, seconds):
    time.sleep(max(0, seconds + 1))
    adb("pull", path, str(TRACES / f"{name}.pftrace"), timeout=180)
    sh(f"rm -f {path}")

def swipe(direction):
    if direction == "up":
        sh("input swipe 540 1900 540 500 200")
    else:
        sh("input swipe 540 500 540 1900 200")
    time.sleep(0.55)

def run_swipes(n=20):
    for i in range(n):
        swipe("up" if i % 2 == 0 else "down")

def tap(x, y, delay=0.5):
    sh(f"input tap {x} {y}")
    time.sleep(delay)

def scenario_s1(rep):
    name = f"S1_cold_{rep}"
    force_stop()
    wait_cool()
    logcat_clear()
    out = start(wait=True)
    (RAW / f"{name}_am_start.txt").write_text(out)
    time.sleep(6)
    gfx_dump(name)
    logcat_dump(name, "ActivityTaskManager:I")
    meminfo(name)
    return {"scenario": "S1_cold", "rep": rep, "temp": temp()}

def scenario_idle(rep, extras, label):
    name = f"{label}_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start(extras); time.sleep(2)
    gfx_reset()
    top = top_start(name, samples=16, interval=2)
    time.sleep(30)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    return {"scenario": label, "rep": rep, "temp": temp()}

def scenario_fake(rep, extras, label, tokens, rate, trace=False, extra_compare=None):
    name = f"{label}_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start(extras)
    if not wait_marker("fake_start", 15):
        print(f"  {name}: fake_start marker missing", flush=True)
    gfx_reset()
    top = top_start(name, samples=18, interval=2)
    trace_info = None
    if trace:
        trace_info = perfetto_start(name, 20)
    duration = tokens / float(rate) + 2.0
    time.sleep(duration)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    if trace_info:
        perfetto_finish(name, trace_info[0], trace_info[1])
    return {"scenario": label, "rep": rep, "temp": temp()}

def scenario_real(rep, trace=False):
    name = f"S5_real_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start({"measure_real": True})
    if not wait_marker("real_first_token", 180, "Measure:I"):
        print(f"  {name}: first token marker missing", flush=True)
    gfx_reset()
    top = top_start(name, samples=18, interval=2)
    trace_info = perfetto_start(name, 35) if trace else None
    ok = wait_marker("generateOnce: generate returned", 180, "ChatViewModel:I")
    if not ok:
        print(f"  {name}: completion marker missing", flush=True)
        time.sleep(10)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    if trace_info:
        perfetto_finish(name, trace_info[0], trace_info[1])
    return {"scenario": "S5_real", "rep": rep, "temp": temp()}

def scenario_scroll(rep, extras, label, seconds=20, trace=False):
    name = f"{label}_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start(extras); time.sleep(3)
    gfx_reset()
    top = top_start(name, samples=14, interval=2)
    trace_info = perfetto_start(name, seconds) if trace else None
    run_swipes(20)
    # keep the window close to the requested duration
    time.sleep(max(0, seconds - 17))
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    if trace_info:
        perfetto_finish(name, trace_info[0], trace_info[1])
    return {"scenario": label, "rep": rep, "temp": temp()}

def scenario_scroll_stream(rep, trace=False):
    name = f"S7_scroll_stream_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start({"measure_seed_long": True, "measure_fake": 10, "measure_fake_tokens": 300})
    if not wait_marker("fake_start", 20):
        print(f"  {name}: fake_start missing", flush=True)
    gfx_reset()
    top = top_start(name, samples=16, interval=2)
    trace_info = perfetto_start(name, 25) if trace else None
    run_swipes(20)
    time.sleep(5)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    if trace_info:
        perfetto_finish(name, trace_info[0], trace_info[1])
    return {"scenario": "S7_scroll_stream", "rep": rep, "temp": temp()}

def scenario_sheets(rep):
    name = f"S8_sheets_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start({"measure_empty": True}); time.sleep(2)
    gfx_reset()
    top = top_start(name, samples=12, interval=2)
    for i in range(10):
        tap(70, 165, 0.45)
        tap(459, 2179, 0.55)
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.35)
        tap(300, 165, 0.55)
        sh("input keyevent KEYCODE_BACK"); time.sleep(0.35)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    return {"scenario": "S8_sheets", "rep": rep, "temp": temp()}

def scenario_thinking(rep):
    name = f"S9_thinking_{rep}"
    force_stop(); wait_cool(); wake(); logcat_clear()
    start({"measure_thinking": True}); time.sleep(2)
    gfx_reset()
    top = top_start(name, samples=8, interval=2)
    xml = ui_dump(name + "_before")
    bounds = None
    try:
        import xml.etree.ElementTree as ET
        root = ET.parse(xml).getroot()
        for n in root.iter("node"):
            if n.get("text") == "Размышления":
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    bounds = ((x1 + x2) // 2, (y1 + y2) // 2)
                    break
    except Exception as e:
        print("  thinking bounds error", e)
    if bounds is None:
        bounds = (308, 500)
    for i in range(10):
        tap(bounds[0], bounds[1], 0.65)
    gfx_dump(name); meminfo(name); logcat_dump(name)
    top_stop(top)
    return {"scenario": "S9_thinking", "rep": rep, "temp": temp()}

def main():
    # Normalise the current session to an empty chat before cold-start runs.
    force_stop(); start({"measure_empty": True}); time.sleep(2); force_stop()
    plan = []
    for rep in range(1, 6):
        plan.append(("S1", lambda r=rep: scenario_s1(r)))
    for rep in range(1, 4):
        plan += [
            ("S2", lambda r=rep: scenario_idle(r, {"measure_empty": True}, "S2_idle")),
            ("S3", lambda r=rep: scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300}, "S3_fake10", 300, 10, trace=(r == 1))),
            ("S4", lambda r=rep: scenario_fake(r, {"measure_fake": 25, "measure_fake_tokens": 750}, "S4_fake25", 750, 25, trace=False)),
            ("S5", lambda r=rep: scenario_real(r, trace=(r == 1))),
            ("S6", lambda r=rep: scenario_scroll(r, {"measure_seed_long": True}, "S6_scroll", 20, trace=(r == 1))),
            ("S7", lambda r=rep: scenario_scroll_stream(r, trace=(r == 1))),
            ("S8", lambda r=rep: scenario_sheets(r)),
            ("S9", lambda r=rep: scenario_thinking(r)),
        ]
    for rep in range(1, 4):
        plan += [
            ("V1", lambda r=rep: scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300, "measure_no_logo": True}, "S3_no_logo", 300, 10)),
            ("V2", lambda r=rep: scenario_fake(r, {"measure_fake": 10, "measure_fake_tokens": 300, "measure_plain": True}, "S3_plain", 300, 10)),
            ("V3", lambda r=rep: scenario_fake(r, {"measure_fake": 25, "measure_fake_tokens": 750, "measure_publish_hz": 15}, "S3_publish15", 750, 25)),
            ("V4", lambda r=rep: scenario_idle(r, {"measure_empty": True, "measure_no_logo": True}, "S2_no_logo")),
        ]
    for label, fn in plan:
        try:
            meta = fn()
            META.append(meta)
            (ROOT / "perf/meta.json").write_text(json.dumps(META, ensure_ascii=False, indent=2))
            print(f"done {meta}", flush=True)
        except Exception as e:
            print(f"FAILED {label}: {e}", flush=True)
            META.append({"scenario": label, "error": str(e)})
            (ROOT / "perf/meta.json").write_text(json.dumps(META, ensure_ascii=False, indent=2))
    print("ALL DONE", flush=True)

if __name__ == "__main__":
    main()
