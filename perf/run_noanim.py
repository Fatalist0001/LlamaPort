import sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_measurements as m

for rep in (1,2,3):
    m.scenario_fake(rep, {"measure_fake": 10, "measure_fake_tokens": 300, "measure_no_logo": True}, "S3_no_anim", 300, 10)

name="S5_no_anim_1"
m.force_stop(); m.wait_cool(); m.wake(); m.logcat_clear()
m.start({"measure_real": True, "measure_no_logo": True})
m.wait_marker("real_first_token", 180)
m.gfx_reset()
top=m.top_start(name, samples=18, interval=2)
m.wait_marker("generateOnce: generate returned", 180, "ChatViewModel:I")
m.gfx_dump(name); m.meminfo(name); m.logcat_dump(name)
m.top_stop(top)
print("NOANIM DONE")
