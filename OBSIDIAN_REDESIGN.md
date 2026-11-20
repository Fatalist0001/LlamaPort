# LlamaPort — Obsidian redesign

Partial, deep visual redesign of the existing Android client. The architecture,
inference path, persistence format and user flows stay untouched.

## Current inventory (verified against the code, `obsidian-design`, not REDESIGN.md)

| Area | File | State today |
|---|---|---|
| Theme | `ui/theme/Theme.kt`, `ui/theme/Shapes.kt` | Fixed Obsidian dark/daylight palettes, 10/14/18/24/28 radii |
| Motion | `ui/motion/*` | MotionTokens, Reveal, pressable, AnimatedNumber, ReducedMotion, disclosure |
| Robot | `ui/robot/AsciiRobot.kt`, `RobotPerformance.kt`, `RobotState.kt` | 24×24 ASCII raster of `AppIcons.Robot`, adaptive 60/120 ms ticker, 20 idle actions and 16 reactions |
| Home chrome | `HomeChrome.kt` | Custom top bar, model chip, optimized drawer, static robot stage and quick-action cards |
| Chat screen | `ChatScreen.kt` | Scaffold, status strip, M3 input bar, send/stop, drawer sessions |
| Messages | `ui/chat/Messages.kt` | tonal bubbles, free assistant layout, thinking block, tool block, metrics dialog |
| Markdown | `Markdown.kt` | small parser, flat code block, no syntax highlight, no copy button |
| Models | `ui/models/ModelsSheet.kt` | RAM bar, swipe cards, fit label, projectors |
| Settings | `ui/settings/SettingsSheet.kt` | Separate model-load and next-request generation panels, shared Obsidian controls and exact number fields |
| State | `ChatViewModel.kt`, `ChatStore.kt` | `UiState`, `StreamingAnswer` (live text outside state), no load progress |

Constraints discovered:
- Model loading exposes only `loadingModel: Boolean` — no real percentage, so the
  new loading UI is explicitly indeterminate.
- Live t/s, prompt progress, RAM, mmproj, vision/audio and model metadata are real
  and must stay the only sources for the metrics shown.
- The streaming answer is deliberately outside `UiState`; the redesign must keep
  token updates local to the message and input bar.
- `LocalReducedMotion` follows the system animator scale live.
- The pixel robot is the brand mark and stays; the final home hero places it on
  a static silver precision stage rather than a continuously animated Core.

## Design system

- Fixed palette (no wallpaper-derived Material You), centralised in
  `ui/theme/Color.kt` + `ObsidianPalette` CompositionLocal:
  #08090B / #111317 / #171A1F / #20242A / #292D34, text #E8E9ED / #999DA7 /
  #686D77, silver #D7D9E0, success #9BC9AE, warning #D9B77E, error #D98C91.
- A high-key “Obsidian Daylight” variant for system light mode, same geometry,
  monochrome ink/silver identity; code and the Core stay polished black in both.
- Expressive type scale in `ui/theme/Type.kt`; system sans for UI, monospace for
  all technical numbers/labels/code.
- Radii 10 / 14 / 18 / 24 / 28 dp; pill for status, 20/6 dp chat bubble.
- Reusable primitives in `ui/components/Obsidian.kt`: hairline, card, chip,
  icon button, primary/secondary button, switch, slider, text field, eyebrow.
- Extended motion tokens + `SilverPulse` overlay, no second motion system.

## Stage plan

1. **Design system** — new palette/typography/shapes/theme, primitives, motion
   tokens, silver progress/pulse, window background. Files: `ui/theme/*`,
   `ui/components/Obsidian.kt`, `ui/motion/*`, `res/values*/colors.xml`.
2. **Home** — custom `ObsidianTopBar`, expressive model chip with state halo,
   `ObsidianCore` hero (Canvas prism, silver rim, orbiting precision arcs, robot
   seal, idle/input/generating/completion/error reactions), compact quick-action
   cards, premium input field, send↔stop morph, silver progress line, status
   strip. Files: `HomeChrome.kt`, `ui/components/ObsidianCore.kt`,
   `ui/components/Progress.kt`, `ChatScreen.kt`.
