package com.llamaport.app.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.Attachment
import com.llamaport.app.ChatMessage
import com.llamaport.app.MarkdownText
import com.llamaport.app.MessageMetrics
import com.llamaport.app.StreamingAnswer
import com.llamaport.app.ThinkingParts
import com.llamaport.app.UiState
import com.llamaport.app.splitThinking
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.components.Eyebrow
import com.llamaport.app.ui.components.Hairline
import com.llamaport.app.ui.components.ObsidianIconButton
import com.llamaport.app.ui.components.ObsidianTag
import com.llamaport.app.ui.components.SilverShimmerLine
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.disclosureEnter
import com.llamaport.app.ui.motion.disclosureExit
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.robot.RobotAvatar
import com.llamaport.app.ui.theme.ChatBubbleShape
import com.llamaport.app.ui.theme.CodeShape
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoBody
import com.llamaport.app.ui.theme.MonoNumeric
import com.llamaport.app.ui.theme.MonoSmall
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MessageList(state: UiState, live: StreamingAnswer) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoFollow by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }

    suspend fun jumpToBottom() {
        if (state.messages.isEmpty()) return
        programmatic = true
        try {
            // While a long answer streams in, its item is taller than the
            // viewport. Re-aligning the item every token (scrollToItem, then a
            // frame, then scrollBy) made the answer jump between its top and
            // its bottom on every token. When the tail is already on screen,
            // just follow the growing edge by the missing pixels instead.
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.lastOrNull()
            if (tail == null || tail.index != state.messages.lastIndex) {
                listState.scrollToItem(state.messages.lastIndex)
            }
            val end = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return
            val delta = (end.offset + end.size) - listState.layoutInfo.viewportEndOffset
            if (delta > 0) listState.scrollBy(delta.toFloat())
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

    LaunchedEffect(
        state.messages.size,
        // When the live speed line appears, bring it into view as well.
        state.messages.lastOrNull()?.metrics != null,
    ) {
        if (autoFollow && state.messages.isNotEmpty()) {
            withFrameNanos { }
            jumpToBottom()
        }
    }

    // Follow the growing answer without reading the live text in composition:
    // the observation lives in this coroutine, so a token does not recompose
    // the whole list (and with it every message).
    LaunchedEffect(live) {
        snapshotFlow { live.revision }
            .collect {
                if (autoFollow && live.messageId != 0L) {
                    withFrameNanos { }
                    jumpToBottom()
                }
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

    val palette = ObsidianTheme.palette
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                PopIn { MessageBubble(message, live) }
            }
        }
        if (!autoFollow) {
            ObsidianIconButton(
                icon = AppIcons.ArrowDown,
                contentDescription = "Вниз",
                onClick = {
                    autoFollow = true
                    scope.launch { jumpToBottom() }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
                container = palette.card,
                borderColor = palette.hairline,
                tint = palette.silver,
                size = 42.dp,
                iconSize = 20.dp,
            )
        }
    }
}

/** Messages pop in with a spring: fade + slight scale-up. */
@Composable
private fun PopIn(content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 0f else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = if (reduced) {
                tween(durationMillis = MotionTokens.Durations.FADE_REDUCED)
            } else {
                MotionTokens.Springs.Pop
            },
        )
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            if (!reduced) {
                val scale = 0.92f + 0.08f * progress.value
                scaleX = scale
                scaleY = scale
            }
        },
    ) {
        content()
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, live: StreamingAnswer) {
    if (message.role == "tool") {
        ToolCallBlock(message)
        return
    }
    val isUser = message.role == "user"
    // Reading the live answer here (and not at the list level) keeps a token
    // update inside this one item: the rest of the screen is not recomposed.
    val text = if (live.messageId == message.id) live.text else message.text
    val parts = remember(text, isUser) {
        if (isUser) ThinkingParts(null, text) else splitThinking(text)
    }
    if (isUser) {
        UserBubble(message)
    } else {
        AssistantMessage(message, parts, live)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(message: ChatMessage) {
    val palette = ObsidianTheme.palette
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 344.dp)
                    .clip(ChatBubbleShape)
                    .background(palette.nested)
                    .border(1.dp, palette.hairline, ChatBubbleShape)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                if (message.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (attachment in message.attachments) {
                            MessageAttachment(attachment)
                        }
                    }
                    if (message.text.isNotEmpty()) Spacer(Modifier.height(8.dp))
                }
                if (message.text.isNotEmpty()) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary,
                    )
                }
            }
            MessageActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                answer = message.text,
                thinking = null,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantMessage(message: ChatMessage, parts: ThinkingParts, live: StreamingAnswer) {
    val palette = ObsidianTheme.palette
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val thinkingLive = message.streaming && parts.answer.isEmpty()
    val copyText = parts.answer.ifBlank { message.text }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.isError) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.card)
                    .border(1.dp, palette.hairline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                RobotAvatar(
                    size = 21.dp,
                    thinking = thinkingLive,
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    )
                    .animateContentSize(
                        // While tokens stream in, apply size changes instantly:
                        // a retargeted spring would animate layout every frame.
                        // The spring stays for the collapse after completion.
                        animationSpec = when {
                            message.streaming -> snap<IntSize>()
                            reduced -> tween<IntSize>(durationMillis = MotionTokens.Durations.FADE_REDUCED)
                            else -> spring<IntSize>(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                    )
                    .padding(top = 1.dp, bottom = 2.dp),
            ) {
                if (!message.isError) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow(
                            text = "LlamaPort",
                            color = palette.textMuted,
                            tick = false,
                        )
                        if (message.streaming) {
                            Spacer(Modifier.width(7.dp))
                            StateDot(color = palette.silver, size = 4.dp, pulse = true)
                        }
                        Spacer(Modifier.weight(1f))
                        if (message.streaming) {
                            Text(
                                text = "ГЕНЕРАЦИЯ",
                                style = MonoSmall,
                                color = palette.silverDim,
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }

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
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(palette.error.copy(alpha = 0.08f))
                            .border(1.dp, palette.error.copy(alpha = 0.30f), MaterialTheme.shapes.small)
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateDot(color = palette.error, size = 5.dp)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "ОШИБКА",
                                style = EyebrowStyle,
                                color = palette.error,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary,
                        )
                    }
                } else {
                    parts.thinking?.let { thinking ->
                        ThinkingBlock(
                            thinking = thinking,
                            streaming = thinkingLive,
                        )
                        if (parts.answer.isNotEmpty()) Spacer(Modifier.height(9.dp))
                    }
                    if (message.streaming && parts.answer.isNotEmpty()) {
                        SilverShimmerLine(
                            modifier = Modifier.fillMaxWidth().height(1.dp),
                            color = palette.silver.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(7.dp))
                    }
                    if (parts.answer.isNotEmpty()) {
                        MarkdownText(
                            text = parts.answer,
                            streaming = message.streaming,
                            contentColor = palette.textPrimary,
                        )
                    } else if (message.streaming && parts.thinking == null) {
                        StreamingCursor()
                    }
                }

                AnswerMetrics(message, live)
            }
            MessageActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                answer = copyText,
                thinking = parts.thinking,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
            )
        }
    }
}

