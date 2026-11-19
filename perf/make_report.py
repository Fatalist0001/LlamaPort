import json, statistics as st, re
from pathlib import Path

root=Path(__file__).resolve().parent
d=json.load(open(root/'summary.json'))
raw=root/'raw'

def med(name, key, default=0):
    return d.get(name,{}).get('median',{}).get(key,default)

def fmt(v, nd=2):
    if isinstance(v,float): return f"{v:.{nd}f}"
    return str(v)

# cold start summary
tt=[]; wt=[]; disp=[]
for p in sorted(raw.glob('S1_cold_*_am_start.txt')):
    t=p.read_text()
    m=re.search(r'TotalTime: (\d+)',t); w=re.search(r'WaitTime: (\d+)',t)
    if m: tt.append(int(m.group(1)))
    if w: wt.append(int(w.group(1)))
for p in sorted(raw.glob('S1_cold_*_logcat.txt')):
    m=re.search(r'Displayed com\.llamaport\.app/\.MainActivity: \+([0-9.]+)ms',p.read_text())
    if m: disp.append(float(m.group(1)))

# S5 real speed
speeds=[]; firsts=[]
for p in sorted(raw.glob('S5_real_[123]_logcat.txt')):
    t=p.read_text()
    m=re.search(r'(\d\d:\d\d:\d\d\.\d+).*Measure : real_send',t)
    f=re.search(r'(\d\d:\d\d:\d\d\.\d+).*Measure : real_first_token',t)
    e=re.search(r'(\d\d:\d\d:\d\d\.\d+).*LlmService: generate done: generated=256',t)
    # easier: parse epoch from threadtime? use seconds difference via HH:MM:SS
    def sec(x):
        h,m,s=x.split(':'); return int(h)*3600+int(m)*60+float(s)
    if m and f:
        firsts.append(sec(f.group(1))-sec(m.group(1)))
    if f and e:
        speeds.append(256/(sec(e.group(1))-sec(f.group(1))))