3. **Chat** — graphite user bubbles, free assistant typography, redesigned
   thinking block with live silver activity line, code surface with lexer,
   language header and copy button, generation pulse line, message metrics chrome.
   Files: `ui/chat/Messages.kt`, `Markdown.kt`, `Thinking.kt` (if needed),
   `ui/AppIcons.kt`, `ChatScreen.kt`.
4. **Models** — segmented animated RAM with mono readouts, technical model cards
   (name/quant/params/size/vision/audio/need/fit/loading/active border),
   indeterminate load sequence with dimmed siblings and silver success pulse,
   styled memory warning dialog, projector and unload/import controls. Files:
   `ui/models/ModelsSheet.kt`, `ChatScreen.kt`.
5. **Drawer + settings** — layered drawer with scrim, sequential content reveal,
   active chat silver rail, refined rename/delete, sticky-new-chat header;
   settings panels, custom slider/switch/segmented controls, prompt editor,
   animated values, disclosure motion. Files: `HomeChrome.kt`, `ChatScreen.kt`,
   `ui/settings/SettingsSheet.kt`.
6. **Polish** — reduced-motion audit, hidden-screen animation stop, streaming
   performance (no list-wide recomposition), device screenshots of every screen,
   debug + release APK, final commit.

Each stage ends with `:app:compileDebugKotlin`, an APK build, and a separate
commit. Functional flows are exercised on the connected device (RMO-NX1).
## Implemented

| Stage | Commit | Result |
|---|---|---|
| 1. Design system | `e990c98` | Fixed Obsidian palette (no dynamic Material You), Obsidian Daylight light variant, precision type scale, mono value styles, 10/14/18/24/28 radii, `ObsidianBackground`, hairlines, buttons, chips, switch, slider, tags, progress line, silver pulse. |
| 2. Home | `71c2ce3` | Custom top bar; technical model chip with state halo; `ObsidianCore` Canvas hero (three polished slabs, silver rim, reflection sweep, precision ticks, engraved robot seal, local completion flash, input reaction); compact quick-action cards; premium composer with focus rim, speed chip and send/stop morph; honest prompt progress. |
| 3. Chat | `d6a6493` | Graphite user bubbles with hairline; free assistant layout with LlamaPort header; Markdown hierarchy, muted syntax highlighting, code surface with language header and copy button; thinking panel with live silver activity and collapse; tool panel; streaming shimmer and cursor; mono metrics and redesigned metrics dialog. |
| 4. Models | `f1c4381` | Segmented animated RAM panel with legend readouts; technical GGUF cards (size, quant, parameters, vision/audio, mmproj, need, fit, active state); indeterminate load sequence with dimmed siblings and success pulse; load sheet stays open until the result; redesigned memory warning with need/available comparison. |
| 5. Drawer + settings | `0d24359`, `2947e66` | Layered drawer, robot tile, staged content reveal, active chat silver rail, compact rename/delete, settings card; three Obsidian settings panels, prompt editor, custom sliders/switches/checks/segments, exact mono inputs, animated values, mono backend line. |
| 6. Polish | `2e7e3d4`, `596fb9f`, `2604824`, `af2a9d4` | Reduced-motion and lifecycle audit, adaptive robot ticker, short-screen scrolling, light theme pass, signed builds, and Perfetto-driven drawer scroll optimization. |

### Design decisions

