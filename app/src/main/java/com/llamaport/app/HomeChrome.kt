package com.llamaport.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.components.Eyebrow
import com.llamaport.app.ui.components.Hairline
import com.llamaport.app.ui.components.ObsidianButton
import com.llamaport.app.ui.components.ObsidianIconButton
import com.llamaport.app.ui.components.SilverPulseOverlay
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.robot.AsciiRobot
import com.llamaport.app.ui.robot.RobotStage
import com.llamaport.app.ui.robot.RobotDemoDialog
import com.llamaport.app.ui.robot.RobotState
import com.llamaport.app.ui.theme.EyebrowStyle
import com.llamaport.app.ui.theme.MonoSmall
import com.llamaport.app.ui.theme.ObsidianTheme
import com.llamaport.app.ui.theme.PillShape
import kotlin.math.PI
import kotlin.math.sin

/** Robot mood derived from the app state. */
internal fun robotStateFor(state: UiState): RobotState = when {
    state.serviceDead -> RobotState.Error
    state.loadingModel -> RobotState.Loading
    state.generating -> RobotState.Generating
    state.modelLoaded -> RobotState.Idle
    else -> RobotState.Sleeping
}

/**
 * Model chip for the top bar. It reads as a small technical module: state
 * label in mono caps, model name, state halo and a chevron opening the model
 * sheet. Tap still opens the model list — the only place it lives.
 */