rows=[
 ('S1 холодный старт', '5', '56', fmt(med('S1_cold','janky_pct')), '14 / 36 / 101 / 150', '8', '53', '5', '— / —', '0'),
 ('S2 idle, логотип', '3', fmt(med('S2_idle','hist_total'),0), fmt(med('S2_idle','janky_pct')), '13 / 15 / 18 / 21', fmt(med('S2_idle','hist_over_16'),0), fmt(med('S2_idle','hist_over_8'),0), '0', '11.1 / 11.1', '1'),
 ('S3 fake 10 tok/s', '3', fmt(med('S3_fake10','hist_total'),0), fmt(med('S3_fake10','janky_pct')), '8 / 14 / 16 / 22', fmt(med('S3_fake10','hist_over_16'),0), fmt(med('S3_fake10','hist_over_8'),0), fmt(med('S3_fake10','missed_vsync'),0), '39.2 / 57.4', '1'),
 ('S4 fake 25 tok/s', '3', fmt(med('S4_fake25','hist_total'),0), fmt(med('S4_fake25','janky_pct')), '8 / 16 / 20 / 27', fmt(med('S4_fake25','hist_over_16'),0), fmt(med('S4_fake25','hist_over_8'),0), fmt(med('S4_fake25','missed_vsync'),0), '50.0 / 53.5', '1'),
 ('S5 реальная LFM3, 256 ток.', '3', fmt(med('S5_real','hist_total'),0), fmt(med('S5_real','janky_pct')), '9 / 12 / 14 / 21', fmt(med('S5_real','hist_over_16'),0), fmt(med('S5_real','hist_over_8'),0), fmt(med('S5_real','missed_vsync'),0), '38.0 / 68.0', '1'),
 ('S6 скролл длинного чата', '3', fmt(med('S6_scroll','hist_total'),0), fmt(med('S6_scroll','janky_pct')), '5 / 5 / 5 / 6', fmt(med('S6_scroll','hist_over_16'),0), fmt(med('S6_scroll','hist_over_8'),0), '0', '14.8 / 22.2', '1'),
 ('S7 скролл + стриминг', '3', fmt(med('S7_scroll_stream','hist_total'),0), fmt(med('S7_scroll_stream','janky_pct')), '6 / 9 / 11 / 15', fmt(med('S7_scroll_stream','hist_over_16'),0), fmt(med('S7_scroll_stream','hist_over_8'),0), '1', '25.9 / 25.0', '1'),
 ('S8 листы настроек/моделей', '3', fmt(med('S8_sheets','hist_total'),0), fmt(med('S8_sheets','janky_pct')), '6 / 9 / 10 / 14', fmt(med('S8_sheets','hist_over_16'),0), fmt(med('S8_sheets','hist_over_8'),0), '1', '0 (max 11.1) / 0 (max 31)', '1'),
 ('S9 thinking открыть/закрыть', '3', fmt(med('S9_thinking','hist_total'),0), fmt(med('S9_thinking','janky_pct')), '8 / 11 / 12 / 14', fmt(med('S9_thinking','hist_over_16'),0), fmt(med('S9_thinking','hist_over_8'),0), '0', '23.2 / 29.6', '1'),
]
var_rows=[
 ('S3 plain text', '3', fmt(med('S3_plain','hist_total'),0), fmt(med('S3_plain','janky_pct')), '8 / 13 / 16 / 21', fmt(med('S3_plain','hist_over_16'),0), fmt(med('S3_plain','hist_over_8'),0), '38.1 / 57.1'),
 ('S3 без анимаций (робот+курсоры+точки)', '3', fmt(med('S3_no_anim','hist_total'),0), fmt(med('S3_no_anim','janky_pct')), '21 / 30 / 32 / 36', fmt(med('S3_no_anim','hist_over_16'),0), fmt(med('S3_no_anim','hist_over_8'),0), '22.2 / 7.4'),
 ('S5 без анимаций', '1', fmt(med('S5_no_anim','hist_total'),0), fmt(med('S5_no_anim','janky_pct')), '25 / 31 / 32 / 34', fmt(med('S5_no_anim','hist_over_16'),0), fmt(med('S5_no_anim','hist_over_8'),0), '16.9 / 5.7'),
 ('S3 publish 15 Гц', '3', fmt(med('S3_publish15','hist_total'),0), fmt(med('S3_publish15','janky_pct')), '8 / 16 / 20 / 28', fmt(med('S3_publish15','hist_over_16'),0), fmt(med('S3_publish15','hist_over_8'),0), '42.8 / 58.2'),
]
out=[]
out.append('# Отчёт: производительность UI LlamaPort\n')
out.append('Дата: 2026-11-18. Устройство: HONOR RMO-NX1, Android 13 (SDK 33), arm64-v8a, 8 ядер, 7,3 ГБ RAM.  ')
out.append('Сборка: release (`isMinifyEnabled=true`), подписана debug-ключом, `<profileable android:shell="true" />` для Perfetto/simpleperf.  ')
out.append('Дисплей: 120 Гц (active mode 2), 1080×2400. Яркость 128, системные анимации 1.0, экран включён.  ')
out.append('Методика: `am force-stop` перед каждым прогоном, пауза до температуры 35,6–37,4 °C (после разогрева плато ~37 °C), 3 повтора (S1 — 5), медианы.  ')
out.append('`dumpsys gfxinfo` + `framestats`; на этом Android `framestats` отдаёт только последние 10 кадров, поэтому перцентили и доля медленных кадров посчитаны по полной гистограмме `gfxinfo`.  ')
out.append('Порог 8,3 мс — 120 Гц; 16,7 мс — 60 Гц. Android-метрика «Janky frames» считает по 16,7 мс.\n')

out.append('## 1. Сводная таблица\n')
out.append('| Сценарий | Повторов | Кадров | Janky, % (16,7 мс) | p50 / p90 / p95 / p99, мс | >16,7 мс | >8,3 мс | Missed Vsync | CPU main / RenderThread, % | GC |')
out.append('|---|---:|---:|---:|---|---:|---:|---:|---|---:|')
for r in rows:
    out.append('| '+' | '.join(str(x) for x in r)+' |')
out.append('')
out.append('S1: `am start -W` TotalTime медиана **%.0f мс**, WaitTime **%.0f мс**, Displayed **%.0f мс**; в `gfxinfo` 56 кадров, p99 150 мс — это кадры старта, а не работа чата.  ' % (st.median(tt), st.median(wt), st.median(disp) if disp else 0))
out.append('S2: 398 кадров за 30 с = **13,3 fps** — это ровно тикер робота (`AsciiRobot.TICK_MS=75`). Janky 80,9%% — артефакт порога 120 Гц: анимация намеренно не рисует каждый кадр, реальных «зависаний» нет (p99 21 мс, main 11%%, Render 11%%).  ')
out.append('S5: 256 токенов, **%.1f ток/с** медиана, первый токен через **%.1f с**.  ' % (st.median(speeds) if speeds else 0, st.median(firsts) if firsts else 0))
out.append('GC: ровно одно явное concurrent copying GC на прогон, total 33–69 мс, максимальная пауза 0,35 мс; GC не является причиной jank.  ')
out.append('Память: PSS процесса 19–45 МБ, Java heap 5–11 МБ; утечек/роста нет.\n')

