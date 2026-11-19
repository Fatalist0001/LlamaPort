import sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_measurements as m

def pstart(name, seconds):
    path = f"/data/misc/perfetto-traces/{name}.pftrace"
    m.sh(f"perfetto -d -o {path} -b 128mb -t {seconds}s sched freq idle gfx view input am wm res binder_driver dalvik -a com.llamaport.app", timeout=60)
    return path, seconds

def real_trace():
    m.force_stop(); m.wait_cool(); m.wake(); m.logcat_clear()
    m.start({"measure_real": True})
    m.wait_marker("real_first_token", 180)
    path, sec = pstart("S5_trace12", 12)
    m.wait_marker("generateOnce: generate returned", 180, "ChatViewModel:I")
    m.perfetto_finish("S5_trace12", path, sec)

def scroll_trace():
    m.force_stop(); m.wait_cool(); m.wake(); m.logcat_clear()
    m.start({"measure_seed_long": True, "measure_fake": 10, "measure_fake_tokens": 300})
    m.wait_marker("fake_start", 20)
    path, sec = pstart("S7_trace12", 12)
    end = time.time() + 12
    i = 0
    while time.time() < end:
        m.swipe("up" if i % 2 == 0 else "down")
        i += 1
    m.perfetto_finish("S7_trace12", path, sec)

real_trace()
scroll_trace()
print("TRACES2 DONE")
