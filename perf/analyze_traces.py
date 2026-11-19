#!/usr/bin/env python3
import csv, json, os, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TP = ROOT / "build/trace_processor_shell"
TRACES = Path(os.environ.get("PERF_TRACES", ROOT / "perf/traces"))
OUT = Path(os.environ.get("PERF_TRACE_OUT", ROOT / "perf/trace_summary.json"))

def q(trace, sql):
    p = subprocess.run([str(TP), "query", "--quiet", str(trace), sql], capture_output=True, text=True)
    if p.returncode != 0:
        return [], p.stderr.strip().splitlines()[-1] if p.stderr else "error"
    rows = list(csv.reader(p.stdout.splitlines()))
    return rows, ""

def main():
    out = {}
    for trace in sorted(TRACES.glob("*.pftrace")):
        name = trace.stem
        rows, err = q(trace, "select p.pid,p.name,p.upid,t.utid,t.tid,t.name from process p join thread t using(upid) where p.name like '%llamaport%';")
        if err or len(rows) < 2:
            out[name] = {"error": err or "no app process"}
            continue
        hdr, data = rows[0], rows[1:]
        app = None
        for r in data:
            if r[1] == "com.llamaport.app":
                app = r; break
        if app is None:
            out[name] = {"error": "no UI process", "threads": data[:5]}
            continue
        pid, pname, upid, main_utid, main_tid, _ = app
        info = {"pid": pid, "upid": upid, "main_tid": main_tid, "main_utid": main_utid}
        # main thread top slices
        rows, err = q(trace, f"select s.name,count(*) n,sum(s.dur)/1e6 total_ms,max(s.dur)/1e6 max_ms from slice s join thread_track tt on s.track_id=tt.id where tt.utid={main_utid} group by 1 order by total_ms desc limit 40;")
        info["main_slices"] = rows
        # thread states
        rows, err = q(trace, f"select state,count(*) n,sum(dur)/1e6 ms from thread_state where utid={main_utid} group by 1 order by ms desc;")
        info["main_states"] = rows
        # per-cpu running for main
        rows, err = q(trace, f"select cpu,sum(dur)/1e6 ms from sched_slice where utid={main_utid} group by 1 order by cpu;")
        info["main_cpu"] = rows
        # all app threads CPU
        rows, err = q(trace, f"select t.name,t.tid,sum(ss.dur)/1e6 cpu_ms from sched_slice ss join thread t using(utid) where t.pid={pid} group by 1,2 order by cpu_ms desc limit 25;")
        info["app_threads"] = rows
        # all app sched per cpu
        rows, err = q(trace, f"select ss.cpu,sum(ss.dur)/1e6 busy_ms from sched_slice ss join thread t using(utid) where t.pid={pid} group by 1 order by 1;")
        info["app_cpu"] = rows
        # llm process threads
        rows, err = q(trace, f"select t.name,t.tid,sum(ss.dur)/1e6 cpu_ms from sched_slice ss join thread t using(utid) join process p using(upid) where p.name like '%llm%' group by 1,2 order by cpu_ms desc limit 25;")
        info["llm_threads"] = rows
        # compose / frame slices
        rows, err = q(trace, f"select s.name,count(*) n,sum(s.dur)/1e6 total_ms,max(s.dur)/1e6 max_ms from slice s join thread_track tt on s.track_id=tt.id where tt.utid={main_utid} and (s.name like 'Compose%' or s.name like 'Recomposer%' or s.name like '%recompose%' or s.name like 'Choreographer%' or s.name like '%measure%' or s.name like '%layout%' or s.name like '%draw%' or s.name like 'traversal%') group by 1 order by total_ms desc limit 40;")
        info["compose_slices"] = rows
        # frame timeline
        rows, err = q(trace, f"select jank_type,count(*) n from actual_frame_timeline_slice where upid={upid} group by 1 order by n desc;")
        info["frame_timeline"] = rows
        # GC slices
        rows, err = q(trace, f"select s.name,count(*) n,sum(s.dur)/1e6 total_ms from slice s join thread_track tt on s.track_id=tt.id where tt.utid={main_utid} and (s.name like '%GC%' or s.name like 'gc%') group by 1 order by total_ms desc limit 20;")
        info["gc_slices"] = rows
        out[name] = info
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2))
    for name, info in out.items():
        print("==", name, info.get("error", ""))
        if "main_slices" in info:
            print("main slices", info["main_slices"][:8])
            print("states", info["main_states"][:5])
            print("frames", info["frame_timeline"][:5])
    return out

if __name__ == "__main__":
    main()
