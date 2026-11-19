package com.llamaport.app.ui.models

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llamaport.app.UiState
import com.llamaport.app.formatRam
import com.llamaport.app.formatSize
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.components.Eyebrow
import com.llamaport.app.ui.components.Hairline
import com.llamaport.app.ui.components.ObsidianButton
import com.llamaport.app.ui.components.ObsidianButtonStyle
import com.llamaport.app.ui.components.ObsidianIconButton
import com.llamaport.app.ui.components.ObsidianProgressLine
import com.llamaport.app.ui.components.ObsidianTag
import com.llamaport.app.ui.components.SilverPulseOverlay
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.AnimatedNumber
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import com.llamaport.app.ui.theme.CardShape
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoNumeric
import com.llamaport.app.ui.theme.MonoSmall
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PanelShape
import com.llamaport.app.ui.theme.PillShape
import java.io.File
import java.util.Locale

private const val MB = 1024L * 1024L
private const val RESERVE = 1.15f

/** How well a model fits into currently free RAM. */
private enum class Fit { Fits, Tight, NoFit }

private fun fitOf(needMb: Long, availMb: Long): Fit? = when {
    availMb <= 0L -> null
    needMb * 100 <= availMb * 80 -> Fit.Fits
    needMb <= availMb -> Fit.Tight
    else -> Fit.NoFit
}

private val QUANT_REGEX =
    Regex("""(IQ\d[_A-Z0-9]*|Q\d[_A-Z0-9]*|BF16|F16|FP16|NVFP4|MXFP4)""", RegexOption.IGNORE_CASE)

/** Quantization label parsed from the file name, e.g. "IQ4_XS". */
private fun quantLabel(name: String): String? =
    QUANT_REGEX.find(name)?.value?.uppercase()

private val PARAM_REGEX = Regex("""(?<![A-Za-z0-9.])(\d+(?:[.,]\d+)?)\s*[Bb](?![A-Za-z])""")

/** Parameter count from the file name, when the publisher put it there. */
private fun paramLabel(name: String): String? =
    PARAM_REGEX.find(name)?.let { "${it.groupValues[1].replace(',', '.')}B" }

