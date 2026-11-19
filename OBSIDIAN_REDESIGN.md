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