@Composable
internal fun ModelChip(
    state: UiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ObsidianTheme.palette
    val cloud = state.activeCloud != null
    val statusColor = when {
        state.serviceDead -> palette.error
        state.loadingModel || state.generating -> palette.silver
        cloud -> palette.silver
        state.modelLoaded -> palette.success
        else -> palette.textMuted
    }
    val statusLabel = when {
        state.serviceDead -> "остановлена"
        state.loadingModel -> "загрузка"
        state.generating -> "генерация"
        cloud -> "облако"
        state.modelLoaded -> "готова"
        else -> "не выбрана"
    }
    val pulse = state.loadingModel || state.serviceDead

    Row(
        modifier = modifier
            .height(48.dp)
            .clip(PillShape)
            .background(
                Brush.verticalGradient(
                    listOf(palette.card, palette.surface),
                ),
            )
            .border(
                1.dp,
                if (state.modelLoaded) palette.silver.copy(alpha = 0.26f) else palette.hairline,
                PillShape,
            )
            .pressable(hapticsEnabled = false, onClick = onClick)
            .padding(start = 7.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            val haloColor by animateColorAsState(
                targetValue = statusColor,
                animationSpec = tween(MotionTokens.Durations.ENTER),
                label = "modelHalo",
            )
            Box(
                Modifier
                    .size(28.dp)
                    .border(1.dp, haloColor.copy(alpha = 0.16f), CircleShape),
            )
            StateDot(color = statusColor, size = 6.dp, pulse = pulse)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = statusLabel.uppercase(),
                style = EyebrowStyle,
                color = statusColor.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.modelName ?: "Выбрать модель",
                style = MaterialTheme.typography.labelLarge,
                color = palette.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = AppIcons.Chevron,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Custom top bar: menu, model module, generation settings and new chat. It
 * owns the status bar inset and the hairline separating chrome from content.
 */
@Composable
internal fun ObsidianTopBar(
    state: UiState,
    onMenu: () -> Unit,
    onModelClick: () -> Unit,
    onGenerationSettings: () -> Unit,
    onNewChat: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.background.copy(alpha = 0.99f),
                        palette.background.copy(alpha = 0.92f),
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObsidianIconButton(
                icon = AppIcons.Menu,
                contentDescription = "Меню",
                onClick = onMenu,
                container = palette.card,
                borderColor = palette.hairline,
                tint = palette.textPrimary,
                size = 42.dp,
                iconSize = 19.dp,
            )
            Spacer(Modifier.width(10.dp))
            ModelChip(
                state = state,
                onClick = onModelClick,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            ObsidianIconButton(
                icon = AppIcons.Tune,
                contentDescription = "Параметры генерации",
                onClick = onGenerationSettings,
                container = palette.card,
                borderColor = palette.hairline,
                tint = palette.textPrimary,
                size = 42.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(8.dp))
            ObsidianIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "Новый чат",
                onClick = onNewChat,
                enabled = !state.generating,
                container = palette.card,
                borderColor = palette.hairline,
                tint = if (state.generating) palette.textMuted else palette.textPrimary,
                size = 42.dp,
                iconSize = 20.dp,
            )
        }
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
    }
}

/**
 * Side drawer: layered surface, technical header and the chat history. The
 * sheet slides with the Material drawer; inside it, header, history and footer
 * follow with a small stagger, and the robot ticker stops when the drawer is
 * not active.
 */
@Composable
internal fun AppDrawer(
    state: UiState,
    drawerActive: Boolean,
    snackbarHostState: SnackbarHostState,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = ObsidianTheme.palette
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Empty chats are scratch space: only the active one has a row.
    val visibleSessions = remember(state.sessions, state.activeSessionId) {
        groupSessions(
            state.sessions.filter { it.messageCount > 0 || it.id == state.activeSessionId },
        )
    }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(drawerActive) {
        if (drawerActive) {
            reveal.snapTo(0f)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.Durations.COMPLEX,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }
    // The history list. Its scroll state also gates the header robot: while the
    // user drags or flings the list, every robot tick would invalidate a frame
    // mid-scroll for an animation nobody is looking at, so the robot freezes
    // for the duration of the gesture and resumes when the list settles.
    val sessionsListState = rememberLazyListState()
    val scrolling by remember { derivedStateOf { sessionsListState.isScrollInProgress } }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.82f),
        drawerContainerColor = palette.background,
        drawerContentColor = palette.textPrimary,
        // A rounded full-height Surface forces the changing LazyColumn into an
        // 886x2274 offscreen texture; Perfetto showed that texture uploading on
        // every scroll frame. Keep the sheet rectangular so rows draw directly.
        drawerShape = RectangleShape,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(palette.surface, palette.background),
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                DrawerPart(reveal, 0) {
                    Column {
                        Row(
                            Modifier.padding(start = 18.dp, end = 14.dp, top = 20.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(palette.card)
                                    .border(1.dp, palette.hairline, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                AsciiRobot(
                                    state = robotStateFor(state),
                                    size = 32.dp,
                                    // The drawer content stays composed while the
                                    // drawer is closed; freeze the robot so it never
                                    // animates offscreen, and freeze it during a list
                                    // scroll so no tick lands in a scroll frame.
                                    active = drawerActive && !scrolling,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "LlamaPort",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = palette.textPrimary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StateDot(
                                        color = if (state.modelLoaded) palette.success else palette.textMuted,
                                        size = 4.dp,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        state.modelName ?: "модель не загружена",
                                        style = MonoSmall,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            ObsidianIconButton(
                                icon = Icons.Filled.Add,
                                contentDescription = "Новый чат",
                                onClick = onNewChat,
                                enabled = !state.generating,
                                container = palette.card,
                                borderColor = palette.hairline,
                                tint = palette.silver,
                                size = 38.dp,
                                iconSize = 18.dp,
                            )
                        }
                        Hairline(color = palette.hairline)
                    }
                }

                DrawerPart(reveal, 1) {
                    Row(
                        Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow(text = "история чатов", color = palette.textMuted)
                        Spacer(Modifier.weight(1f))
                        if (state.generating) {
                            StateDot(color = palette.silver, size = 4.dp, pulse = true)
                            Spacer(Modifier.width(6.dp))
                            Text("ИДЁТ ГЕНЕРАЦИЯ", style = MonoSmall, color = palette.silverDim)
                        }
                    }
                }

                DrawerPart(reveal, 2, Modifier.weight(1f)) {
                    if (visibleSessions.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                AppIcons.Chat,
                                contentDescription = null,
                                tint = palette.textMuted,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Пока нет чатов",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                            Text(
                                "Новый чат появится здесь",
                                style = MonoSmall,
                                color = palette.textMuted,
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = sessionsListState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            visibleSessions.forEach { (group, sessions) ->
                                item(key = "group-${group.name}") {
                                    Eyebrow(
                                        text = group.label,
                                        color = palette.textMuted,
                                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp),
                                    )
                                }
                                items(items = sessions, key = { it.id }) { session ->
                                    SessionRow(
                                        session = session,
                                        active = session.id == state.activeSessionId,
                                        enabled = !state.generating,
                                        onSelect = { onSelectSession(session.id) },
                                        onRename = {
                                            renaming = session
                                            renameText = session.title
                                        },
                                        onDelete = { onDeleteSession(session.id) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }

                DrawerPart(reveal, 3) {
                    Column {
                        Hairline(color = palette.hairline)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(palette.card)
                                .border(1.dp, palette.hairline, MaterialTheme.shapes.small)
                                .pressable(onClick = onOpenSettings)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(palette.nested),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Настройки модели",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                AppIcons.Chevron,
                                contentDescription = null,
                                tint = palette.textMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = -90f },
                            )
                        }
                        if (state.backendInfo.isNotBlank()) {
                            Text(
                                state.backendInfo,
                                style = MonoSmall,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            // Same SnackbarHostState as the scaffold: while the drawer is open
            // this host is the visible one, so "Отменить" is reachable.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }
    }

    renaming?.let { session ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = palette.card,
            title = {
                Column {
                    Eyebrow(text = "история", color = palette.textMuted)
                    Spacer(Modifier.height(5.dp))
                    Text("Переименовать чат", style = MaterialTheme.typography.titleLarge)
                }
            },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Название") },
                    shape = MaterialTheme.shapes.small,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, renameText)
                        renaming = null
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text("Сохранить", color = palette.silver)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("Отмена", color = palette.textSecondary)
                }
            },
        )
    }
}

/**
 * Staggered drawer content reveal. Reads the shared animation only in the draw
 * phase, so opening the drawer does not recompose the whole history list.
 */
@Composable
private fun DrawerPart(
    reveal: Animatable<Float, *>,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val revealFinished by remember(reveal) {
        derivedStateOf { reveal.value >= 0.999f }
    }
    val revealModifier = if (revealFinished) {
        // Drop the layer once the entrance has settled. Keeping a graphics
        // layer around the LazyColumn makes each scroll frame re-upload it.
        modifier
    } else {
        modifier.graphicsLayer {
            val start = index * 0.08f
            val progress = ((reveal.value - start) / 0.76f).coerceIn(0f, 1f)
            alpha = progress
            translationX = (1f - progress) * 20.dp.toPx()
        }
    }
    Box(revealModifier) {
        content()
    }
}

private enum class SessionGroup(val label: String) {
    Today("Сегодня"),
    Yesterday("Вчера"),
    Earlier("Ранее"),
}

/** Splits the chat list into Сегодня / Вчера / Ранее, newest first in each. */
private fun groupSessions(
    sessions: List<SessionSummary>,
): List<Pair<SessionGroup, List<SessionSummary>>> {
    val startOfToday = Calendar.getInstance().startOfDay()
    val startOfYesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }.startOfDay()
    val buckets = LinkedHashMap<SessionGroup, MutableList<SessionSummary>>()
    for (session in sessions.sortedByDescending { it.updatedAt }) {
        val group = when {
            session.updatedAt >= startOfToday -> SessionGroup.Today
            session.updatedAt >= startOfYesterday -> SessionGroup.Yesterday
            else -> SessionGroup.Earlier
        }
        buckets.getOrPut(group) { mutableListOf() } += session
    }
    return buckets.map { (group, list) -> group to list }
}

private fun Calendar.startOfDay(): Long {
    val copy = clone() as Calendar
    copy.set(Calendar.HOUR_OF_DAY, 0)
    copy.set(Calendar.MINUTE, 0)
    copy.set(Calendar.SECOND, 0)
    copy.set(Calendar.MILLISECOND, 0)
    return copy.timeInMillis
}

private data class QuickAction(
    val label: String,
    val icon: ImageVector,
)

private val QUICK_ACTIONS = listOf(
    QuickAction("Составь план на день", AppIcons.Plan),
    QuickAction("Объясни простыми словами", AppIcons.Explain),
    QuickAction("Придумай идею", AppIcons.Idea),
    QuickAction("Напиши короткое письмо", AppIcons.Letter),
)

/**
 * Empty state: the Obsidian Core, a precise wordmark, and either the model
 * picker or four compact prompt cards. A long-press on the Core opens the
 * hidden robot state demo (debug), exactly as before.
 */
@Composable
internal fun HomeEmptyState(
    state: UiState,
    shakeTick: Int,
    onChooseModel: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current
    var showRobotDemo by remember { mutableStateOf(false) }
    if (showRobotDemo) {
        RobotDemoDialog { showRobotDemo = false }
    }

    // "+" on an empty chat shakes the screen instead of creating a chat.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0 && !reduced) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = MotionTokens.Durations.TRANSITION),
            )
        }
    }

    // A short silver bloom after a model finishes loading or generation ends.
    var completionTick by remember { mutableIntStateOf(0) }
    var wasLoading by remember { mutableStateOf(state.loadingModel) }
    var wasGenerating by remember { mutableStateOf(state.generating) }
    LaunchedEffect(state.loadingModel) {
        if (wasLoading && !state.loadingModel) completionTick++
        wasLoading = state.loadingModel
    }
    LaunchedEffect(state.generating) {
        if (wasGenerating && !state.generating) completionTick++
        wasGenerating = state.generating
    }

    val loadingProgress = when {
        !state.loadingModel -> 0f
        // With reduced motion the eyes sit at a readable half-filled state
        // instead of running an endless decorative fill.
        reduced -> 0.6f
        else -> {
            val infinite = rememberInfiniteTransition(label = "loadFill")
            val t by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "loadFillT",
            )
            t
        }
    }

    val statusColor = when {
        state.serviceDead -> palette.error
        state.loadingModel || state.generating -> palette.silver
        state.modelLoaded -> palette.success
        else -> palette.textMuted
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val compact = viewportHeight < 640.dp
        val heroSize = if (compact) 210.dp else 272.dp
        // The composition centers when it fits and scrolls on short screens,
        // so the picker and prompt cards are never clipped.
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(
                        horizontal = 24.dp,
                        vertical = if (compact) 10.dp else 20.dp,
                    )
                    .graphicsLayer {
                        translationX = if (shake.value in 0.001f..0.999f) {
                            sin(shake.value * 4f * PI).toFloat() * 10.dp.toPx() * (1f - shake.value)
                        } else {
                            0f
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Reveal(index = 0) {
                    Box {
                        // The pixel robot is the hero: it hangs on a static
                        // Obsidian stage and performs on its own — looks,
                        // blinks, emotions, nods and glitches — plus a
                        // different reaction for every tap.
                        RobotStage(size = heroSize) {
                            AsciiRobot(
                                state = robotStateFor(state),
                                size = heroSize * 0.62f,
                                progress = loadingProgress,
                                active = true,
                                lively = true,
                                onLongPress = { showRobotDemo = true },
                            )
                        }
                        // One short local silver bloom when a model finishes
                        // loading or an answer completes.
                        SilverPulseOverlay(
                            trigger = completionTick,
                            color = palette.silver,
                            modifier = Modifier.matchParentSize(),
                            maxScale = 1.15f,
                        )
                    }
                }
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                Reveal(index = 1) {
                    Text(
                        "LlamaPort",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    palette.textPrimary,
                                    palette.silver,
                                    palette.silverDim,
                                ),
                            ),
                        ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Reveal(index = 2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateDot(
                            color = statusColor,
                            size = 5.dp,
                            pulse = state.loadingModel,
                        )
                        Spacer(Modifier.width(7.dp))
                        Eyebrow(
                            text = when {
                                state.loadingModel -> "загрузка модели"
                                state.serviceDead -> "инференс остановлен"
                                state.activeCloud != null -> "облачная модель · готов"
                                state.modelLoaded -> "локальный интеллект · готов"
                                else -> "локальный интеллект · llama.cpp"
                            },
                            color = palette.textMuted,
                            tick = false,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Reveal(index = 3) {
                    Text(
                        text = when {
                            state.loadingModel -> "Загружаю модель…"
                            state.serviceDead -> "Процесс инференса остановлен"
                            state.activeCloud != null ->
                                "Облачная модель готова. Сообщения уходят провайдеру " +
                                    "через интернет."
                            !state.modelLoaded ->
                                "Модель работает прямо на телефоне, без интернета. " +
                                    "Выберите GGUF-файл, чтобы начать."
                            else -> "Модель готова. С чего начнём?"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }

                if (!state.modelLoaded) {
                    Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
                    Reveal(index = 4) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ObsidianButton(
                                text = "Выбрать модель",
                                onClick = onChooseModel,
                                leadingIcon = AppIcons.Document,
                                modifier = Modifier.widthIn(max = 300.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "GGUF · ЛОКАЛЬНО · БЕЗ СЕТИ",
                                style = EyebrowStyle,
                                color = palette.textMuted,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                    Reveal(index = 4) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            QUICK_ACTIONS.chunked(2).forEach { rowActions ->
                                Row(
                                    Modifier.fillMaxWidth().widthIn(max = 430.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (action in rowActions) {
                                        QuickActionCard(
                                            action = action,
                                            onClick = { onSuggestion(action.label) },
                                            modifier = Modifier.weight(1f),
                                            compact = compact,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean,
) {
    val palette = ObsidianTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border by animateColorAsState(
        targetValue = if (pressed) palette.silver.copy(alpha = 0.5f) else palette.hairline,
        animationSpec = tween(MotionTokens.Durations.MICRO),
        label = "quickCardBorder",
    )
    val background by animateColorAsState(
        targetValue = if (pressed) palette.nested else palette.card,
        animationSpec = tween(MotionTokens.Durations.MICRO),
        label = "quickCardBackground",
    )
    Column(
        modifier = modifier
            .height(if (compact) 82.dp else 88.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .border(1.dp, border, MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = if (pressed) palette.silver else palette.silverDim,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