/** Live speed while the answer streams; a token-count button once it is done. */
@Composable
private fun AnswerMetrics(message: ChatMessage, live: StreamingAnswer) {
    val palette = ObsidianTheme.palette
    if (message.streaming) {
        // The speed lives in the live answer, so it does not wake up the chat
        // state; the final metrics are committed to the message when done.
        val speed = if (live.messageId == message.id) live.speed else 0f
        if (speed > 0f) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(color = palette.silver, size = 3.dp, pulse = true)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = String.format(Locale.ROOT, "%.1f t/s", speed),
                    style = MonoSmall,
                    color = palette.textMuted,
                )
            }
        }
    } else {
        val metrics = message.metrics ?: return
        if (metrics.generatedTokens > 0) {
            Spacer(Modifier.height(7.dp))
            MessageMetricsButton(metrics)
        }
    }
}

@Composable
private fun MessageMetricsButton(metrics: MessageMetrics) {
    val palette = ObsidianTheme.palette
    var detailsOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(PillShape)
            .background(palette.nested.copy(alpha = 0.45f))
            .border(1.dp, palette.hairline, PillShape)
            .pressable(hapticsEnabled = false, onClick = { detailsOpen = true })
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(4.dp).background(palette.silverDim, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = tokenCount(metrics.generatedTokens),
            style = MonoSmall,
            color = palette.textSecondary,
        )
    }
    if (detailsOpen) {
        MessageMetricsDialog(metrics = metrics, onDismiss = { detailsOpen = false })
    }
}