- **Single identity, two light levels.** The core palette is fixed Obsidian; system dark mode uses it directly. System light mode uses an airy “Obsidian Daylight” ink/silver variant. Code surfaces and the robot stage retain their graphite/silver identity in both modes.
- **No second motion system.** All new effects use the existing `MotionTokens`, `Reveal`, `pressable`, `disclosureEnter/Exit` and `LocalReducedMotion`. `SilverPulseOverlay` and the progress/shimmer lines are additional primitives built on the same tokens.
- **The robot is the hero.** The temporary animated Core was retired. The pixel robot from `main` now sits directly on a static Canvas stage with a silver reflection, precision ring and hairline floor.
- **Honest metrics.** Prompt progress uses the real processed/total values. Model load has no native percentage, so the loading card and header show an indeterminate silver line, never a fake percent. Generation speed, token counts and RAM values still come from the existing state.
- **Streaming cost.** The streaming answer remains outside `UiState`. New motion reads animation state in draw phase where possible. During generation only a small header dot, the answer shimmer line and the status shimmer line are continuous; the speed chip and status dots are static. The completion pulse is one-shot.
- **Reduced Motion.** All infinite decorative motion (robot ticker, pulses, shimmers, cursor) is disabled when `LocalReducedMotion` is true; short fades and layout changes remain.
- **Small screens.** The home composition centers when it fits and becomes vertically scrollable when it does not.

### Verification performed on the connected device

- Kotlin compilation and debug APK build after every stage; R8 release builds at the end, signed with the project key and verified to install/launch (debug-signed variant).
- GGUF import through the system picker (temporary test file), card refresh, and long-press deletion. This exposed a real bug: swipe/remember state in the plain model Column was position-based, so importing a file could move the delete callback to the neighbouring card. Fixed with `key(file.absolutePath)` in `ModelsSheet.kt` (`596fb9f`), then re-verified import and deletion with the correct target.
- Model load/unload, mmproj auto-pairing (Omni + projector), active model state.
- New chat, empty state, drawer navigation, session rename.
- Session delete and snackbar undo path (delete verified; undo logic unchanged).
- Streaming generation, real tok/s metrics, stop generation.
- Markdown headings/lists/inline formatting; fenced Kotlin code; copy button with “ГОТОВО”.
- Thinking block expand/collapse and live state.
- Tool block collapsed/expanded with bounded 8 000-character output.
- Image attachment rendering.
- Settings: exact number commit, threads slider drag, flash-attention segment, tools disclosure, switches.
- Memory warning (need 4.9 GB / free 2.2 GB) and cancel path.
- Reduced motion: two home screenshots two seconds apart differ only in the system battery area.
- Light mode: home, models and settings rendered with the Daylight palette; dark restored afterwards.
- Idle frame check after generation: 14 frames in six seconds (no persistent animation).

### Drawer scroll performance follow-up

Live `gfxinfo` A/B tests initially ruled out `animateItem`, the small drawer robot,
and the row press animations as the primary cause. A Perfetto trace then exposed
the actual bottleneck: the rounded full-height `ModalDrawerSheet` forced the
changing drawer into an **886×2274 offscreen texture**, and that texture was
uploaded again on nearly every scroll frame.

Commit `af2a9d4` fixes the rendering path without removing chat actions or
swipe-to-delete:

- the full-height sheet uses a rectangular surface, avoiding the oversized clip layer;
- staggered `DrawerPart` graphics layers exist only during the entrance animation and
  are removed as soon as the reveal settles;
- the 32 dp header robot pauses while the history `LazyColumn` is being dragged or flung;
- row action buttons retain ripple and click haptics but skip their individual
  press-scale animation states and graphics layers.

Measured on the only target device, HONOR RMO-NX1, using an R8 release payload
compiled with ART `speed` mode:

| Metric | Before | Final |
|---|---:|---:|
| Janky frames | 10.4–12.5% | **1.7–2.7%** |
| 50th percentile | 14 ms | **10–12 ms** |
| 90th percentile | 17–18 ms | **12–14 ms** |
| 95th percentile | 18–19 ms | **13–15 ms** |
| 99th percentile | 21–26 ms | **17–18 ms** |
| Missed Vsync | 0–3 | **0–1** |

