package com.llamaport.app

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.models.ModelsSheet
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                state = state,
                onSelectSession = { id ->
                    viewModel.selectSession(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    onNewChat()
                },
                onDeleteSession = viewModel::deleteSession,
                onRenameSession = viewModel::renameSession,
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    showSettings = true
                },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(AppIcons.Menu, contentDescription = "Меню")
                        }
                    },
                    title = {
                        ModelChip(
                            state = state,
                            onClick = { showModels = true },
                        )
                    },
                    actions = {
                        IconButton(onClick = onNewChat, enabled = !state.generating) {
                            Icon(Icons.Filled.Add, contentDescription = "Новый чат")
                        }
                    },
                )
            },
            bottomBar = {
                if (state.modelLoaded || state.messages.isNotEmpty()) {
                    ChatInputBar(
                        state = state,
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
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Процесс инференса остановлен", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Модель выгружена. Можно загрузить её снова.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(
                                onClick = viewModel::reloadModel,
                                enabled = state.modelName != null && !state.loadingModel,
                            ) {
                                Text("Перезапустить")
                            }
                        }
                    }
                }

                val promptProgress = state.progress?.takeIf { it.phase == GenerationPhase.PROMPT }
                when {
                    state.loadingModel -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    promptProgress != null -> LinearProgressIndicator(
                        progress = {
                            if (promptProgress.total > 0) {
                                promptProgress.processed.toFloat() / promptProgress.total
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                    MessageList(state)
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val progress = state.progress
        val left = when {
            progress != null && progress.phase == GenerationPhase.PROMPT ->
                "Промпт ${progress.processed}/${progress.total} · ${formatSpeed(progress.tokensPerSecond)}"
            progress != null ->
                "Генерация · ${formatSpeed(progress.tokensPerSecond)}"
            else -> state.status
        }
        Text(
            text = left ?: "",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = if (progress != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.showFreeMemory) {
            TextButton(
                onClick = onFreeMemory,
                enabled = !state.freeMemoryBusy,
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    if (state.freeMemoryBusy) "…" else "Освободить",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ToolCallBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val container = if (message.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (message.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▼" else "▶", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (message.isError) "Ошибка инструмента" else "Инструмент",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    message.toolName.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!expanded && !message.toolArgs.isNullOrBlank()) {
                Text(
                    message.toolArgs,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (expanded) {
                if (!message.toolArgs.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Аргументы", style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(
                            message.toolArgs,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Результат", style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(
                        message.toolResult ?: message.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1)
    withFrameNanos { }
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val delta = (last.offset + last.size) - info.viewportEndOffset
    if (delta > 0) scrollBy(delta.toFloat())
}

@Composable
private fun MessageList(state: UiState) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoFollow by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }

    suspend fun jumpToBottom() {
        if (state.messages.isEmpty()) return
        programmatic = true
        try {
            listState.scrollToBottom(state.messages.size)
        } finally {
            programmatic = false
        }
    }

    LaunchedEffect(state.activeSessionId, state.scrollRequest) {
        autoFollow = true
        if (state.messages.isNotEmpty()) {
            withFrameNanos { }
            jumpToBottom()
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (autoFollow && state.messages.isNotEmpty()) {
            withFrameNanos { }
            jumpToBottom()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling && !programmatic) {
                    autoFollow = !canForward
                } else if (!scrolling && !canForward) {
                    autoFollow = true
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
        if (!autoFollow) {
            FilledIconButton(
                onClick = {
                    autoFollow = true
                    scope.launch { jumpToBottom() }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(42.dp),
            ) {
                Icon(AppIcons.ArrowDown, contentDescription = "Вниз")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.role == "tool") {
        ToolCallBlock(message)
        return
    }
    val isUser = message.role == "user"
    val container = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val parts = remember(message.text, isUser) {
        if (isUser) ThinkingParts(null, message.text) else splitThinking(message.text)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (attachment in message.attachments) {
                            MessageAttachment(attachment)
                        }
                    }
                    if (parts.answer.isNotBlank() || parts.thinking != null) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (message.isError) {
                    SelectionContainer {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (isUser) {
                    if (message.text.isNotEmpty()) {
                        SelectionContainer {
                            Text(message.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    parts.thinking?.let { thinking ->
                        ThinkingBlock(
                            thinking = thinking,
                            streaming = message.streaming && parts.answer.isEmpty(),
                        )
                        if (parts.answer.isNotEmpty()) Spacer(Modifier.height(8.dp))
                    }
                    if (parts.answer.isNotEmpty()) {
                        SelectionContainer {
                            MarkdownText(
                                text = parts.answer,
                                streaming = message.streaming,
                            )
                        }
                    } else if (message.streaming && parts.thinking == null) {
                        Text("▍", fontSize = 15.sp)
                    }
                }

                message.stats?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "▼" else "▶", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Размышления",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (streaming) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· думает…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${thinking.length} симв.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(7.dp))
                MarkdownText(thinking, streaming = streaming)
            }
        }
    }
}

@Composable
private fun MessageAttachment(attachment: Attachment) {
    when (attachment.kind) {
        "image" -> ImageAttachment(attachment)
        else -> AudioAttachment(attachment)
    }
}

@Composable
private fun ImageAttachment(attachment: Attachment) {
    val bitmap by produceState<ImageBitmap?>(null, attachment.path) {
        value = withContext(Dispatchers.IO) { decodeSampledBitmap(attachment.path) }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = attachment.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = 190.dp)
                .widthIn(max = 250.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(9.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(attachment.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AudioAttachment(attachment: Attachment) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val player = remember(attachment.path) {
        runCatching {
            MediaPlayer.create(context, Uri.fromFile(File(attachment.path)))
        }.getOrNull()
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false }
        onDispose {
            runCatching { player?.release() }
        }
    }
    val duration = remember(player) {
        val ms = runCatching { player?.duration ?: 0 }.getOrDefault(0)
        if (ms > 0) formatDuration(ms) else ""
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val mediaPlayer = player ?: return@IconButton
                    if (playing) {
                        runCatching { mediaPlayer.pause() }
                        playing = false
                    } else {
                        runCatching { mediaPlayer.start() }
                        playing = true
                    }
                },
                enabled = player != null,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    if (playing) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (playing) "Пауза" else "Прослушать",
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (duration.isBlank()) "аудио" else "аудио · $duration",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
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
                            .clip(RoundedCornerShape(7.dp)),
                    )
                } else {
                    Icon(
                        AppIcons.Image,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            } else {
                Icon(
                    AppIcons.Audio,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.widthIn(max = 150.dp)) {
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (attachment.kind == "image") "изображение" else "аудио",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать", modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun decodeSampledBitmap(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

private fun formatDuration(milliseconds: Int): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

@Composable
private fun ChatInputBar(
    state: UiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 7.dp),
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
                if (state.modelLoaded && (state.supportsVision || state.supportsAudio)) {
                    IconButton(
                        onClick = onAttach,
                        enabled = !state.generating,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            AppIcons.Attach,
                            contentDescription = "Прикрепить изображение или аудио",
                        )
                    }
                }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (state.modelLoaded) "Сообщение…" else "Сначала загрузите модель")
                    },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (state.generating) onStop() else onSend() },
                    enabled = state.generating || (
                        state.modelLoaded &&
                            (state.input.isNotBlank() || state.pendingAttachments.isNotEmpty())
                        ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (state.generating) AppIcons.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.generating) "Остановить" else "Отправить",
                    )
                }
            }
        }
    }
}

@Composable
internal fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                if (session.preview.isNotBlank()) {
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${session.messageCount} сообщ. · " +
                        DateUtils.getRelativeTimeSpanString(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    state: UiState,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onReload: () -> Unit,
) {
    val settings = state.settings
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = settings.systemPrompt,
            onValueChange = { value -> onUpdate { it.copy(systemPrompt = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Системный промпт") },
            minLines = 2,
            maxLines = 5,
        )

        Spacer(Modifier.height(16.dp))
        val contextValues = remember(state.modelCap, settings.contextSize) {
            contextOptions(state.modelCap, settings.contextSize)
        }
        val contextIndex = contextValues.indexOf(settings.contextSize).let { index ->
            if (index >= 0) index else contextValues.indexOfFirst { it >= settings.contextSize }
                .coerceAtLeast(0)
        }
        SliderRow(
            label = "Контекст",
            valueText = "${settings.contextSize} токенов · макс. ${state.modelCap}",
            value = contextIndex.toFloat(),
            range = 0f..(contextValues.lastIndex).toFloat(),
            steps = (contextValues.size - 2).coerceAtLeast(0),
            onChange = { v ->
                val index = v.roundToInt().coerceIn(0, contextValues.lastIndex)
                onUpdate { it.copy(contextSize = contextValues[index]) }
            },
        )
        if (settings.contextSize > 8192) {
            Text(
                "Большой контекст увеличивает KV-кэш. Если модель не поместится в RAM, " +
                    "она может быть выгружена системой.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val memoryNeedMb = remember(state.estimate, settings.contextSize, state.mmprojBytes) {
            val estimate = state.estimate
            if (estimate == null) {
                0L
            } else {
                val kv = if (estimate.estimateCtx > 0) {
                    estimate.kvBytes * settings.contextSize / estimate.estimateCtx
                } else {
                    estimate.kvBytes
                }
                (estimate.fileBytes + kv + state.mmprojBytes) / (1024L * 1024L) + 300L
            }
        }
        if (memoryNeedMb > 0) {
            Text(
                "Оценка памяти: ~${formatRam(memoryNeedMb)} · свободно " +
                    "${formatRam(state.memAvailableMb)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (memoryNeedMb > state.memAvailableMb) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Text(
                "Реальный максимум контекста берётся из метаданных модели и появится после её выбора.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SliderRow(
            label = "Потоки генерации",
            valueText = settings.threads.toString(),
            value = settings.threads.toFloat(),
            range = 1f..8f,
            steps = 6,
            onChange = { v -> onUpdate { it.copy(threads = v.toInt()) } },
        )
        SliderRow(
            label = "Потоки промпта",
            valueText = settings.threadsBatch.toString(),
            value = settings.threadsBatch.toFloat(),
            range = 1f..8f,
            steps = 6,
            onChange = { v -> onUpdate { it.copy(threadsBatch = v.toInt()) } },
        )
        val batchValues = remember { listOf(64, 128, 256, 512, 1024, 2048) }
        val batchIndex = batchValues.indexOf(settings.nBatch).let { index ->
            if (index >= 0) index else batchValues.indexOfFirst { it >= settings.nBatch }
                .let { found -> if (found >= 0) found else batchValues.lastIndex }
        }
        SliderRow(
            label = "Пакет промпта",
            valueText = "${settings.nBatch} токенов",
            value = batchIndex.toFloat(),
            range = 0f..batchValues.lastIndex.toFloat(),
            steps = (batchValues.size - 2).coerceAtLeast(0),
            onChange = { v ->
                val index = v.roundToInt().coerceIn(0, batchValues.lastIndex)
                onUpdate { it.copy(nBatch = batchValues[index]) }
            },
        )
        val ubatchValues = remember { listOf(32, 64, 128, 256, 512) }
        val ubatchIndex = ubatchValues.indexOf(settings.nUbatch).let { index ->
            if (index >= 0) index else ubatchValues.indexOfFirst { it >= settings.nUbatch }
                .let { found -> if (found >= 0) found else ubatchValues.lastIndex }
        }
        SliderRow(
            label = "Микробатч",
            valueText = "${settings.nUbatch} токенов",
            value = ubatchIndex.toFloat(),
            range = 0f..ubatchValues.lastIndex.toFloat(),
            steps = (ubatchValues.size - 2).coerceAtLeast(0),
            onChange = { v ->
                val index = v.roundToInt().coerceIn(0, ubatchValues.lastIndex)
                onUpdate { it.copy(nUbatch = minOf(ubatchValues[index], settings.nBatch)) }
            },
        )
        val flashLabels = remember { listOf("auto", "включён", "выключен") }
        SliderRow(
            label = "Flash Attention",
            valueText = flashLabels[settings.flashAttn.coerceIn(0, flashLabels.lastIndex)],
            value = settings.flashAttn.coerceIn(0, flashLabels.lastIndex).toFloat(),
            range = 0f..flashLabels.lastIndex.toFloat(),
            steps = 1,
            onChange = { v -> onUpdate { it.copy(flashAttn = v.roundToInt()) } },
        )
        SliderRow(
            label = "Температура",
            valueText = String.format(Locale.ROOT, "%.2f", settings.temperature),
            value = settings.temperature,
            range = 0f..2f,
            steps = 19,
            onChange = { v -> onUpdate { it.copy(temperature = (v * 100).toInt() / 100f) } },
        )
        SliderRow(
            label = "Top-P",
            valueText = String.format(Locale.ROOT, "%.2f", settings.topP),
            value = settings.topP,
            range = 0.1f..1f,
            steps = 17,
            onChange = { v -> onUpdate { it.copy(topP = v) } },
        )
        SliderRow(
            label = "Top-K",
            valueText = settings.topK.toString(),
            value = settings.topK.toFloat(),
            range = 1f..100f,
            steps = 0,
            onChange = { v -> onUpdate { it.copy(topK = v.toInt().coerceAtLeast(1)) } },
        )
        SliderRow(
            label = "Максимум новых токенов",
            valueText = settings.maxTokens.toString(),
            value = settings.maxTokens.toFloat(),
            range = 64f..2048f,
            steps = 30,
            onChange = { v -> onUpdate { it.copy(maxTokens = (v.toInt() / 64) * 64) } },
        )

        val thinkingValues = remember { listOf(0, 512, 1024, 2048, 4096, 8192) }
        val thinkingIndex = thinkingValues.indexOf(settings.thinkingBudget).let { index ->
            if (index >= 0) index else thinkingValues.indexOfFirst { it >= settings.thinkingBudget }
                .coerceAtLeast(0)
        }
        SliderRow(
            label = "Бюджет размышлений",
            valueText = if (settings.thinkingBudget > 0) {
                "${settings.thinkingBudget} токенов"
            } else {
                "выключен"
            },
            value = thinkingIndex.toFloat(),
            range = 0f..(thinkingValues.lastIndex).toFloat(),
            steps = (thinkingValues.size - 2).coerceAtLeast(0),
            onChange = { v ->
                val index = v.roundToInt().coerceIn(0, thinkingValues.lastIndex)
                onUpdate { it.copy(thinkingBudget = thinkingValues[index]) }
            },
        )
        Text(
            "Бюджет добавляется к максимуму новых токенов. Даже при выключенном бюджете " +
                "модель может напечатать блок размышлений — он будет показан свёрнутым.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow(
            label = "mmap модели",
            description = "Не копировать веса в память целиком; экономит RAM, но файл должен оставаться на месте.",
            checked = settings.useMmap,
            onChange = { checked -> onUpdate { it.copy(useMmap = checked) } },
        )
        SwitchRow(
            label = "KV-кэш q8_0",
            description = "Сжимает кэш контекста примерно вдвое (нужен Flash Attention).",
            checked = settings.kvType == 1,
            onChange = { checked -> onUpdate { it.copy(kvType = if (checked) 1 else 0) } },
        )

        Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Инструменты", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            label = "Разрешить инструменты",
            description = "Модель сможет вызывать калькулятор, файлы в песочнице и другие инструменты.",
            checked = settings.toolsEnabled,
            onChange = { checked -> onUpdate { it.copy(toolsEnabled = checked) } },
        )
        if (settings.toolsEnabled) {
            for (option in TOOL_OPTIONS) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = option.name in settings.enabledTools,
                        onCheckedChange = { checked ->
                            onUpdate { current ->
                                current.copy(
                                    enabledTools = if (checked) {
                                        current.enabledTools + option.name
                                    } else {
                                        current.enabledTools - option.name
                                    },
                                    httpToolEnabled = if (option.name == "http_get") checked else current.httpToolEnabled,
                                )
                            }
                        },
                    )
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
            Text(
                "Файлы доступны только в папке sandbox приложения. http_get и run_js по умолчанию выключены.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            state.backendInfo.ifBlank { "llama.cpp" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
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

private fun formatSpeed(tokensPerSecond: Float): String =
    if (tokensPerSecond >= 10f) {
        String.format(Locale.ROOT, "%.0f tok/s", tokensPerSecond)
    } else {
        String.format(Locale.ROOT, "%.1f tok/s", tokensPerSecond)
    }

private fun contextOptions(cap: Int, current: Int): List<Int> {
    val base = listOf(
        512,
        1024,
        2048,
        3072,
        4096,
        6144,
        8192,
        12288,
        16384,
        24576,
        32768,
        49152,
        65536,
        98304,
        131072,
    )
    val maximum = maxOf(cap, current, 512)
    return (base.filter { it < maximum } + maximum + current).distinct().sorted()
}

private fun mediaMimeTypes(state: UiState): Array<String> = when {
    state.supportsVision && state.supportsAudio -> arrayOf("image/*", "audio/*")
    state.supportsVision -> arrayOf("image/*")
    state.supportsAudio -> arrayOf("audio/*")
    else -> arrayOf("image/*", "audio/*")
}
