# Phase 1: streaming UI cost

Follow-up to `perf/REPORT.md`. That report measured where the app loses frames
while an answer streams in; this one records what was changed, what it bought,
and where it did not help.

Everything below is measured on the same device (HONOR RMO-NX1, 1080×2400,
120 Hz, Android 13), release build with R8, installed over adb.

## What changed

1. **The live answer moved out of `UiState`.**
   Previously every token replaced the whole message list in `MutableStateFlow`,
   which woke up the scaffold, both bars, the drawer and every visible bubble,
   and it also rebuilt the session preview. Now the text lives in
   `StreamingAnswer` (`ChatViewModel.kt`), which only the streaming bubble reads.
   The message text is committed to `ChatMessage.text` once, when the answer
   completes.

2. **Writes are coalesced to one per display frame.**
   Tokens arriving from the inference callback are buffered and flushed on the
   main thread at most once per 8 ms (`LIVE_FLUSH_MS`). Prompt progress is
   throttled to 10 Hz and tok/s to 4 Hz. During generation the `progress` state
   is no longer written at all — the status line uses `generating`.

3. **Auto-scroll follows the live revision, not the committed text length.**
   The effect observes `live.revision` inside `snapshotFlow`, so the observation
   does not recompose the list.

4. **Tool blocks are bounded.**
   `read_file` can return 256 KB. Expanding such a result in a single `Text`
   produced a **200 ms frame**. The expanded view now renders the head of the
   text (8 000 characters) inside a height-bounded scrollable box; arguments get
   the same treatment. The full text stays in the message and still goes to the
   model.

Not changed in this phase: Markdown parsing (still `remember(text) {
parseMarkdown(text) }`), the live thinking block, and the decorative animations.
Those are Phase 2–3; see the list at the end.

## Results

Medians of the final build against the baseline from `perf/summary.json`.
Frame percentiles come from the `gfxinfo` HISTOGRAM (this Android only returns
the last 10 frames in `framestats`).

| scenario | jank % | p90 ms | p99 ms | main CPU % | frames |
|---|---|---|---|---|---|
| S3 fake 10 tok/s, before | 4.05 | 14 | 22 | 39.2 | 3607 |
| S3 fake 10 tok/s, after | **0.08** | **10** | **14** | **30.3** | 3664 |
| S4 fake 25 tok/s, before | 8.20 | 16 | 27 | 50.0 | 3571 |
| S4 fake 25 tok/s, after | **0.27** | **10** | **15** | **37.5** | 3692 |
| S5 real LFM3, before | 1.66 | 12 | 21 | 38.0 | 3983 |
| S5 real LFM3, after | 3.23 | 14 | 21 | 38.9 | 4013 |

Reps: S3/S4 one rep each on the final build (the effect is 10–30×, far outside
run-to-run noise); S5 three reps (3.15 / 4.34 / 3.23 % jank).

### Tool rendering

| case | before | after |
|---|---|---|
| expand a 200 KB `read_file` result, worst frame | 200 ms | **35 ms** |
| same, p99 | 200 / 150 ms | **19 / 27 ms** |
| same, jank | 3.87 / 2.16 % | 1.46 / 2.96 % |
| scroll the chat with the block expanded | — | 0.56 % jank, p99 9 ms, 358 frames |
| tool round (stream JSON → card → 200 KB result → answer) | 0.14 % jank, p99 12 ms | unchanged, p99 13 ms |

The 200 ms frame in the baseline trace was `traversal` 193 ms,
`Record View#draw()` 192 ms, `AndroidOwner:measureAndLayout` 137 ms — one
layout of one `Text` with 100 000 characters, inside an item taller than the
screen. After the change the worst frame is the first layout of 8 000 characters.

### Traces

Main thread, same 12 s windows (`perf/traces/`, `perf/traces_final2/`):

| | S3 before | S3 after | S5 before | S5 after |
|---|---|---|---|---|
| `Recomposer:recompose`, per frame | 0.84 ms | **0.65 ms** | 1.04 ms | **0.82–0.86 ms** |
| `traversal`, per frame | 2.63 ms | **1.93 ms** | 2.68 ms | 2.5–3.2 ms |
| `draw`, per frame | 2.40 ms | **1.73 ms** | 2.38 ms | 2.4–2.9 ms |
| main thread `Running` | 36 % | **33.5 %** | 45 % | **40–41 %** |

The per-frame recompose cost is the part Phase 1 targets, and it dropped by
18–23 %. The absolute `traversal`/`draw` numbers for S3 fell with it; for S5 they
did not, for the reason below.

### Why S5 did not improve

Real generation is RenderThread-bound, not main-thread-bound: in the baseline
S5 trace `RenderThread` was busy 68 % of the window against main-thread
`Running` 45 %. Making the main thread cheaper means it now submits frames
faster into a pipeline that is already saturated by drawing the growing answer,
so p90 moves 12 → 14 ms while p99 stays flat at 21 ms and the main thread costs
less. Jank at the 16.7 ms threshold rises from 1.66 % to 3.23 %, which is
±1.5 pp on a scenario whose frames are already mostly 8–14 ms with heavy buffer
stuffing.

That is the Phase 3 target, not a Phase 1 regression: the fix is to draw less
per frame (cap the live thinking block, cheaper text), not to make the main
thread faster.

## Limits of these numbers

- S3/S4 were re-measured once on the final build; S5 three times. The device
  generated at 6.1–7.5 tok/s across the final runs versus 7.7 tok/s in the
  baseline, which is device state, not app code.
- `Janky frames` uses the 16.7 ms threshold while the panel runs at 120 Hz
  (8.3 ms deadline), so a 9 ms frame counts as smooth. The p90/p99 columns and
  the HISTOGRAM are the meaningful numbers.
- The tool scroll scenario first produced 3 frames because the screen had turned
  off; the fixed runner keeps the display awake (`svc power stayon true`) and
  now reports 357–358 frames.

## Raw data

| Path | Content |
|---|---|
| `perf/raw_final2/` | final build: S3, S4, S5 ×3, tool expand, tool scroll ×2 |
| `perf/raw_tool/`, `perf/raw_tool_after2/` | tool expand before / after the fix |
| `perf/traces_final2/` | final build traces (S5 ×3) |
| `perf/traces_final/` | short S3/S5 traces of the near-final build |
| `perf/traces_tool*/` | tool expand and scroll traces |
| `perf/summary.json`, `perf/summary_phase1.json`, `perf/phase_final/summary.json` | per-scenario medians |
| `perf/trace_summary*.json` | main-thread slices, thread states, frame timeline |
| `run_final.py`, `run_tool.py`, `run_phase1.py` | runners; `analyze_frames.py`, `analyze_traces.py` take `PERF_RAW`/`PERF_TRACES` |

Binaries (`raw*/`, `traces*/`) are not committed — the traces alone are ~800 MB.
Re-run the scripts to regenerate them.

## Next

1. **Incremental Markdown** — keep finished blocks stable, parse only the open
   tail; plain `BasicText` for text without markup. Should cut the remaining
   per-frame `Record View#draw()` in S5.
2. **Cap the live thinking block** — fixed height or last N lines while
   streaming, static dots and avatar. Target: RenderThread 68 % → ≤50 %.
3. **Cheaper animations, not fewer** — 30–60 Hz for the pulsing dot and cursors,
   or their own layer. Removing them entirely was measured as worse
   (`S3_no_anim`: 580 frames in 30 s instead of 3607).