package com.llamaport.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.llamaport.app.Settings
import com.llamaport.app.UiState
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.formatRam
import com.llamaport.app.formatSize
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import com.llamaport.app.ui.motion.pressable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

private const val MB = 1024L * 1024L

/** Quantization presets for temperature / top-p / top-k. */
private data class TemperaturePreset(
    val label: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
)

private val TEMPERATURE_PRESETS = listOf(
    TemperaturePreset("Точно", 0.2f, 0.9f, 20),
    TemperaturePreset("Баланс", 0.7f, 0.95f, 40),
    TemperaturePreset("Творчески", 1.0f, 0.98f, 80),
)

private val CONTEXT_STEP = 64

private val BATCH_VALUES = listOf(64, 128, 256, 512, 1024, 2048)
private val UBATCH_VALUES = listOf(32, 64, 128, 256, 512)
private val THINKING_VALUES = listOf(0, 512, 1024, 2048, 4096, 8192)

private val FLASH_ATTN_LABELS = listOf("Авто", "Вкл", "Выкл")

private data class ToolOption(
    val name: String,
    val label: String,
    val description: String,
)

private val TOOL_OPTIONS = listOf(
    ToolOption("get_time", "get_time", "Текущие дата и время"),
    ToolOption("calculator", "calculator", "Арифметические выражения"),
    ToolOption("read_file", "read_file", "Чтение файла из песочницы"),
    ToolOption("write_file", "write_file", "Запись файла в песочницу"),
    ToolOption("list_dir", "list_dir", "Список файлов песочницы"),
    ToolOption("notes", "notes", "Заметки в песочнице"),
    ToolOption("http_get", "http_get", "HTTP GET, только http(s), ответ до 64 КБ"),
    ToolOption("run_js", "run_js", "JavaScript в QuickJS, таймаут 8 с, память 32 МБ"),
)

/**
 * Settings surface: three animated disclosure sections.
 *
 * 1. Поведение — system prompt, temperature presets, fine-tuning under «Подробнее».
 * 2. Память и скорость — context with a live KV-cache estimate, threads, batches, FA.
 * 3. Продвинутое — mmap, KV type, tools, reload.
 *
 * Every number field is editable: the slider and the field share one value, the
 * displayed number animates, and discrete sliders tick with haptics on each step.
 */