out.append('## 2. Варианты для атрибуции\n')
out.append('| Вариант | Повторов | Кадров | Janky, % | p50 / p90 / p95 / p99, мс | >16,7 мс | >8,3 мс | CPU main / Render, % |')
out.append('|---|---:|---:|---:|---|---:|---:|---|')
for r in var_rows:
    out.append('| '+' | '.join(str(x) for x in r)+' |')
out.append('')
out.append('Ключевые наблюдения:  ')
out.append('- **S3_plain** (без Markdown, обычный `Text`) даёт лишь ~15%% меньше jank (3,46%% против 4,05%%) и p90 13 против 14 мс. Значит парсинг/блоки Markdown — не главный источник; основной вклад даёт раскладка/отрисовка растущего текста и широкая рекомпозиция.  ')
out.append('- **S3_no_anim** (выключены пульс точки модели, курсоры, точки thinking и анимация robot avatar): main CPU 39,2→22,2%%, RenderThread 57,4→7,4%%, но кадров 3607→580 (19 fps), jank 62%%. Это не «исправление»: анимации генерируют дешёвые кадры и держат 120 Гц. Они стоят ~17 п.п. main CPU и ~50 п.п. RenderThread, но не являются причиной медленных кадров обновления текста.  ')
out.append('- **S3_publish15** (тот же поток 25 ток/с, публикация 15 Гц) **хуже** S4 (9,33%% против 8,20%%, p90 16, p99 28): крупные пачки текста дают больший layout/draw за кадр. Буферизацию нужно делать по vsync-кадру, а не фиксированными 66 мс.  ')
out.append('- **S2_no_logo** — статичный экран, всего 5–6 кадров за 30 с; метрика jank к нему неприменима.\n')

out.append('## 3. Perfetto: где именно время\n')
out.append('Трассы: `perf/traces/TRACE_S3_101.pftrace` (8 с, 117 fps), `S5_trace12.pftrace` (12 с, 119 fps), `TRACE_S6_101.pftrace` (8 с), `S7_trace12.pftrace` (12 с, 120 fps).  ')
out.append('S3/S6 — буфер 32 МБ, в трейс попали последние ~8 с; S5/S7 сняты с `-b 128mb` и содержат полные 12 с.  \n')
out.append('| Трасса | main Running | main Runnable | RenderThread Drawing | Recomposer:recompose | animation | postAndWait | deadline missed |')
out.append('|---|---:|---:|---:|---:|---:|---:|---:|')
out.append('| S3 fake 10 | 36,0% | 7,4% | 45% (3,61 с / 8 с) | 786 мс (9,8%) | 1459 мс (18,2%) | 1841 мс (23%) | 15 |')
out.append('| S5 real | 45,1% | 2,2% | 67% (8,09 с / 12 с) | 1477 мс (12,3%) | 2811 мс (23,4%) | 2825 мс (23,5%) | 15 |')
out.append('| S6 scroll | 17,5% | 0,6% | 19% (1,53 с / 8 с) | <240 мс | 393 мс (4,9%) | 238 мс (3%) | 1 |')
out.append('| S7 stream+scroll | 28,6% | 0,6% | 24% (2,85 с / 12 с) | 502 мс (4,2%) | 1262 мс (10,5%) | — | 0 |')
out.append('')
out.append('Самый медленный кадр S3: `Choreographer#doFrame` **15,81 мс**, ts `2218240208765696`. Внутри: `Recomposer:recompose` **9,43 мс**, `traversal` 5,42 мс, `draw` 5,14 мс, `Record View#draw()` 3,94 мс, `AndroidOwner:measureAndLayout` 3,25 мс.  ')
out.append('Самая длинная рекомпозиция S3: **15,71 мс**, ts `2218237571109811`; в ней 18 дочерних `Compose:recompose` (2,70 / 1,70 / 1,11 / 0,98 мс …), `Compose:applyChanges` 0,46 мс. Это не один тяжёлый блок, а **широкая рекомпозиция поддерева сообщения**.  ')
out.append('S5: при 256 токенах LFM3 RenderThread занят 67% времени, main — 45%, Runnable всего 2,2%. GPU/отрисовка, а не нехватка CPU, ограничивает кадр.  ')
out.append('S3: main Runnable 7,4% — небольшая очередь к CPU есть, но main всё равно 36% Running; RenderThread 45% и `postAndWait` 23% показывают, что кадр упирается в отрисовку/обмен буферами.\n')

