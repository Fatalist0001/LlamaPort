# AGENTS.md

Карта проекта для ИИ-агентов: что это, как собирать и проверять, где что лежит
и какие инварианты нельзя нарушать. Дополняет `README.md` (окружение) и
`OBSIDIAN_REDESIGN.md` (дизайн-система).

## Что это

LlamaPort — Android-приложение (Kotlin + Jetpack Compose, minSdk 26, только
`arm64-v8a`), которое запускает GGUF-модели **локально** через llama.cpp
(собственный нативный бинарь, JNI) и умеет подключать **облачные**
OpenAI-совместимые провайдеры. UI — собственная дизайн-система «Obsidian»:
тёмный фон, серебро, хайрлайны, mono-капсы, «технический» характер.

## Сборка и проверка

Всё окружение локальное (`toolchain/jdk`, `toolchain/gradle-8.9`, `.sdk/`,
`.gradle-home/`) — глобальные JDK/Gradle/SDK не нужны, скрипты выставляют
переменные сами.

| Команда | Назначение |
|---|---|
| `scripts/gradle.sh :app:compileDebugKotlin` | быстрая проверка компиляции |
| `scripts/gradle.sh :app:testDebugUnitTest` | unit-тесты (пока только cloud-слой, pure JVM) |
| `scripts/build-apk.sh` | debug APK → `LlamaPort-debug.apk` в корне |
| `scripts/build-release.sh` | подписанный release APK → `LlamaPort-release.apk` |
| `scripts/build-native.sh` | кросс-сборка llama.cpp под arm64 |
| `scripts/test-device.sh` | поставить debug APK на устройство с моделью, снять скриншот |

- `SKIP_NATIVE=1 scripts/build-apk.sh` — не пересобирать нативную часть
  (меняется редко; результат лежит в `app/src/main/jniLibs/`, в git не входит).
- Компиляция и тесты обязательны перед сдачей любых изменений; UI-тестовой
  инфраструктуры нет — изменения интерфейса проверяются сборкой + ручным
  прогоном на устройстве (`scripts/test-device.sh`).
- Подпись: два канала — debug (`LlamaPort-debug.apk`,
  `LlamaPort-release-debugsigned.apk`) и release (`LlamaPort-release*.apk`).
  Данные приложения сохраняются только при обновлении APK **той же подписью**;
  смешивать каналы нельзя. APK-файлы в корне — артефакты, в git не входят.

## Карта кода

`app/src/main/java/com/llamaport/app/`:

- `MainActivity.kt` — точка входа.
- `ChatScreen.kt` — корень UI: `LlamaPortApp` (Scaffold, drawer, шторки,
  поле ввода, робот-док), `StatusStrip`, `ChatInputBar`, `SessionRow`.
- `HomeChrome.kt` — топ-бар, drawer, пустой экран с роботом-героем,
  `robotStateFor()` (мэппинг UiState → настроение робота).
- `ChatViewModel.kt` — `UiState` (StateFlow), `StreamingAnswer` (вне UiState!),
  цикл инструментов, local/cloud генерация, сборка промпта
  (`buildTurns`/`buildPrompt`, base-cache), загрузка/выгрузка модели.
- `LlmService.kt` + `LlamaBridge.kt` — Android Service и JNI-мост к llama.cpp
  (`src/main/cpp/llama_jni.cpp`).
- `Tools.kt` — реестр инструментов (8 шт., файловые — в песочнице
  `files/sandbox/`, JS — в QuickJS), парсер протокола вызовов.
- `Thinking.kt` — разделение блоков `<think>` и видимого ответа.
- `ChatStore.kt` — сессии в `files/sessions.json`.
- `cloud/` — `CloudClient` (SSE), `CloudWire` (wire-формат), `CloudStore`
  (`files/cloud.json`), модели и editor UI в `ui/models/`.
- `ui/` — дизайн-система:
  - `theme/` — палитра Obsidian, типографика, формы;
  - `motion/` — `MotionTokens` (все тайминги/кривые), `Reveal`, `Disclosure`,
    `AnimatedNumber`, reduced-motion;
  - `components/` — `Obsidian.kt` (кнопки, теги, `DisclosureChevron` и др.),
    `SilverPulse`, `Progress`;
  - `robot/` — `AsciiRobot` (пиксельный робот: сетка из AppIcons.Robot,
    тикер ~20 fps, каталог поз в `RobotPerformance`), `RobotStage`,
    `RobotDock` (робот-спутник в чате), `RobotDemo` (скрытое демо, long-press);
  - `chat/` — `Messages.kt` (баблы, markdown, блоки инструментов),
    `Markdown.kt`;
  - `models/`, `settings/` — шторки выбора модели и настроек.

## Инварианты — не нарушать

1. **Стриминг не трогает UiState.** `StreamingAnswer` живёт отдельно именно
   затем, чтобы токен не рекомпозировал весь экран. Токенами интересуются
   только маленькие сабтри (бабл, док) — через `snapshotFlow` внутри
   эффектов, не чтением в композиции. Новые фичи следуют тому же правилу.
2. **Производительность важнее декора.** В этом коде каждый тик анимации
   обдуман: робот рисуется на собственном `graphicsLayer`, замирает через
   `active`, когда не на экране; списки не анимируются в покадровом режиме;
   слои сбрасываются после входной анимации. Не добавлять непрерывные
   анимации в скроллящиеся контейнеры.
3. **MotionTokens — единственный источник таймингов.** Сырых чисел в
   анимациях не должно быть; каждая анимация обязана иметь ветку reduced
   motion (`snap()` / короткий fade).
4. **Инструментальный протокол.** Вызов — XML-маркеры, один на ответ;
   парсер в `ToolCallParser`. При сборке промпта вызов модели реплеится в
   историю перед результатом (`buildTurns`) — без этого чейнинг ломается.
   Лимита шагов нет; есть только предохранитель от идентичных повторов
   (`MAX_IDENTICAL_TOOL_REPEATS`). У рассуждений перед ответом модель видит
   только ответ: `<think>` никогда не возвращается в контекст.
5. **Base-cache.** Системный блок промпта кэшируется на диск; при смене
   формата поднять `BASE_CACHE_FORMAT` в `ChatViewModel`.
6. **Языки.** Комментарии в коде — английские; строки UI — русские.
   Компоненты UI — `internal`/`private`; переиспользуемые живут в
   `ui/components/`.

## Ловушки

- `llama.cpp/`, `.sdk/`, `toolchain/`, `.gradle-home/`, `perf/` (трейсы) —
  локальные, в git не входят; свежая копия репо не соберёт APK без шагов
  «Подготовка окружения» из README.
- У облачных провайдеров нет нативного function calling — протокол вызовов
  тот же текстовый (маркеры), поэтому качество чейнинга зависит от
  инструкций в промпте.
- `robotStateFor` покрывает и героя, и drawer, и док — менять аккуратно.
- Модели и сессии лежат в приватной папке приложения (`files/`); удаление
  приложения или смена подписи канала их стирает.