Perfetto confirmed the mechanism, not just the visible improvement: average
`Drawing` time fell from 6.72 to 4.46 ms, `flush commands` from 4.23 to 2.43 ms,
and main-thread `traversal` from 1.84 to 0.85 ms. The 886×2274 upload disappeared.
The remaining small 877×454 upload averaged about 0.25 ms and is below the
threshold that affected scrolling. Device coverage is intentionally limited to
the single available target handset.

### Separate model and generation settings

The drawer entry is now **«Настройки модели»**; a new tuning-sliders button
immediately left of the top-bar **«+»** opens **«Параметры генерации»** on both
home and chat. Each destination is its own scrollable bottom-sheet screen.
One nullable settings destination prevents overlapping model/generation panels;
opening the model chooser closes the settings destination, and vice versa.

The existing `Settings` object and `updateSettings` remain the only source of
truth. No native, IPC, chat-persistence or cache-fingerprint changes are needed:

| Applies on load/reload only | Applies to the next new request without reload |
|---|---|
| `contextSize` | `systemPrompt` |
| `threads`, `threadsBatch` | `temperature`, `topP`, `topK` and temperature presets |
| `nBatch`, `nUbatch` | `maxTokens`, `thinkingBudget` |
| `flashAttn`, `useMmap`, `kvType` | `toolsEnabled`, `enabledTools`, `httpToolEnabled` |

The model screen retains the context/RAM estimate, memory warnings, mmap/KV
controls and backend information. Its reload action is outside collapsed panels
and is disabled when the model is absent, loading or generating. Editing a load
setting does not automatically reload the model. Thread counts, including
«Потоки генерации», stay here because the current IPC passes them only to `load`.

The generation screen retains the prompt editor, presets, exact sampling and
limit controls, and a separate tools disclosure. It is available without a loaded
model and while an answer is running. Its notice says settings apply to the
**next request**: the whole current answer, including all tool rounds, keeps its
original settings snapshot. There is no reload button on this screen.

Both screens reuse the original private controls, ranges, defaults, rounding,
reduced-motion behavior and exact-number commits. The existing normalization
`nUbatch <= nBatch` and the joint `http_get` permission/enabled-tool update are
preserved. Splitting the UI does not add per-chat settings or disk persistence.

Verification for the split:

- Static assertions confirmed all 8 load-only and all 9 next-request fields are
  exclusive to their intended screen. Existing private controls and helper
  implementations are byte-identical to the preceding revision.
- Both Kotlin variants, debug packaging, R8 release packaging and release vital
  lint passed. Independent source review found no blocking regressions.
- USB smoke tests on HONOR RMO-NX1 exercised both entries, panel disclosure and
  closing, the generation presets, the separate eight-tool list, precise token
  entry (73 stays 73 rather than snapping to the slider step), and persistence
  of changed values across closing/reopening the panels within the ViewModel.
- Reducing the prompt batch from 512 to 64 also reduced the microbatch to 64;
  the reload action remained accessible with both model sections collapsed and
  was disabled without a loaded model.
- With the existing small Qwen model loaded once, the first test request used
  `maxNew=512`; changing the generation limit made the next request use
  `maxNew=64`. No load/reload call occurred during that change, and the inference
  process stayed the same. The generation screen also opened during an answer
  and showed the notice about retaining the current request's settings snapshot.
- The final release UI was checked in dark and daylight themes, with reduced
  motion and 1.3x font scaling. The top-bar status now ellipsizes like the model
  filename. Device font scale, animation scale and night mode were restored.
- Existing user chats were neither deleted nor renamed. Only the uniquely
  identified temporary four-message test chat was removed, and the previously
  selected user chat was restored. The final compatible release APK was installed.

### Build artifacts and signing

All APKs are signed with the modern APK signature schemes (v2 + v3):