out.append('## 4. Топ-5 горячих мест\n')
out.append('1. **Полный re-parse и широкая рекомпозиция Markdown на каждый токен.**  ')
out.append('   `app/src/main/java/com/llamaport/app/Markdown.kt:80` — `val blocks = remember(text) { parseMarkdown(text) }`; при каждом изменении текста создаётся новый список блоков, `blocks.forEachIndexed` перекомпоновывает поддерево. `Messages.kt:227` (`splitThinking`), `Messages.kt:289` (`AssistantMessage`), `Messages.kt:358` (`MarkdownText`).  ')
out.append('   Цифры: S3 p99 22 мс, 166/3607 кадров >16,7 мс; самая длинная рекомпозиция 15,71 мс с 18 `Compose:recompose`; S3_plain снижает jank до 3,46%% и p99 до 21 мс — то есть Markdown-слой даёт ~10–15%%, но не является единственной причиной.  ')
out.append('2. **Состояние на каждый токен: копирование списка сообщений и два обновления StateFlow (текст + метрики).**  ')
out.append('   `ChatViewModel.kt:363` (`updateMessages`), `ChatViewModel.kt:387` (`updateMessageMetrics`), `ChatViewModel.kt:414` (`appendToMessage`), вызовы из `onToken`/`onGenerationProgress` около `ChatViewModel.kt:1500–1560`.  ')
out.append('   Цифры: main CPU 39,2%% при 10 ток/с и 50,0%% при 25 ток/с; jank 4,05→8,20%%. Публикация 15 Гц дала 9,33%% — хуже, значит проблема не в частоте как таковой, а в размере и стоимости одного обновления.  ')
out.append('3. **Раскрытый live-блок размышлений.**  ')
out.append('   `Messages.kt:652` (`ThinkingBlock`), `Messages.kt:721` (`MarkdownText(thinking, streaming)`), `Messages.kt:739` (`ThinkingDots`). Пока `parts.answer.isEmpty()`, блок раскрыт и перерисовывается на каждом токене; у LFM3 это большая часть генерации.  ')
out.append('   Цифры: S5 (реальная LFM3) — RenderThread 67%, main 45%, `animation` 23,4%, `Recomposer:recompose` 12,3%; при этом обновлений всего 7,7/с. S3 trace на первых 8 с — тоже почти весь thinking-блок: RenderThread 45%, `animation` 18,2%.  ')
out.append('4. **Анимации, которые держат полный кадр окна на 120 Гц.**  ')
out.append('   `HomeChrome.kt:121` (пульс точки модели), `Messages.kt:533` (курсор), `Markdown.kt:199` (курсор Markdown), `Messages.kt:739` (точки thinking), `ui/robot/RobotAvatar.kt:86` (пульс аватара).  ')
out.append('   Цифры: выключение всех этих анимаций — main CPU 39,2→22,2%%, RenderThread 57,4→7,4%%, кадры 3607→580. Анимации стоят дорого, но именно они дают плавность между обновлениями текста; отключать их нельзя, нужно делать дешевле/реже.  ')
out.append('5. **Отрисовка текста и обмен буферами (RenderThread/GPU).**  ')
out.append('   `Markdown.kt:108+` (`MdBlockView`, `CodeBlock`), Compose `Text`/`BasicText`; `Messages.kt:289`.  ')
out.append('   Цифры: `RenderThread Drawing` 45% (S3) и 67% (S5); `postAndWait` 23% S3 и 23,5% S5; simpleperf S3: `libhwui.so` 18,4%%, GPU-драйвер 8,6%%, app odex (Compose/UI) 27,6%%, `libart` 12,8%%.  ')
out.append('')
out.append('Дополнительно: S8 (листы) — p99 14 мс, 1,24% jank, не горячее место; S9 — p99 14 мс, 0,69%; S6 — 0,05% jank, p99 6 мс.  ')
out.append('S1 — медиана старта 270 мс, p99 150 мс в 56 стартовых кадрах; для холодного старта это нормально, оптимизация не приоритетна.\n')

