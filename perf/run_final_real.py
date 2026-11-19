#!/usr/bin/env python3
import json
from pathlib import Path
import run_measurements as rm
import run_final as rf

rm.RAW = rf.rm.RAW = Path(rm.ROOT / "perf/raw_final2")
rm.TRACES = rf.rm.TRACES = Path(rm.ROOT / "perf/traces_final2")
rf.stay_awake()
out = []
for rep in (2, 3):
    out.append(rf.real(rep))
    print("done", out[-1], flush=True)
(rm.ROOT / "perf/final_real_extra.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))
