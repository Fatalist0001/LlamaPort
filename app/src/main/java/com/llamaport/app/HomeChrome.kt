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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.motion.Reveal
import com.llamaport.app.ui.motion.pressable
import com.llamaport.app.ui.robot.AsciiRobot
import com.llamaport.app.ui.robot.RobotDemoDialog
import com.llamaport.app.ui.robot.RobotState
import com.llamaport.app.ui.theme.ModelChipShape
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
 * Model chip for the top bar: name plus a state dot.
 * Tap opens the model sheet — the only place the model list lives.
 */
@Composable
internal fun ModelChip(
    state: UiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dotTarget = when {
        state.serviceDead -> scheme.error
        state.loadingModel || state.generating -> scheme.tertiary
        state.modelLoaded -> scheme.primary
        else -> scheme.outline
    }
    val dotColor by animateColorAsState(
        targetValue = dotTarget,
        animationSpec = tween(durationMillis = MotionTokens.Durations.ENTER),
        label = "modelDotColor",
    )
    val reduced = LocalReducedMotion.current

    Surface(
        shape = ModelChipShape,
        color = scheme.surfaceVariant,
        modifier = modifier
            .widthIn(max = 260.dp)
            .pressable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The infinite transition only exists while the dot actually
            // pulses; at rest (and with reduced motion) nothing subscribes to
            // the frame clock.
            if (!reduced && (state.loadingModel || state.generating || state.serviceDead)) {
                val pulseT by rememberInfiniteTransition(label = "modelDotPulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "modelDotPulseT",
                )
                Box(
                    Modifier
                        .size(9.dp)
                        .graphicsLayer { alpha = pulseT }
                        .background(dotColor, CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(dotColor, CircleShape),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.modelName ?: "Выбрать модель",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Side drawer: chat history plus settings. Replaces the old "chats" and
 * "settings" sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Empty chats are scratch space: only the active one has a row.
    val visibleSessions = remember(state.sessions, state.activeSessionId) {
        groupSessions(
            state.sessions.filter { it.messageCount > 0 || it.id == state.activeSessionId },
        )
    }

    ModalDrawerSheet {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsciiRobot(
                        state = robotStateFor(state),
                        size = 40.dp,
                        // The drawer content stays composed while the drawer is
                        // closed; freeze the robot so it never animates offscreen.
                        active = drawerActive,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "LlamaPort",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.modelName ?: "модель не загружена",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Чаты",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewChat, enabled = !state.generating) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Новый")
                    }
                }

                if (visibleSessions.isEmpty()) {
                    Text(
                        "Пока нет чатов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        visibleSessions.forEach { (group, sessions) ->
                            item(key = "group-${group.name}") {
                                Text(
                                    group.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
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

                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Настройки") },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    selected = false,
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (state.backendInfo.isNotBlank()) {
                    Text(
                        state.backendInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
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
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Название") },
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
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("Отмена")
                }
            },
        )
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

/**
 * Empty state: the robot in the middle, "Выбрать модель" while no model is
 * loaded, prompt suggestion chips once it is. A long-press opens the robot
 * state demo (debug).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeEmptyState(
    state: UiState,
    shakeTick: Int,
    onChooseModel: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
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

    val suggestions = listOf(
        "Составь план на день",
        "Объясни простыми словами",
        "Придумай идею",
        "Напиши короткое письмо",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
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
            AsciiRobot(
                state = robotStateFor(state),
                size = 168.dp,
                progress = loadingProgress,
                onTap = {},
                onLongPress = { showRobotDemo = true },
            )
        }
        Spacer(Modifier.height(16.dp))
        Reveal(index = 1) {
            Text(
                "LlamaPort",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Reveal(index = 2) {
            Text(
                text = when {
                    state.loadingModel -> "Загружаю модель…"
                    state.serviceDead -> "Процесс инференса остановлен"
                    !state.modelLoaded ->
                        "Локальный чат на llama.cpp. Модель работает прямо на телефоне, без интернета."
                    else -> "Модель готова. С чего начнём?"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        if (!state.modelLoaded) {
            Spacer(Modifier.height(24.dp))
            Reveal(index = 3) {
                Button(
                    onClick = onChooseModel,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text("Выбрать модель", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            Spacer(Modifier.height(24.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    Reveal(index = 3 + index) {
                        SuggestionChip(
                            onClick = { onSuggestion(suggestion) },
                            label = { Text(suggestion) },
                        )
                    }
                }
            }
        }
    }
}