@Composable
internal fun SettingsSheet(
    state: UiState,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onReload: () -> Unit,
) {
    val settings = state.settings
    var behaviorExpanded by rememberSaveable { mutableStateOf(true) }
    var memoryExpanded by rememberSaveable { mutableStateOf(true) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Reveal(index = 0) {
            Column {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Температура и инструменты применяются сразу; контекст, потоки, " +
                        "пакеты и Flash Attention — после перезагрузки модели.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Reveal(index = 1) {
            SettingsSection(
                title = "Поведение",
                subtitle = "Промпт, температура, лимиты",
                expanded = behaviorExpanded,
                onToggle = { behaviorExpanded = !behaviorExpanded },
            ) {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = { value -> onUpdate { it.copy(systemPrompt = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Системный промпт") },
                    shape = RoundedCornerShape(16.dp),
                    minLines = 2,
                    maxLines = 6,
                )

                Spacer(Modifier.height(14.dp))
                Text("Температура", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TemperaturePresets(
                    settings = settings,
                    onPreset = { preset ->
                        onUpdate {
                            it.copy(
                                temperature = preset.temperature,
                                topP = preset.topP,
                                topK = preset.topK,
                            )
                        }
                    },
                    onCustom = { detailsExpanded = true },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "«Точно» — короче и по делу, «Творчески» — свободнее и разнообразнее.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                DetailsToggle(
                    expanded = detailsExpanded,
                    onToggle = { detailsExpanded = !detailsExpanded },
                )
                AnimatedVisibility(
                    visible = detailsExpanded,
                    enter = disclosureEnter(),
                    exit = disclosureExit(),
                ) {
                    Column {
                        Reveal(index = 0) {
                            SettingsSlider(
                                label = "Температура",
                                value = settings.temperature,
                                range = 0f..1.5f,
                                display = { formatFloat(it, 2) },
                                parse = { text -> text.toFloatOrNull()?.coerceIn(0f, 1.5f) },
                                decimal = true,
                                onValueChange = { value ->
                                    onUpdate { it.copy(temperature = roundTo(value, 100)) }
                                },
                            )
                        }
                        Reveal(index = 1) {
                            SettingsSlider(
                                label = "Top-P",
                                value = settings.topP,
                                range = 0.05f..1f,
                                display = { formatFloat(it, 2) },
                                parse = { text -> text.toFloatOrNull()?.coerceIn(0.05f, 1f) },
                                decimal = true,
                                onValueChange = { value ->
                                    onUpdate { it.copy(topP = roundTo(value, 100)) }
                                },
                            )
                        }
                        Reveal(index = 2) {
                            SettingsSlider(
                                label = "Top-K",
                                value = settings.topK.toFloat(),
                                range = 1f..128f,
                                display = { it.roundToInt().toString() },
                                parse = { text ->
                                    text.toIntOrNull()?.coerceIn(1, 128)?.toFloat()
                                },
                                onValueChange = { value ->
                                    onUpdate { it.copy(topK = value.roundToInt().coerceIn(1, 128)) }
                                },
                            )
                        }
                        Reveal(index = 3) {
                            SettingsSlider(
                                label = "Максимум новых токенов",
                                value = settings.maxTokens.toFloat(),
                                range = 64f..2048f,
                                display = { it.roundToInt().toString() },
                                parse = { text ->
                                    text.toIntOrNull()?.coerceIn(64, 2048)?.toFloat()
                                },
                                onValueChange = { value ->
                                    onUpdate { it.copy(maxTokens = snapTo(value, 64)) }
                                },
                                onExactCommit = { value ->
                                    onUpdate {
                                        it.copy(maxTokens = value.roundToInt().coerceIn(64, 2048))
                                    }
                                },
                            )
                        }
                        Reveal(index = 4) {
                            SettingsSlider(
                                label = "Бюджет размышлений",
                                description = "Добавляется к максимуму новых токенов; 0 — выключен.",
                                value = THINKING_VALUES.indexOf(settings.thinkingBudget)
                                    .coerceAtLeast(0)
                                    .toFloat(),
                                range = 0f..THINKING_VALUES.lastIndex.toFloat(),
                                steps = (THINKING_VALUES.size - 2).coerceAtLeast(0),
                                display = { index ->
                                    val value = THINKING_VALUES[index.roundToInt().coerceIn(0, THINKING_VALUES.lastIndex)]
                                    if (value == 0) "выкл" else value.toString()
                                },
                                parse = { text ->
                                    val value = text.toIntOrNull()
                                    when {
                                        value == null -> null
                                        value <= 0 -> 0f
                                        else -> THINKING_VALUES.indexOfFirst { it >= value }
                                            .coerceAtLeast(0)
                                            .toFloat()
                                    }
                                },
                                onValueChange = { index ->
                                    val value = THINKING_VALUES[
                                        index.roundToInt().coerceIn(0, THINKING_VALUES.lastIndex)
                                    ]
                                    onUpdate { it.copy(thinkingBudget = value) }
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Reveal(index = 2) {
            SettingsSection(
                title = "Память и скорость",
                subtitle = "Контекст, потоки, Flash Attention",
                expanded = memoryExpanded,
                onToggle = { memoryExpanded = !memoryExpanded },
            ) {
                val kvBytes = kvEstimateBytes(state, settings.contextSize)
                val kvText = when {
                    kvBytes != null -> formatSize(kvBytes)
                    state.modelLoaded -> "≈ ${formatSize(fallbackKvBytes(state, settings.contextSize))}"
                    else -> "после загрузки модели"
                }
                ContextSlider(
                    contextSize = settings.contextSize,
                    maxContext = state.modelCap,
                    description = "макс. ${state.modelCap} · KV-кэш $kvText",
                    onValueChange = { value -> onUpdate { it.copy(contextSize = value) } },
                )
                if (settings.contextSize > 8192) {
                    Text(
                        "Большой контекст увеличивает KV-кэш. Если модель не поместится в RAM, " +
                            "она может быть выгружена системой.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val needMb = memoryNeedMb(state, settings.contextSize)
                if (needMb > 0) {
                    Text(
                        "Оценка памяти: ~${formatRam(needMb)} · свободно " +
                            "${formatRam(state.memAvailableMb)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (needMb > state.memAvailableMb) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    Text(
                        "Реальный максимум контекста берётся из метаданных модели и появится " +
                            "после её выбора.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                SettingsSlider(
                    label = "Потоки генерации",
                    value = settings.threads.toFloat(),
                    range = 1f..8f,
                    steps = 6,
                    display = { it.roundToInt().toString() },
                    parse = { text -> text.toIntOrNull()?.coerceIn(1, 8)?.toFloat() },
                    onValueChange = { value ->
                        onUpdate { it.copy(threads = value.roundToInt().coerceIn(1, 8)) }
                    },
                )
                SettingsSlider(
                    label = "Потоки промпта",
                    value = settings.threadsBatch.toFloat(),
                    range = 1f..8f,
                    steps = 6,
                    display = { it.roundToInt().toString() },
                    parse = { text -> text.toIntOrNull()?.coerceIn(1, 8)?.toFloat() },
                    onValueChange = { value ->
                        onUpdate { it.copy(threadsBatch = value.roundToInt().coerceIn(1, 8)) }
                    },
                )
                SettingsSlider(
                    label = "Пакет промпта",
                    value = nearestIndex(BATCH_VALUES, settings.nBatch).toFloat(),
                    range = 0f..BATCH_VALUES.lastIndex.toFloat(),
                    steps = (BATCH_VALUES.size - 2).coerceAtLeast(0),
                    display = { index ->
                        "${BATCH_VALUES[index.roundToInt().coerceIn(0, BATCH_VALUES.lastIndex)]}"
                    },
                    parse = { text ->
                        text.toIntOrNull()
                            ?.let { nearestIndex(BATCH_VALUES, it) }
                            ?.toFloat()
                    },
                    onValueChange = { index ->
                        val value = BATCH_VALUES[index.roundToInt().coerceIn(0, BATCH_VALUES.lastIndex)]
                        onUpdate { it.copy(nBatch = value) }
                    },
                )
                SettingsSlider(
                    label = "Микробатч",
                    value = nearestIndex(UBATCH_VALUES, settings.nUbatch).toFloat(),
                    range = 0f..UBATCH_VALUES.lastIndex.toFloat(),
                    steps = (UBATCH_VALUES.size - 2).coerceAtLeast(0),
                    display = { index ->
                        "${UBATCH_VALUES[index.roundToInt().coerceIn(0, UBATCH_VALUES.lastIndex)]}"
                    },
                    parse = { text ->
                        text.toIntOrNull()
                            ?.let { nearestIndex(UBATCH_VALUES, it) }
                            ?.toFloat()
                    },
                    onValueChange = { index ->
                        val value = UBATCH_VALUES[index.roundToInt().coerceIn(0, UBATCH_VALUES.lastIndex)]
                        onUpdate { it.copy(nUbatch = minOf(value, it.nBatch)) }
                    },
                )

                Spacer(Modifier.height(8.dp))
                Text("Flash Attention", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                FlashAttentionControl(
                    value = settings.flashAttn,
                    onChange = { index -> onUpdate { it.copy(flashAttn = index) } },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "«Авто» включает внимание с учётом типа KV-кэша; «Вкл» может ускорить " +
                        "генерацию, «Выкл» — запасной вариант.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Reveal(index = 3) {
            SettingsSection(
                title = "Продвинутое",
                subtitle = "mmap, KV-кэш, инструменты",
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
            ) {
                SwitchRow(
                    label = "mmap модели",
                    description = "Не копировать веса в память целиком; экономит RAM, но файл " +
                        "должен оставаться на месте.",
                    checked = settings.useMmap,
                    onChange = { checked -> onUpdate { it.copy(useMmap = checked) } },
                )
                SwitchRow(
                    label = "KV-кэш q8_0",
                    description = "Сжимает кэш контекста примерно вдвое (нужен Flash Attention).",
                    checked = settings.kvType == 1,
                    onChange = { checked -> onUpdate { it.copy(kvType = if (checked) 1 else 0) } },
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    label = "Разрешить инструменты",
                    description = "Модель сможет вызывать калькулятор, файлы в песочнице и другие " +
                        "инструменты.",
                    checked = settings.toolsEnabled,
                    onChange = { checked -> onUpdate { it.copy(toolsEnabled = checked) } },
                )
                AnimatedVisibility(
                    visible = settings.toolsEnabled,
                    enter = disclosureEnter(),
                    exit = disclosureExit(),
                ) {
                    Column {
                        for ((index, option) in TOOL_OPTIONS.withIndex()) {
                            Reveal(index = index) {
                                ToolRow(
                                    option = option,
                                    checked = option.name in settings.enabledTools,
                                    onCheckedChange = { checked ->
                                        onUpdate { current ->
                                            current.copy(
                                                enabledTools = if (checked) {
                                                    current.enabledTools + option.name
                                                } else {
                                                    current.enabledTools - option.name
                                                },
                                                httpToolEnabled = if (option.name == "http_get") {
                                                    checked
                                                } else {
                                                    current.httpToolEnabled
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        Text(
                            "Файлы доступны только в папке sandbox приложения. http_get и run_js " +
                                "по умолчанию выключены.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Контекст, потоки, пакеты, Flash Attention, mmap и тип KV применяются " +
                        "после перезагрузки модели.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onReload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.modelLoaded && !state.generating && !state.loadingModel,
                ) {
                    Text("Перезагрузить модель")
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Text(
                    state.backendInfo.ifBlank { "llama.cpp" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MotionTokens.Durations.MICRO, easing = MotionTokens.Easing.Standard),
        label = "sectionChevron",
    )

    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = AppIcons.Chevron,
                    contentDescription = if (expanded) "Свернуть" else "Открыть",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = disclosureEnter(),
            exit = disclosureExit(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 14.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemperaturePresets(
    settings: Settings,
    onPreset: (TemperaturePreset) -> Unit,
    onCustom: () -> Unit,
) {
    val selected = TEMPERATURE_PRESETS.firstOrNull { preset ->
        abs(settings.temperature - preset.temperature) < 0.005f &&
            abs(settings.topP - preset.topP) < 0.005f &&
            settings.topK == preset.topK
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (preset in TEMPERATURE_PRESETS) {
            FilterChip(
                selected = preset == selected,
                onClick = { onPreset(preset) },
                label = { Text(preset.label) },
            )
        }
        FilterChip(
            selected = selected == null,
            onClick = onCustom,
            label = { Text("Свой") },
        )
    }
}

@Composable
private fun DetailsToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MotionTokens.Durations.MICRO, easing = MotionTokens.Easing.Standard),
        label = "detailsChevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "Свернуть" else "Подробнее",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AppIcons.Chevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = chevron },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlashAttentionControl(
    value: Int,
    onChange: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FLASH_ATTN_LABELS.forEachIndexed { index, label ->
            SegmentedButton(
                selected = value == index,
                onClick = { onChange(index) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = FLASH_ATTN_LABELS.size,
                ),
            ) {
                Text(label)
            }
        }
    }
}

/**
 * Slider with a shared exact-number field. The field shows the animated value
 * (unless the user is typing), accepts a precise number, and discrete sliders
 * tick with haptics on each step.
 */
@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    parse: (String) -> Float?,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    steps: Int = 0,
    decimal: Boolean = false,
    onExactCommit: ((Float) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val clamped = value.coerceIn(range.start, range.endInclusive)
    var lastStep by remember { mutableIntStateOf(stepIndex(clamped, range, steps)) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ExactNumberField(
                value = clamped,
                display = display,
                parse = parse,
                decimal = decimal,
                onCommit = onExactCommit ?: onValueChange,
            )
        }
        Slider(
            value = clamped,
            onValueChange = { next ->
                if (steps > 0) {
                    val index = stepIndex(next, range, steps)
                    if (index != lastStep) {
                        lastStep = index
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                onValueChange(next)
            },
            valueRange = range,
            steps = steps,
        )
    }
}

/**
 * Context slider on a logarithmic scale: 512…262144 fits into one track, while
 * the number field still accepts any exact value the model supports.
 */
@Composable
private fun ContextSlider(
    contextSize: Int,
    maxContext: Int,
    description: String,
    onValueChange: (Int) -> Unit,
) {
    val minContext = 512
    val maxValue = maxOf(maxContext, minContext * 2)
    val logSpan = ln(maxValue.toDouble() / minContext)

    fun positionOf(context: Int): Float {
        val clamped = context.coerceIn(minContext, maxValue)
        return (ln(clamped.toDouble() / minContext) / logSpan).toFloat().coerceIn(0f, 1f)
    }

    fun contextAt(position: Float): Int {
        val value = minContext * exp(position.coerceIn(0f, 1f) * logSpan)
        return value.roundToInt().coerceIn(minContext, maxValue)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text("Контекст", style = MaterialTheme.typography.bodyMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExactNumberField(
                value = positionOf(contextSize),
                display = { position -> contextAt(position).toString() },
                parse = { text -> text.toIntOrNull()?.coerceIn(minContext, maxValue) },
                onCommit = onValueChange,
                decimal = false,
            )
        }
        Slider(
            value = positionOf(contextSize),
            onValueChange = { position ->
                onValueChange(snapTo(contextAt(position).toFloat(), CONTEXT_STEP))
            },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun <T> ExactNumberField(
    value: Float,
    display: (Float) -> String,
    parse: (String) -> T?,
    onCommit: (T) -> Unit,
    decimal: Boolean,
) {
    val reduced = LocalReducedMotion.current
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = if (reduced) {
            snap()
        } else {
            tween(MotionTokens.Durations.SHORT, easing = MotionTokens.Easing.Emphasized)
        },
        label = "exactNumber",
    )
    var focused by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    BasicTextField(
        value = if (focused) draft else display(animated),
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .width(96.dp)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !focused) {
                    focused = true
                    draft = display(value)
                } else if (!focusState.isFocused && focused) {
                    focused = false
                    parse(draft)?.let(onCommit)
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ToolRow(
    option: ToolOption,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                option.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun disclosureEnter() = if (LocalReducedMotion.current) {
    fadeIn(tween(MotionTokens.Durations.FADE_REDUCED))
} else {
    expandVertically(
        animationSpec = tween(MotionTokens.Durations.ENTER, easing = MotionTokens.Easing.EmphasizedDecelerate),
        expandFrom = Alignment.Top,
    ) + fadeIn(tween(MotionTokens.Durations.ENTER))
}

@Composable
private fun disclosureExit() = if (LocalReducedMotion.current) {
    fadeOut(tween(MotionTokens.Durations.FADE_REDUCED))
} else {
    shrinkVertically(
        animationSpec = tween(MotionTokens.Durations.SHORT, easing = MotionTokens.Easing.EmphasizedAccelerate),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(tween(MotionTokens.Durations.SHORT))
}

/** Number of steps a continuous value has passed, for step haptics. */
private fun stepIndex(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    if (steps <= 0) return 0
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: return 0
    return ((value - range.start) / span * (steps + 1)).roundToInt()
}

/** Nearest slide position for a discrete value list. */
private fun nearestIndex(values: List<Int>, value: Int): Int {
    var best = 0
    var bestDistance = Int.MAX_VALUE
    for ((index, candidate) in values.withIndex()) {
        val distance = abs(candidate - value)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

private fun formatFloat(value: Float, decimals: Int): String =
    String.format(Locale.ROOT, "%.${decimals}f", value)

private fun roundTo(value: Float, scale: Int): Float = (value * scale).roundToInt() / scale.toFloat()

private fun snapTo(value: Float, step: Int): Int =
    ((value / step).roundToInt() * step).coerceAtLeast(step)

/**
 * KV cache size for the selected context, scaled from the native estimate.
 * Returns null when the model metadata is unavailable.
 */
private fun kvEstimateBytes(state: UiState, contextSize: Int): Long? {
    val estimate = state.estimate ?: return null
    if (estimate.kvBytes <= 0) return null
    val base = if (estimate.estimateCtx > 0) {
        estimate.kvBytes.toDouble() * contextSize / estimate.estimateCtx
    } else {
        estimate.kvBytes.toDouble()
    }
    return base.toLong().coerceAtLeast(0L)
}

/** Rough fallback when the model has no KV metadata: ~¼ of the file per 4K. */
private fun fallbackKvBytes(state: UiState, contextSize: Int): Long {
    val fileBytes = state.models.firstOrNull { it.name == state.modelName }?.length()
        ?: state.estimate?.fileBytes
        ?: 0L
    if (fileBytes <= 0L) return 0L
    return fileBytes / 4 * contextSize / 4096
}

private fun memoryNeedMb(state: UiState, contextSize: Int): Long {
    val estimate = state.estimate ?: return 0L
    val kv = if (estimate.estimateCtx > 0) {
        estimate.kvBytes * contextSize / estimate.estimateCtx
    } else {
        estimate.kvBytes
    }
    return (estimate.fileBytes + kv + state.mmprojBytes) / MB + 300L
}