- `LlamaPort-debug.apk` — debug build, standard Android debug certificate. Installs over an existing debug-signed install.
- `LlamaPort-release-debugsigned.apk` — R8/minified release payload, signed with the debug certificate so it updates an existing debug install without wiping app data.
- `LlamaPort-release.apk` — R8/minified release payload signed with the project release key (`CN=LlamaPort`) for distribution.
- `LlamaPort-release-official.apk` — byte-for-byte copy of the current official release artifact, kept as an explicit archival/distribution name.

All four root artifacts include the drawer performance fix and the settings-screen split. Debug and R8 debug-signed release were rebuilt from the final UI source; the compatible release was installed and smoke-tested over USB. The official release was packaged with the project key, and its explicit official copy was verified byte-for-byte identical. All four artifacts passed v2/v3 signature verification. Official-release installation over the debug-signed app was not attempted because the certificates differ. The project build explicitly enables v2/v3 signing in `app/build.gradle.kts`; some vendor installers labelled the older v2-only debug package as “not signed”.

## Robot performance (home hero)

The animated Obsidian Core object was retired from the empty home; the pixel robot from `main` is now the central hero on a static stage (`RobotStage`: silver reflection, precision ring, hairline floor, no animation of its own).

`ui/robot/RobotPerformance.kt` adds a deterministic pose catalogue:

- **20 idle behaviours:** Blink, DoubleBlink, LookLeft, LookRight, LookUp, LookDown, LookAround, EyeRoll, Squint, Happy, Wink, Sad, Angry, Curious, Nod, ShakeHead, Yawn, AntennaTwitch, Glitch, Sway.
- **16 tap reactions:** Hop, Giggle, Wink, LookAtTap, Surprised, Dizzy, Annoyed, Shy, Laugh, No, Yes, RollEyes, Glitch, Sleepy, Spark, Tilt.

Both lists are drawn as complete weighted shuffle cycles: every variant appears once before any repeat. Taps are normalised against the robot centre, so `LookAtTap`/`Tilt` (and the head tilt) follow the pressed spot. A light haptic confirms every reaction.

Mechanics:

- one 16 fps ticker (~60 ms) drives breathing, cell effects and the scheduler; no 60 fps transitions on home;
- idle performance runs in `Idle` **and** `Sleeping` (dim body, bright animated eyes/mouth); loading/thinking/generating/error keep their functional overlays;
- `lively = false` for the 32 dp drawer robot keeps that header calm; its base ticker also pauses while the history list scrolls;
- reduced motion disables the ticker and the whole performance; two home screenshots 2 s apart differ by 0 pixels outside the status bar;
- the footer drawer robot and all chat avatars are unchanged; no new dependencies or assets.

Verification on the device: 17 distinct idle behaviours were observed in 90 s (the cycle guarantees all 20), and 20 taps produced all 16 reactions. The home robot is drawn at 168 dp inside a 272 dp stage; the old 60 fps `ObsidianCore` is no longer in the composition.

### Robot follow-up (after the first push)

- **Haptics on every tap**, including reduced motion and Loading/Error states. Long-press has its own haptic.
- **Lifecycle-aware ticker:** the 16 fps clock runs inside `repeatOnLifecycle(STARTED)`. When the app is backgrounded, the ticker is cancelled; on return it resumes. Measured: 4 frames during the 6 s after HOME, versus a continuously ticking robot before.
- **Adaptive idle clock:** 60 ms during gestures/reactions, 120 ms between them for breathing only. Idle frame rate fell to ~9.5 fps; the installed R8 debug-signed build measured ~14% of one core and ~8.5 redraws/s, versus ~28–39% and a fixed 16 fps on the debug build.
- **Accessibility:** the robot now exposes `contentDescription = "Робот LlamaPort"`, button role and an `onClick` label.
- **Verification:** all 16 reactions observed across 20 taps; 18–19 distinct idle behaviours observed per minute, the shuffle cycle guarantees all 20 across a full cycle; model-loaded `Idle` verified (`sleeping=false` actions) as well as the no-model `Sleeping` state; reduced motion again produced 0 differing app pixels; long-press demo opens; light theme renders without a crash; background pause verified.