@Composable
private fun MessageMetricsDialog(metrics: MessageMetrics, onDismiss: () -> Unit) {
    val palette = ObsidianTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = palette.card,
        title = {
            Column {
                Eyebrow(text = "инференс", color = palette.textMuted)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Статистика ответа",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MetricsRow("Обработка промпта", formatSpeed(metrics.promptTokensPerSecond))
                MetricsRow("Токенов в промпте", metrics.promptTokens.toString())
                Spacer(Modifier.height(4.dp))
                Hairline(Modifier.padding(vertical = 4.dp))
                MetricsRow("Генерация", formatSpeed(metrics.generationTokensPerSecond))
                MetricsRow("Сгенерировано", tokenCount(metrics.generatedTokens))
                MetricsRow("Время", formatElapsed(metrics.durationMs))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = palette.silver)
            }
        },
    )
}

@Composable
private fun MetricsRow(label: String, value: String) {
    val palette = ObsidianTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MonoNumeric.copy(color = palette.textPrimary),
        )
    }
}

private fun formatSpeed(tokensPerSecond: Float): String =
    if (tokensPerSecond > 0f) {
        String.format(Locale.ROOT, "%.1f tok/s", tokensPerSecond)
    } else {
        "—"
    }

private fun formatElapsed(milliseconds: Long): String {
    val seconds = milliseconds / 1000.0
    return if (seconds < 60.0) {
        String.format(Locale.ROOT, "%.1f с", seconds)
    } else {
        String.format(Locale.ROOT, "%d:%04.1f", (seconds / 60).toInt(), seconds % 60.0)
    }
}

private fun tokenCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "токенов"
        count % 10 == 1 -> "токен"
        count % 10 in 2..4 -> "токена"
        else -> "токенов"
    }
    return "$count $word"
}

@Composable
private fun MessageActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    answer: String,
    thinking: String?,
    onCopy: (String) -> Unit,
) {
    val palette = ObsidianTheme.palette
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        DropdownMenuItem(
            text = { Text("Копировать") },
            onClick = {
                onCopy(answer)
                onDismiss()
            },
        )
        if (!thinking.isNullOrBlank()) {
            DropdownMenuItem(
                text = { Text("Копировать размышления") },
                onClick = {
                    onCopy(thinking)
                    onDismiss()
                },
            )
        }
    }
}

/** Blinking cursor while the first tokens arrive without any text yet. */
@Composable
private fun StreamingCursor() {
    val reduced = LocalReducedMotion.current
    val alpha = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        )
    }
    Text(
        "▍",
        fontSize = 15.sp,
        color = ObsidianTheme.palette.silver,
        // Read the animation in the draw phase: no recomposition per frame.
        modifier = Modifier.graphicsLayer { this.alpha = alpha?.value ?: 1f },
    )
}

/**
 * Tool output can be up to 256 KB (`read_file`). One [Text] of that size took
 * ~200 ms to lay out and record on this device, so the block renders the head
 * of the text in a bounded, scrollable box instead of an item taller than the
 * screen. The message keeps the full text: only the view is limited.
 */
private const val TOOL_TEXT_DISPLAY_LIMIT = 8_000

private fun String.forToolDisplay(limit: Int = TOOL_TEXT_DISPLAY_LIMIT): String =
    if (length <= limit) {
        this
    } else {
        take(limit) + "\n… показано $limit из $length символов"
    }