@Composable
internal fun ModelsSheet(
    state: UiState,
    onPick: () -> Unit,
    onLoad: (File) -> Unit,
    onDelete: (File) -> Unit,
    onUnload: () -> Unit,
    onMmproj: (File) -> File?,
) {
    val palette = ObsidianTheme.palette
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var successTick by remember { mutableIntStateOf(0) }
    var wasLoading by remember { mutableStateOf(state.loadingModel) }
    val busy = state.generating || state.loadingModel
    val enabled = !busy
    val availMb = state.memAvailableMb
    val loadingFile = pendingFile?.takeIf { state.loadingModel }

    LaunchedEffect(state.loadingModel) {
        if (wasLoading && !state.loadingModel && state.modelLoaded && pendingFile != null) {
            successTick++
        }
        wasLoading = state.loadingModel
    }

    // Loaded model footprint: file + projector + KV cache from the metadata
    // estimate when available.
    val activeFile = state.models.firstOrNull { it.name == state.modelName }
    val activeMmproj = activeFile?.let(onMmproj)
    val modelMb = if (state.modelLoaded && activeFile != null) {
        ((activeFile.length() + (activeMmproj?.length() ?: 0L) +
            (state.estimate?.kvBytes ?: 0L)) / MB)
    } else {
        0L
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Reveal(index = 0) {
            Column {
                Eyebrow(text = "локальные модели", color = palette.textMuted)
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Модели GGUF",
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.loadingModel) {
                        StateDot(color = palette.silver, size = 5.dp, pulse = true)
                        Spacer(Modifier.width(7.dp))
                        Text("ЗАГРУЗКА", style = MonoSmall, color = palette.silver)
                    } else if (state.modelLoaded) {
                        StateDot(color = palette.success, size = 5.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("В ПАМЯТИ", style = MonoSmall, color = palette.success)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    state.modelInfo ?: "Выберите файл модели, чтобы загрузить её в память",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
                if (state.loadingModel) {
                    Spacer(Modifier.height(12.dp))
                    ObsidianProgressLine(
                        progress = null,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        trackColor = palette.elevated,
                        accent = palette.silver,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.status ?: "Загрузка модели…",
                        style = MonoSmall,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (state.memTotalMb > 0) {
            Reveal(index = 1) {
                RamPanel(
                    totalMb = state.memTotalMb,
                    availableMb = availMb,
                    modelMb = modelMb,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.models.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(palette.card)
                    .border(1.dp, palette.hairline, MaterialTheme.shapes.medium)
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.Document,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "В приложении пока нет моделей",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Нажмите «Добавить», чтобы выбрать .gguf файл.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        } else {
            state.models.forEachIndexed { index, file ->
                val active = file.name == state.modelName
                val mmproj = onMmproj(file)
                val baseBytes = file.length() + (mmproj?.length() ?: 0L)
                val kvBytes = if (active && state.modelLoaded) {
                    state.estimate?.kvBytes ?: 0L
                } else {
                    0L
                }
                val needMb = (baseBytes * RESERVE).toLong() / MB + kvBytes / MB
                val approximate = !(active && state.estimate != null)
                val loading = loadingFile == file
                val dimmed = state.loadingModel && !loading
                // Keep swipe/remember state attached to the file, not to the
                // list slot: importing or deleting a model must not move the
                // delete callback onto a neighbouring card.
                key(file.absolutePath) {
                    Reveal(index = 2 + index) {
                        SwipeableModelCard(
                            file = file,
                            mmproj = mmproj,
                            active = active,
                            loading = loading,
                            dimmed = dimmed,
                            vision = if (active) state.supportsVision else mmproj != null,
                            audio = active && state.supportsAudio,
                            needMb = needMb,
                            approximate = approximate,
                            availMb = availMb,
                            enabled = enabled,
                            successTick = if (active && !dimmed) successTick else 0,
                            onLoad = {
                                pendingFile = file
                                onLoad(file)
                            },
                            onDeleteRequest = { pendingDelete = file },
                        )
                    }
                }
            }
        }

        if (state.projectors.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Eyebrow(text = "проекторы", color = palette.textMuted)
            Spacer(Modifier.height(6.dp))
            Text("mmproj", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Подключаются автоматически к модели с совпадающим именем " +
                    "(например, mmproj-LFM3-Omni-2B-Q4_K_M.gguf).",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            state.projectors.forEach { file ->
                ProjectorRow(
                    file = file,
                    enabled = enabled,
                    onDelete = { pendingDelete = file },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Hairline()
        Spacer(Modifier.height(14.dp))
        ObsidianButton(
            text = "Добавить модель или mmproj",
            onClick = onPick,
            style = ObsidianButtonStyle.Secondary,
            leadingIcon = Icons.Filled.Add,
            enabled = !state.generating,
        )
        if (state.modelLoaded) {
            Spacer(Modifier.height(8.dp))
            ObsidianButton(
                text = "Выгрузить модель",
                onClick = onUnload,
                style = ObsidianButtonStyle.Ghost,
                enabled = !state.generating,
            )
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = palette.card,
            title = {
                Column {
                    Eyebrow(text = "хранилище", color = palette.textMuted)
                    Spacer(Modifier.height(5.dp))
                    Text("Удалить файл?", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                Text(
                    "«${file.name}» (${formatSize(file.length())}) будет удалён " +
                        "из хранилища приложения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(file)
                        pendingDelete = null
                    },
                ) {
                    Text("Удалить", color = palette.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена", color = palette.textSecondary)
                }
            },
        )
    }
}

/**
 * Model card: name, technical tags, RAM estimate, fit and state. Tap loads,
 * long-press (or swipe) asks to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableModelCard(
    file: File,
    mmproj: File?,
    active: Boolean,
    loading: Boolean,
    dimmed: Boolean,
    vision: Boolean,
    audio: Boolean,
    needMb: Long,
    approximate: Boolean,
    availMb: Long,
    enabled: Boolean,
    successTick: Int,
    onLoad: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { alpha = if (dimmed) 0.42f else 1f },
        backgroundContent = {
            // Show the delete hint only while the card is actually swiped.
            val reveal = dismissState.progress
            if (reveal > 0.02f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CardShape)
                        .background(
                            ObsidianTheme.palette.error.copy(
                                alpha = (reveal * 0.24f).coerceAtMost(0.24f),
                            ),
                        )
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = ObsidianTheme.palette.error,
                    )
                }
            }
        },
    ) {
        ModelCard(
            file = file,
            mmproj = mmproj,
            active = active,
            loading = loading,
            vision = vision,
            audio = audio,
            needMb = needMb,
            approximate = approximate,
            availMb = availMb,
            enabled = enabled,
            successTick = successTick,
            onLoad = onLoad,
            onDeleteRequest = onDeleteRequest,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    file: File,
    mmproj: File?,
    active: Boolean,
    loading: Boolean,
    vision: Boolean,
    audio: Boolean,
    needMb: Long,
    approximate: Boolean,
    availMb: Long,
    enabled: Boolean,
    successTick: Int,
    onLoad: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    val fit = fitOf(needMb, availMb)
    val fitColor = when (fit) {
        Fit.Fits -> palette.success
        Fit.Tight -> palette.warning
        Fit.NoFit -> palette.error
        null -> palette.textMuted
    }
    val fitLabel = when (fit) {
        Fit.Fits -> "влезет"
        Fit.Tight -> "впритык"
        Fit.NoFit -> "не влезет"
        null -> null
    }
    val background = when {
        loading -> palette.nested
        active -> palette.nested
        else -> palette.card
    }
    val border by animateColorAsState(
        targetValue = when {
            loading -> palette.silver.copy(alpha = 0.62f)
            active -> palette.silver.copy(alpha = 0.42f)
            else -> palette.hairline
        },
        animationSpec = tween(MotionTokens.Durations.ENTER),
        label = "modelCardBorder",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(
                Brush.verticalGradient(listOf(background, palette.card)),
            )
            .border(1.dp, border, CardShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = enabled,
                onClick = onLoad,
                onLongClick = onDeleteRequest,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(palette.elevated.copy(alpha = 0.7f))
                    .border(1.dp, palette.hairline, MaterialTheme.shapes.extraSmall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Document,
                    contentDescription = null,
                    tint = if (active || loading) palette.silver else palette.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                        fontWeight = if (active || loading) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        loading -> StateDot(color = palette.silver, size = 6.dp, pulse = true)
                        active -> ActiveTag()
                    }
                }
                Spacer(Modifier.height(7.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ObsidianTag(formatSize(file.length()).uppercase().replace(" ", " "))
                    quantLabel(file.name)?.let { ObsidianTag(it) }
                    paramLabel(file.name)?.let { ObsidianTag(it) }
                    if (vision) FeatureTag(AppIcons.Image, "зрение")
                    if (audio) FeatureTag(AppIcons.Audio, "аудио")
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ПАМЯТЬ",
                        style = EyebrowStyle,
                        color = palette.textMuted,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = (if (approximate) "≈ " else "") + "нужно ${formatRam(needMb)}",
                        style = MonoSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                    )
                    if (fitLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        StateDot(color = fitColor, size = 4.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            fitLabel,
                            style = MonoSmall,
                            color = fitColor,
                            fontWeight = if (fit == Fit.NoFit) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
                if (mmproj != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "MMPROJ · ${mmproj.name}",
                        style = MonoSmall,
                        color = palette.silverDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(9.dp))
                    ObsidianProgressLine(
                        progress = null,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        trackColor = palette.elevated,
                        accent = palette.silver,
                        heightDp = 2,
                    )
                }
            }
        }
        SilverPulseOverlay(
            trigger = successTick,
            color = palette.silver,
            modifier = Modifier.matchParentSize(),
            maxScale = 1.08f,
        )
    }
}

@Composable
private fun ActiveTag() {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .clip(PillShape)
            .background(palette.silver.copy(alpha = 0.12f))
            .border(1.dp, palette.silver.copy(alpha = 0.35f), PillShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(color = palette.silver, size = 3.dp)
        Spacer(Modifier.width(5.dp))
        Text("АКТИВНА", style = EyebrowStyle, color = palette.silver)
    }
}

@Composable
private fun FeatureTag(icon: ImageVector, text: String) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(palette.silver.copy(alpha = 0.07f))
            .border(1.dp, palette.silver.copy(alpha = 0.16f), RoundedCornerShape(7.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = palette.silverDim,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = EyebrowStyle, color = palette.textSecondary)
    }
}

@Composable
private fun ProjectorRow(
    file: File,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.small)
            .background(palette.card)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Image,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textPrimary,
            )
            Text(
                formatSize(file.length()),
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
        ObsidianIconButton(
            icon = Icons.Outlined.Delete,
            contentDescription = "Удалить mmproj",
            onClick = onDelete,
            enabled = enabled,
            tint = palette.textSecondary,
            size = 36.dp,
            iconSize = 18.dp,
        )
    }
}

/**
 * Device memory panel: a segmented, animated bar plus mono readouts.
 * It uses the same real ActivityManager numbers as the header; nothing here
 * is estimated except the model footprint shown separately on each card.
 */
@Composable
private fun RamPanel(
    totalMb: Long,
    availableMb: Long,
    modelMb: Long,
) {
    val palette = ObsidianTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(palette.card)
            .border(1.dp, palette.hairline, PanelShape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(text = "память устройства", color = palette.textMuted)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatRam(availableMb)} свободно",
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        RamBar(
            totalMb = totalMb,
            availableMb = availableMb,
            modelMb = modelMb,
        )
    }
}

/**
 * Segmented RAM bar: system / model / free, with animated proportions and
 * animated numeric values.
 */
@Composable
internal fun RamBar(
    totalMb: Long,
    availableMb: Long,
    modelMb: Long,
) {
    val palette = ObsidianTheme.palette
    val total = totalMb.coerceAtLeast(1L)
    val free = availableMb.coerceIn(0L, total)
    val used = total - free
    val model = modelMb.coerceIn(0L, used)
    val system = used - model

    val reduced = LocalReducedMotion.current
    val segmentSpec = if (reduced) snap<Float>() else tween<Float>(MotionTokens.Durations.ENTER)
    val systemF by animateFloatAsState(system.toFloat() / total, segmentSpec, label = "ramSystem")
    val modelF by animateFloatAsState(model.toFloat() / total, segmentSpec, label = "ramModel")
    val freeF by animateFloatAsState(free.toFloat() / total, segmentSpec, label = "ramFree")
    val systemColor = palette.elevated
    val modelColor = palette.silver
    val freeColor = palette.success.copy(alpha = 0.62f)

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.elevated.copy(alpha = 0.35f)),
        ) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val gap = 3.dp.toPx()
                val usable = (size.width - gap * 2f).coerceAtLeast(0f)
                val fractions = floatArrayOf(systemF, modelF, freeF)
                val colors = arrayOf(systemColor, modelColor, freeColor)
                var x = 0f
                for (i in fractions.indices) {
                    val w = usable * fractions[i]
                    if (w > 0.5f) {
                        drawRoundRect(
                            color = colors[i],
                            topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                            size = androidx.compose.ui.geometry.Size(w, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                size.height / 2f,
                                size.height / 2f,
                            ),
                        )
                    }
                    x += w + gap
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            RamLegend(
                color = systemColor,
                label = "Система",
                mb = system,
                modifier = Modifier.weight(1f),
            )
            RamLegend(
                color = modelColor,
                label = "Модель",
                mb = model,
                modifier = Modifier.weight(1f),
            )
            RamLegend(
                color = freeColor,
                label = "Свободно",
                mb = free,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RamLegend(
    color: Color,
    label: String,
    mb: Long,
    modifier: Modifier = Modifier,
) {
    val palette = ObsidianTheme.palette
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label.uppercase(),
                style = EyebrowStyle,
                color = palette.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        AnimatedNumber(
            value = mb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            style = MonoNumeric.copy(color = palette.textPrimary),
            format = { formatRam(it.toLong()) },
        )
    }
}
