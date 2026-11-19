# LlamaPort — Obsidian redesign

Partial, deep visual redesign of the existing Android client. The architecture,
inference path, persistence format and user flows stay untouched.

## Current inventory (verified against the code, `obsidian-design`, not REDESIGN.md)

| Area | File | State today |
|---|---|---|
| Theme | `ui/theme/Theme.kt`, `ui/theme/Shapes.kt` | Material You dynamic color, indigo fallback, 8/12/16/22/28 radii |
| Motion | `ui/motion/*` | MotionTokens, Reveal, pressable, AnimatedNumber, ReducedMotion, disclosure |
| Robot | `ui/robot/AsciiRobot.kt`, `RobotAvatar.kt`, `RobotState.kt` | 24×24 ASCII raster of `AppIcons.Robot`, ticker at 13 fps, all states |
| Home chrome | `HomeChrome.kt` | `TopAppBar`, pill model chip, drawer, empty state with robot + SuggestionChips |
| Chat screen | `ChatScreen.kt` | Scaffold, status strip, M3 input bar, send/stop, drawer sessions |
| Messages | `ui/chat/Messages.kt` | tonal bubbles, free assistant layout, thinking block, tool block, metrics dialog |
| Markdown | `Markdown.kt` | small parser, flat code block, no syntax highlight, no copy button |
| Models | `ui/models/ModelsSheet.kt` | RAM bar, swipe cards, fit label, projectors |
| Settings | `ui/settings/SettingsSheet.kt` | 3 disclosures, M3 sliders/switches/chips, exact number fields |
| State | `ChatViewModel.kt`, `ChatStore.kt` | `UiState`, `StreamingAnswer` (live text outside state), no load progress |

Constraints discovered:
- Model loading exposes only `loadingModel: Boolean` — no real percentage, so the
  new loading UI is explicitly indeterminate.
- Live t/s, prompt progress, RAM, mmproj, vision/audio and model metadata are real
  and must stay the only sources for the metrics shown.
- The streaming answer is deliberately outside `UiState`; the redesign must keep
  token updates local to the message and input bar.
- `LocalReducedMotion` follows the system animator scale live.
- The pixel robot is the brand mark and stays; the new home hero adds a separate
  Obsidian Core composition around it.

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
| 6. Polish | `2e7e3d4`, `596fb9f` | Reduced-motion audit, concurrent-animation reduction during streaming, short-screen scrolling for the empty state, light theme pass, device screenshots, signed debug + release builds. |

### Design decisions

- **Single identity, two light levels.** The core palette is fixed Obsidian; system dark mode uses it directly. System light mode uses an airy “Obsidian Daylight” ink/silver variant. Code surfaces and the Core stay polished black in both modes.
- **No second motion system.** All new effects use the existing `MotionTokens`, `Reveal`, `pressable`, `disclosureEnter/Exit` and `LocalReducedMotion`. `SilverPulseOverlay` and the progress/shimmer lines are additional primitives built on the same tokens.
- **The Core is not a glowing orb.** It is a matte stacked-octagon object (Canvas paths and gradients only, no bitmap/3D dependency), lit by thin silver edge gradients. The pixel robot is the brand seal inside it.
- **Honest metrics.** Prompt progress uses the real processed/total values. Model load has no native percentage, so the loading card and header show an indeterminate silver line, never a fake percent. Generation speed, token counts and RAM values still come from the existing state.
- **Streaming cost.** The streaming answer remains outside `UiState`. New motion reads animation state in draw phase where possible. During generation only a small header dot, the answer shimmer line and the status shimmer line are continuous; the speed chip and status dots are static. The completion pulse is one-shot.
- **Reduced Motion.** All infinite decorative motion (Core drift, robot ticker, pulses, shimmers, cursor) is disabled when `LocalReducedMotion` is true; short fades and layout changes remain.
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

### Build artifacts and signing

All APKs are signed with the modern APK signature schemes (v2 + v3):

- `LlamaPort-debug.apk` — debug build, standard Android debug certificate. Installs over an existing debug-signed install.
- `LlamaPort-release-debugsigned.apk` — R8/minified release payload, signed with the debug certificate so it updates an existing debug install without wiping app data.
- `LlamaPort-release.apk` — R8/minified release payload signed with the project release key (`CN=LlamaPort`) for distribution.

The project build now explicitly enables v2/v3 signing in `app/build.gradle.kts`; some vendor installers labelled the older v2-only debug package as “not signed”.
