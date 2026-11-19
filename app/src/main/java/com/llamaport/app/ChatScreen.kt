package com.llamaport.app

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.chat.MessageList
import com.llamaport.app.ui.chat.decodeSampledBitmap
import com.llamaport.app.ui.models.ModelsSheet
import com.llamaport.app.ui.components.MonoValue
import com.llamaport.app.ui.components.ObsidianBackground
import com.llamaport.app.ui.components.ObsidianIconButton
import com.llamaport.app.ui.components.ObsidianProgressLine
import com.llamaport.app.ui.components.SilverPulseOverlay
import com.llamaport.app.ui.components.SilverShimmerLine
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.AnimatedNumber
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.settings.SettingsSheet
import com.llamaport.app.ui.theme.MonoNumeric
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaPortApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var emptyShakeTick by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.attachMedia(uris)
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // "+" on an empty chat does not create another one: it shakes the
    // current one so the user sees where they are.
    val onNewChat: () -> Unit = {
        if (state.messages.isEmpty()) {
            emptyShakeTick++
        } else if (!state.generating) {
            viewModel.newChat()
        }
    }

    val palette = ObsidianTheme.palette

    ObsidianBackground(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = if (palette.isDark) {
            Color.Black.copy(alpha = 0.68f)
        } else {
            Color.Black.copy(alpha = 0.32f)
        },
        drawerContent = {
            AppDrawer(
                state = state,
                // Drawer content stays composed while closed — pass visibility
                // so the header robot does not animate offscreen.
                drawerActive = drawerState.targetValue == DrawerValue.Open ||
                    drawerState.isAnimationRunning,
                snackbarHostState = snackbarHostState,
                onSelectSession = { id ->
                    viewModel.selectSession(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    onNewChat()
                },
                onDeleteSession = { id ->
                    viewModel.deleteSession(id)
                    scope.launch {
                        // One undo level: a newer deletion replaces the pending one.
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = "Чат удалён",
                            actionLabel = "Отменить",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete()
                        } else {
                            viewModel.clearUndo()
                        }
                    }
                },
                onRenameSession = viewModel::renameSession,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    showSettings = true
                },
            )
        },
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                ObsidianTopBar(
                    state = state,
                    onMenu = { scope.launch { drawerState.open() } },
                    onModelClick = { showModels = true },
                    onNewChat = onNewChat,
                )
            },
            bottomBar = {
                if (state.modelLoaded || state.messages.isNotEmpty()) {
                    ChatInputBar(
                        state = state,
                        live = viewModel.live,
                        onInput = viewModel::onInputChange,
                        onSend = viewModel::send,
                        onStop = viewModel::stop,
                        onAttach = {
                            mediaPicker.launch(mediaMimeTypes(state))
                        },
                        onRemoveAttachment = viewModel::removePendingAttachment,
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                StatusStrip(state, onFreeMemory = viewModel::freeMemory)

                if (state.serviceDead) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(palette.error.copy(alpha = 0.10f))
                            .border(1.dp, palette.error.copy(alpha = 0.30f), MaterialTheme.shapes.small)
                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StateDot(color = palette.error, size = 6.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Процесс инференса остановлен",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.textPrimary,
                            )
                            Text(
                                "Модель выгружена. Можно загрузить её снова.",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                            )
                        }
                        TextButton(
                            onClick = viewModel::reloadModel,
                            enabled = state.modelName != null && !state.loadingModel,
                        ) {
                            Text("Перезапустить", color = palette.silver)
                        }
                    }
                }

                val promptProgress = state.progress?.takeIf { it.phase == GenerationPhase.PROMPT }
                when {
                    state.loadingModel -> ObsidianProgressLine(
                        progress = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(3.dp),
                        trackColor = palette.elevated,
                        accent = palette.silver,
                    )
                    promptProgress != null -> ObsidianProgressLine(
                        progress = if (promptProgress.total > 0) {
                            promptProgress.processed.toFloat() / promptProgress.total
                        } else {
                            0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(3.dp),
                        trackColor = palette.elevated,
                        accent = palette.silver,
                    )
                }

                if (state.messages.isEmpty()) {
                    HomeEmptyState(
                        state = state,
                        shakeTick = emptyShakeTick,
                        onChooseModel = { showModels = true },
                        onSuggestion = viewModel::onInputChange,
                    )
                } else {
                    MessageList(state, viewModel.live)
                }
            }
        }
    }
    }

    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = sheetState,
        ) {
            ModelsSheet(
                state = state,
                onPick = { picker.launch(arrayOf("*/*")) },
                onLoad = {
                    viewModel.loadModel(it)
                    showModels = false
                },
                onDelete = viewModel::deleteModel,
                onUnload = viewModel::unloadModel,
                onMmproj = viewModel::matchedMmproj,
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
        ) {
            SettingsSheet(
                state = state,
                onUpdate = viewModel::updateSettings,
                onReload = viewModel::reloadModel,
            )
        }
    }

    state.memoryWarning?.let { warning ->
        MemoryWarningDialog(
            warning = warning,
            busy = state.freeMemoryBusy,
            onFreeMemory = viewModel::freeMemory,
            onReduce = viewModel::applySuggestedAndLoad,
            onLoadAnyway = viewModel::loadAnyway,
            onCancel = viewModel::dismissMemoryWarning,
        )
    }
}