@Composable
private fun ToolText(text: String, maxHeight: Dp) {
    val palette = ObsidianTheme.palette
    val shown = remember(text) { text.forToolDisplay() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(palette.codeBackground)
            .border(1.dp, palette.codeBorder, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Text(
            shown,
            style = MonoBody.copy(color = palette.codeText.copy(alpha = 0.84f)),
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallBlock(message: ChatMessage) {
    val palette = ObsidianTheme.palette
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) {
            snap()
        } else {
            tween(durationMillis = MotionTokens.Durations.SHORT)
        },
        label = "toolArrow",
    )
    val accent = if (message.isError) palette.error else palette.silverDim

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.card)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.small),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = { expanded = !expanded },
                )
                .padding(start = 8.dp, end = 11.dp, top = 7.dp, bottom = expanded.let { if (it) 7.dp else 3.dp }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.nested.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                if (message.isError) "Ошибка инструмента" else "Инструмент",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (message.isError) palette.error else palette.textPrimary,
            )
            Spacer(Modifier.width(7.dp))
            if (!message.toolName.isNullOrBlank()) {
                ObsidianTag(
                    text = message.toolName,
                    color = if (message.isError) palette.error else palette.textMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (message.isError) "СБОЙ" else "TOOL",
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
        if (!expanded && !message.toolArgs.isNullOrBlank()) {
            Text(
                message.toolArgs.forToolDisplay(400),
                style = MonoSmall,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 39.dp, end = 11.dp, bottom = 7.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = disclosureEnter(),
            exit = disclosureExit(),
        ) {
            Column(
                Modifier.padding(start = 39.dp, end = 11.dp, bottom = 10.dp),
            ) {
                if (!message.toolArgs.isNullOrBlank()) {
                    Text("АРГУМЕНТЫ", style = EyebrowStyle, color = palette.textMuted)
                    Spacer(Modifier.height(4.dp))
                    ToolText(message.toolArgs, maxHeight = 140.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Text("РЕЗУЛЬТАТ", style = EyebrowStyle, color = palette.textMuted)
                Spacer(Modifier.height(4.dp))
                ToolText(
                    message.toolResult?.takeIf { it.isNotBlank() } ?: message.text,
                    maxHeight = 300.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    val palette = ObsidianTheme.palette
    var expanded by remember { mutableStateOf(streaming) }
    // Collapse automatically when the answer starts, unless the user opened it
    // by hand while the model was still thinking.
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (!streaming && !userToggled) expanded = false
    }
    val reduced = LocalReducedMotion.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) {
            snap()
        } else {
            tween(durationMillis = MotionTokens.Durations.SHORT)
        },
        label = "thinkingArrow",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.card.copy(alpha = 0.62f))
            .border(
                1.dp,
                if (streaming) palette.silver.copy(alpha = 0.24f) else palette.hairline,
                MaterialTheme.shapes.small,
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = {
                        expanded = !expanded
                        userToggled = true
                    },
                )
                .padding(start = 8.dp, end = 11.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(palette.nested.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (streaming) palette.silver else palette.textSecondary,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                "Размышления",
                style = MaterialTheme.typography.labelLarge,
                color = palette.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (streaming) {
                Spacer(Modifier.width(7.dp))
                StateDot(color = palette.silver, size = 4.dp, pulse = true)
                Spacer(Modifier.width(5.dp))
                Text(
                    "ИДЁТ",
                    style = MonoSmall,
                    color = palette.silverDim,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${thinking.length} симв.",
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
        if (streaming) {
            SilverShimmerLine(
                modifier = Modifier.fillMaxWidth().height(1.dp),
                color = palette.silver.copy(alpha = 0.7f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = disclosureEnter(),
            exit = disclosureExit(),
        ) {
            Column(Modifier.padding(start = 12.dp, top = 9.dp, end = 12.dp, bottom = 11.dp)) {
                MarkdownText(
                    text = thinking,
                    streaming = streaming,
                    contentColor = palette.textSecondary,
                    bodyStyle = MaterialTheme.typography.bodySmall,
                )
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
    val palette = ObsidianTheme.palette
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
                .clip(CodeShape)
                .border(1.dp, palette.hairline, CodeShape),
        )
    } else {
        Row(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.card)
                .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Image,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AudioAttachment(attachment: Attachment) {
    val palette = ObsidianTheme.palette
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.card)
            .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ObsidianIconButton(
            icon = if (playing) AppIcons.Pause else AppIcons.Play,
            contentDescription = if (playing) "Пауза" else "Прослушать",
            onClick = {
                val mediaPlayer = player ?: return@ObsidianIconButton
                if (playing) {
                    runCatching { mediaPlayer.pause() }
                    playing = false
                } else {
                    runCatching { mediaPlayer.start() }
                    playing = true
                }
            },
            enabled = player != null,
            tint = palette.silver,
            size = 38.dp,
            iconSize = 19.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (duration.isBlank()) "аудио" else "аудио · $duration",
                style = MonoSmall,
                color = palette.textMuted,
            )
        }
    }
}

internal fun decodeSampledBitmap(path: String): ImageBitmap? {
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

internal fun formatDuration(milliseconds: Int): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}