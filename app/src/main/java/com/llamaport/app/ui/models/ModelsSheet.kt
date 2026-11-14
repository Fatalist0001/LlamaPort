package com.llamaport.app.ui.models

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.llamaport.app.UiState
import com.llamaport.app.formatRam
import com.llamaport.app.formatSize
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.motion.AnimatedNumber
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import java.io.File

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

@Composable
internal fun ModelsSheet(
    state: UiState,
    onPick: () -> Unit,
    onLoad: (File) -> Unit,
    onDelete: (File) -> Unit,
    onUnload: () -> Unit,
    onMmproj: (File) -> File?,
) {
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    val scheme = MaterialTheme.colorScheme
    val enabled = !state.generating && !state.loadingModel
    val availMb = state.memAvailableMb

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
                Text("Модели GGUF", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    state.modelInfo ?: "Выберите файл модели, чтобы загрузить её в память",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (state.memTotalMb > 0) {
            Reveal(index = 1) {
                RamBar(
                    totalMb = state.memTotalMb,
                    availableMb = availMb,
                    modelMb = modelMb,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (state.models.isEmpty()) {
            Text(
                "В приложении пока нет моделей. Нажмите «Добавить», чтобы выбрать .gguf файл.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
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
                Reveal(index = 2 + index) {
                    SwipeableModelCard(
                        file = file,
                        mmproj = mmproj,
                        active = active,
                        vision = if (active) state.supportsVision else mmproj != null,
                        audio = active && state.supportsAudio,
                        needMb = needMb,
                        approximate = approximate,
                        availMb = availMb,
                        enabled = enabled,
                        onLoad = { onLoad(file) },
                        onDeleteRequest = { pendingDelete = file },
                    )
                }
            }
        }

        if (state.projectors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Text("Проекторы mmproj", style = MaterialTheme.typography.titleSmall)
            Text(
                "Подключаются автоматически к модели с совпадающим именем " +
                    "(например, mmproj-LFM3-Omni-2B-Q4_K_M.gguf).",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            state.projectors.forEach { file ->
                ProjectorRow(
                    file = file,
                    enabled = enabled,
                    onDelete = { pendingDelete = file },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.generating,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить модель или mmproj")
        }
        if (state.modelLoaded) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onUnload,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.generating,
            ) {
                Text("Выгрузить модель")
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить файл?") },
            text = {
                Text(
                    "«${file.name}» (${formatSize(file.length())}) будет удалён " +
                        "из хранилища приложения.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(file)
                        pendingDelete = null
                    },
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Отмена")
                }
            },
        )
    }
}

/**
 * Model card: name, size, quantization, vision/audio badges and a
 * fit-for-RAM label. Tap loads, long-press (or swipe) asks to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableModelCard(
    file: File,
    mmproj: File?,
    active: Boolean,
    vision: Boolean,
    audio: Boolean,
    needMb: Long,
    approximate: Boolean,
    availMb: Long,
    enabled: Boolean,
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
        modifier = Modifier.padding(vertical = 4.dp),
        backgroundContent = {
            // Show the delete hint only while the card is actually swiped,
            // so nothing peeks out at rest.
            val reveal = dismissState.progress
            if (reveal > 0.02f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(
                                alpha = (reveal * 1.6f).coerceAtMost(1f),
                            ),
                        )
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        ModelCard(
            file = file,
            mmproj = mmproj,
            active = active,
            vision = vision,
            audio = audio,
            needMb = needMb,
            approximate = approximate,
            availMb = availMb,
            enabled = enabled,
            onLoad = onLoad,
            onDeleteRequest = onDeleteRequest,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelCard(
    file: File,
    mmproj: File?,
    active: Boolean,
    vision: Boolean,
    audio: Boolean,
    needMb: Long,
    approximate: Boolean,
    availMb: Long,
    enabled: Boolean,
    onLoad: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fit = fitOf(needMb, availMb)
    val fitColor by animateColorAsState(
        targetValue = when (fit) {
            Fit.Fits -> scheme.primary
            Fit.Tight -> scheme.tertiary
            Fit.NoFit -> scheme.error
            null -> scheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = MotionTokens.Durations.ENTER),
        label = "fitColor",
    )
    val fitLabel = when (fit) {
        Fit.Fits -> "влезет"
        Fit.Tight -> "впритык"
        Fit.NoFit -> "не влезет"
        null -> null
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (active) scheme.secondaryContainer else scheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = enabled,
                onClick = onLoad,
                onLongClick = onDeleteRequest,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Document,
                contentDescription = null,
                tint = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatSize(file.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    quantLabel(file.name)?.let { quant ->
                        Text(
                            "  ·  $quant",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (vision) {
                        Spacer(Modifier.width(6.dp))
                        Badge("зрение")
                    }
                    if (audio) {
                        Spacer(Modifier.width(4.dp))
                        Badge("аудио")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            if (approximate) append("≈ ")
                            append("нужно ${formatRam(needMb)}")
                            fitLabel?.let { append(" · ") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (fitLabel != null) {
                        Text(
                            fitLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = fitColor,
                            fontWeight = if (fit == Fit.NoFit) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }

                if (mmproj != null) {
                    Text(
                        "mmproj: ${mmproj.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (active) {
                Text(
                    "активна",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ProjectorRow(
    file: File,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    formatSize(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = "Удалить mmproj")
            }
        }
    }
}

/**
 * Segmented RAM bar: system / model / free, with animated proportions.
 */
@Composable
internal fun RamBar(
    totalMb: Long,
    availableMb: Long,
    modelMb: Long,
) {
    val scheme = MaterialTheme.colorScheme
    val total = totalMb.coerceAtLeast(1L)
    val free = availableMb.coerceIn(0L, total)
    val used = total - free
    val model = modelMb.coerceIn(0L, used)
    val system = used - model

    val systemF by animateFloatAsState(system.toFloat() / total, tween(MotionTokens.Durations.ENTER), label = "ramSystem")
    val modelF by animateFloatAsState(model.toFloat() / total, tween(MotionTokens.Durations.ENTER), label = "ramModel")
    val freeF by animateFloatAsState(free.toFloat() / total, tween(MotionTokens.Durations.ENTER), label = "ramFree")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.surfaceVariant),
        ) {
            Segment(systemF, scheme.onSurfaceVariant.copy(alpha = 0.35f))
            Segment(modelF, scheme.primary)
            Segment(freeF, scheme.secondaryContainer)
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Legend(scheme.onSurfaceVariant.copy(alpha = 0.35f), "Система", system, Modifier.weight(1f))
            Legend(scheme.primary, "Модель", model, Modifier.weight(1f))
            Legend(scheme.secondaryContainer, "Свободно", free, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.Segment(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .fillMaxHeight()
            .weight(fraction.coerceAtLeast(0.0001f))
            .background(color),
    )
}

@Composable
private fun Legend(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    mb: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        AnimatedNumber(
            value = mb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            style = MaterialTheme.typography.labelSmall,
            format = { formatRam(it.toLong()) },
        )
    }
}