out.append('## 5. Атрибуция: UI или конкуренция с инференсом\n')
out.append('- **Не конкуренция за CPU.** В S3/S5 main Runnable 7,4%%/2,2%%, main Running 36%%/45%%; потоки инференса не отбирают у main квант. S5 (реальная генерация) имеет jank **1,66%%**, меньше, чем fake 10/25 ток/с (4,05%%/8,20%%), потому что LFM3 выдаёт 7,7 ток/с и UI обновляется реже. Если бы виноват был инференс, S5 был бы хуже fake, а не лучше.  ')
out.append('- **Виноват UI-путь стриминга**: обновление текста, рекомпозиция поддерева сообщения и отрисовка растущего текста. Чем выше частота обновлений, тем хуже: 7,7→10→25 ток/с даёт jank 1,66→4,05→8,20%%.  ')
out.append('- **S5 тяжелее по RenderThread (67%%)**, чем fake (45%%), из-за live-thinking и большего объёма текста; это не «инференс съел CPU», а «UI рисует больше».  ')
out.append('- **S2 80,9%% jank — ложная тревога**: 13,3 fps тикер робота, p99 21 мс, main/Render по 11%%.  ')
out.append('- **S6/S7/S9 гладкие**: скролл и раскрытие thinking не являются проблемой; S7 лёгкий потому, что при ручном скролле растущее сообщение уходит из вьюпорта, а не потому, что стриминг дешёвый.\n')

out.append('## 6. Предлагаемые исправления (не применены)\n')
out.append('1. **Инкрементальный рендер стриминга.** Не парсить весь Markdown заново и не перекомпоновывать все блоки: держать стабильные завершённые блоки, обновлять только хвостовой блок; для простого текста — `BasicText`/`AnnotatedString`, добавлять в конец без пересоздания блоков. Ожидаемо: p99 22→16–18 мс, jank 4,05→2%%, при 25 ток/с 8,2→4–5%%, main CPU −10–15%%.  ')
out.append('2. **Обновление состояния по кадру, а не по токену.** Один `withFrameNanos`-коалесcер: накопить токены, опубликовать один раз за vsync; метрики скорости обновлять 4–5 раз/с отдельным state. Не делать фиксированные 15 Гц (данные показали ухудшение). Ожидаемо: main CPU −5–10%%, jank при 25 ток/с −2–3 п.п.  ')
out.append('3. **Ограничить live-thinking.** Раскрытый блок размышлений держать фиксированной высоты/последние N строк, без Markdown-перерисовки всего текста; точки/пульс аватара — статичные во время стриминга. Ожидаемо для S5: RenderThread −20–30%%, main animation −50%%, p99 21→16–17 мс.  ')
out.append('4. **Сделать анимации дешевле, не убирая их.** Пульс точки модели/курсоры/точки thinking перевести на 30–60 Гц или отдельный слой, не инвалидировать всё окно на 120 Гц. Ожидаемо: main −5–10%%, RenderThread −10–20%%; сохранить плавность.  ')
out.append('5. **Снизить стоимость draw.** `drawWithCache`/DisplayList для статичных блоков, не перерисовывать неизменившиеся абзацы/код, уменьшить overdraw. Ожидаемо: RenderThread −10–20%%, особенно при длинном ответе.  ')
out.append('6. **S8/листы** — p99 14 мс, не приоритет; при желании убрать анимацию открытия списка моделей на время сканирования.  ')
out.append('7. **S1** — 270 мс TotalTime, p99 150 мс; при желании вынести восстановление сессии/темы с главного потока, но выигрыш меньше, чем в стриминге.\n')

out.append('## 7. Сырые данные\n')
out.append('- `perf/raw/*_gfxinfo.txt`, `*_framestats.txt` — кадры по сценариям.  ')
out.append('- `perf/raw/*_top.txt` — `top -H` по 2 с.  ')
out.append('- `perf/raw/*_logcat.txt` — GC, Skipped, Measure-маркеры.  ')
out.append('- `perf/raw/*_meminfo.txt` — память.  ')
out.append('- `perf/traces/*.pftrace` — Perfetto; `perf/trace_summary.json` — срезы.  ')
out.append('- `perf/raw/S3_simpleperf.txt`, `S3_simpleperf_dso.txt` — simpleperf 5 с.  ')
out.append('- `perf/summary.json` — медианы и перцентили.  ')
out.append('\nОграничения: release минифицирован, Kotlin-символы в simpleperf обфусцированы (`J.p.F` и т.п.); Compose runtime-tracing не подключён, поэтому нет поштучных счётчиков рекомпозиций — выводы по `Recomposer:recompose`, `Compose:recompose` и вариантам. `framestats` на устройстве отдаёт 10 кадров, перцентили — по гистограмме. Трассы S3/S6 короче 20 с из-за 32 МБ буфера; S5/S7 пересняты с 128 МБ.\n')

text='\n'.join(out).replace('%%','%')
(root/'REPORT.md').write_text(text, encoding='utf-8')
print('wrote', root/'REPORT.md')
print('S1', st.median(tt), st.median(wt), st.median(disp) if disp else None)
print('S5 speed', st.median(speeds) if speeds else None, 'first', st.median(firsts) if firsts else None)