@Composable
private fun MemoryWarningDialog(
    warning: MemoryWarning,
    busy: Boolean,
    onFreeMemory: () -> Unit,
    onReduce: () -> Unit,
    onLoadAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Памяти может не хватить") },
        text = {
            Column {
                Text(
                    "Для этой модели нужно примерно ${formatRam(warning.needMb)} " +
                        "(файл + KV-кэш + запас), а свободно ${formatRam(warning.availMb)}. " +
                        "С mmap модель может загрузиться, но система может её выгрузить.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onFreeMemory,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(if (busy) "Освобождаю память…" else "Освободить память")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onReduce,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Уменьшить контекст до ${warning.suggestedCtx} и KV q8_0")
                }
                TextButton(
                    onClick = onLoadAnyway,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Всё равно загрузить (mmap)")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun StatusStrip(state: UiState, onFreeMemory: () -> Unit) {
    val palette = ObsidianTheme.palette
    val progress = state.progress
    val left = when {
        progress != null && progress.phase == GenerationPhase.PROMPT ->
            if (progress.total > 0) {
                "промпт ${progress.processed}/${progress.total}"
            } else {
                "промпт ${progress.processed}"
            }
        state.generating -> "генерация"
        else -> state.status
    }
    val active = state.generating || progress != null
    val visible = left != null || state.showFreeMemory
    val accent = when {
        state.serviceDead -> palette.error
        progress != null || state.generating -> palette.silver
        else -> palette.textMuted
    }

    AnimatedVisibility(visible = visible) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateDot(
                    color = accent,
                    size = 5.dp,
                    pulse = state.generating || state.loadingModel,
                )
                Spacer(Modifier.width(8.dp))
                if (left != null) {
                    Text(
                        text = left,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.2.sp,
                        ),
                        color = if (active) palette.silver else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (state.showFreeMemory) {
                    TextButton(
                        onClick = onFreeMemory,
                        enabled = !state.freeMemoryBusy,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            if (state.freeMemoryBusy) "…" else "Освободить",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.silver,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (state.generating) {
                SilverShimmerLine(
                    modifier = Modifier.fillMaxWidth().height(1.dp),
                    color = palette.silver,
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(palette.card)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
            .padding(start = 5.dp, end = 3.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (attachment.kind == "image") {
            val bitmap by produceState<ImageBitmap?>(null, attachment.path) {
                value = withContext(Dispatchers.IO) { decodeSampledBitmap(attachment.path) }
            }
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Icon(
                    AppIcons.Image,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        } else {
            Icon(
                AppIcons.Audio,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.widthIn(max = 150.dp)) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (attachment.kind == "image") "изображение" else "аудио",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
            )
        }
        ObsidianIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "Убрать",
            onClick = onRemove,
            tint = palette.textSecondary,
            size = 28.dp,
            iconSize = 15.dp,
            shape = MaterialTheme.shapes.extraSmall,
        )
    }
}

@Composable
private fun ChatInputBar(
    state: UiState,
    live: StreamingAnswer,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fieldShape = RoundedCornerShape(22.dp)
    val border by animateColorAsState(
        targetValue = when {
            focused -> palette.silver.copy(alpha = 0.55f)
            else -> palette.hairline
        },
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.MICRO),
        label = "inputBorder",
    )

    var completionTick by remember { mutableIntStateOf(0) }
    var wasGenerating by remember { mutableStateOf(state.generating) }
    LaunchedEffect(state.generating) {
        if (wasGenerating && !state.generating) completionTick++
        wasGenerating = state.generating
    }
    val canSend = state.modelLoaded &&
        (state.input.isNotBlank() || state.pendingAttachments.isNotEmpty())

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            palette.hairline.copy(alpha = 0f),
                            palette.hairlineStrong,
                            palette.hairline.copy(alpha = 0f),
                        ),
                    ),
                ),
        )
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp, start = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    for (attachment in state.pendingAttachments) {
                        PendingAttachmentChip(attachment) {
                            onRemoveAttachment(attachment)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(fieldShape)
                        .background(palette.card)
                        .border(1.dp, border, fieldShape)
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (state.modelLoaded && (state.supportsVision || state.supportsAudio)) {
                        ObsidianIconButton(
                            icon = AppIcons.Attach,
                            contentDescription = "Прикрепить изображение или аудио",
                            onClick = onAttach,
                            enabled = !state.generating,
                            tint = palette.textSecondary,
                            size = 38.dp,
                            iconSize = 19.dp,
                            shape = CircleShape,
                        )
                    }
                    BasicTextField(
                        value = state.input,
                        onValueChange = onInput,
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (state.supportsVision || state.supportsAudio) 2.dp else 10.dp,
                                end = 8.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = palette.textPrimary,
                        ),
                        maxLines = 5,
                        cursorBrush = SolidColor(palette.silver),
                        interactionSource = interaction,
                        decorationBox = { inner ->
                            Box {
                                if (state.input.isEmpty()) {
                                    Text(
                                        text = if (state.modelLoaded) {
                                            "Сообщение…"
                                        } else {
                                            "Сначала загрузите модель"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                if (state.generating) {
                    Spacer(Modifier.width(8.dp))
                    SpeedChip(live)
                }
                Spacer(Modifier.width(8.dp))
                SendStopButton(
                    generating = state.generating,
                    enabled = canSend,
                    completionTick = completionTick,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/**
 * Fluid morph between send and stop: the silver disc settles into a dark
 * rounded square as generation starts, and a single silver pulse confirms the
 * end of the answer.
 */
@Composable
private fun SendStopButton(
    generating: Boolean,
    enabled: Boolean,
    completionTick: Int,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    val spec = if (reduced) snap<Dp>() else tween<Dp>(MotionTokens.Durations.SHORT)
    val radius by animateDpAsState(
        targetValue = if (generating) 15.dp else 24.dp,
        animationSpec = spec,
        label = "sendStopRadius",
    )
    val background by animateColorAsState(
        targetValue = when {
            generating -> palette.nested
            enabled -> palette.silver
            else -> palette.elevated
        },
        animationSpec = tween(MotionTokens.Durations.MICRO),
        label = "sendStopBackground",
    )
    val foreground = when {
        generating -> palette.error
        palette.isDark -> Color(0xFF111214)
        else -> Color(0xFFF7F7F9)
    }
    val interactive = generating || enabled
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .size(48.dp)
            .graphicsLayer { alpha = if (interactive) 1f else 0.4f }
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                if (generating) palette.hairlineStrong else Color.Transparent,
                shape,
            )
            .pressable(enabled = interactive) {
                if (generating) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = generating,
            transitionSpec = {
                if (reduced) {
                    fadeIn(tween(MotionTokens.Durations.FADE_REDUCED)) togetherWith
                        fadeOut(tween(MotionTokens.Durations.FADE_REDUCED))
                } else {
                    (
                        fadeIn(tween(MotionTokens.Durations.MICRO)) +
                            scaleIn(
                                initialScale = 0.45f,
                                animationSpec = MotionTokens.Springs.Press,
                            )
                        ) togetherWith (
                        fadeOut(tween(MotionTokens.Durations.FADE_REDUCED)) +
                            scaleOut(targetScale = 0.45f)
                        )
                }
            },
            label = "sendStopMorph",
        ) { isGenerating ->
            Icon(
                imageVector = if (isGenerating) AppIcons.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (isGenerating) "Остановить" else "Отправить",
                tint = foreground,
                modifier = Modifier.size(if (isGenerating) 17.dp else 21.dp),
            )
        }
        SilverPulseOverlay(
            trigger = completionTick,
            color = palette.silver,
            modifier = Modifier.matchParentSize(),
            maxScale = 1.5f,
        )
    }
}

/** Live generation speed right next to the send/stop button. */
@Composable
private fun SpeedChip(live: StreamingAnswer) {
    // Read here, not in ChatInputBar: a speed tick must not recompose the bar.
    val palette = ObsidianTheme.palette
    val tokensPerSecond = live.speed
    Row(
        Modifier
            .clip(PillShape)
            .background(palette.nested)
            .border(1.dp, palette.hairline, PillShape)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(color = palette.silver, size = 4.dp, pulse = true)
        Spacer(Modifier.width(6.dp))
        AnimatedNumber(
            value = tokensPerSecond,
            style = MonoNumeric.copy(fontSize = 11.sp, color = palette.silver),
            format = { String.format(Locale.ROOT, "%.1f t/s", it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        modifier = modifier.padding(vertical = 4.dp),
        backgroundContent = {
            // Show the delete hint only while the row is actually swiped.
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
        Surface(
            onClick = onSelect,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            color = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val preview = session.preview.ifBlank {
                        if (session.messageCount > 0) "Думает…" else ""
                    }
                    if (preview.isNotBlank()) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${session.messageCount} сообщ. · " + shortRelativeTime(session.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (session.modelName.isNotBlank()) {
                            SessionModelBadge(session.modelName)
                        }
                    }
                }
                IconButton(onClick = onRename, enabled = enabled) {
                    Icon(Icons.Outlined.Create, contentDescription = "Переименовать")
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Удалить чат")
                }
            }
        }
    }
}

/** Compact "1 мин / 3 ч / 4 дн" label for the chat list. */
private fun shortRelativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "сейчас"
        minutes < 60 -> "$minutes мин"
        minutes < 1440 -> "${minutes / 60} ч"
        minutes < 1440 * 7 -> "${minutes / 1440} дн"
        minutes < 1440 * 30 -> "${minutes / (1440 * 7)} нед"
        else -> "${minutes / (1440 * 30)} мес"
    }
}

/** Tiny chip naming the model the chat was held with. */
@Composable
private fun SessionModelBadge(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            name.substringBeforeLast('.').take(18),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 116.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format(Locale.ROOT, "%.2f ГБ", mb / 1024)
    } else {
        String.format(Locale.ROOT, "%.0f МБ", mb)
    }
}

internal fun formatRam(mb: Long): String =
    if (mb >= 1024) {
        String.format(Locale.ROOT, "%.1f ГБ", mb / 1024.0)
    } else {
        "$mb МБ"
    }



private fun mediaMimeTypes(state: UiState): Array<String> = when {
    state.supportsVision && state.supportsAudio -> arrayOf("image/*", "audio/*")
    state.supportsVision -> arrayOf("image/*")
    state.supportsAudio -> arrayOf("audio/*")
    else -> arrayOf("image/*", "audio/*")
}
