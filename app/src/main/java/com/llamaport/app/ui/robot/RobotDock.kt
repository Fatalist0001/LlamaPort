package com.llamaport.app.ui.robot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llamaport.app.GenerationPhase
import com.llamaport.app.StreamingAnswer
import com.llamaport.app.UiState
import com.llamaport.app.robotStateFor
import com.llamaport.app.splitThinking
import com.llamaport.app.ui.components.StateDot
import com.llamaport.app.ui.motion.AnimatedNumber
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.theme.MonoNumeric
import com.llamaport.app.ui.theme.ObsidianTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The chat companion: the pixel robot docked in the bottom chrome, right
 * above the input bar.
 *
 * The home hero disappears as soon as the first message is sent — and the
 * richest robot states (darting eyes, the mouth beating with each token) are
 * exactly the chat states. The dock keeps one live robot on screen for the
 * whole conversation: it sits where the eye already rests while the answer
 * streams in, it never scrolls away with the message list, and it stays
 * visible above the keyboard. Two size tiers carry the mood: the robot stands
 * tall (96 dp, 76 dp compact) while the model is busy — loading, thinking,
 * generating or in error — and settles down to 64 dp (52 dp) when it is only
 * waiting.
 *
 * Status text is deliberately minimal: only the live tok/s while tokens are
 * actually streaming; everything else the robot tells with its face. The
 * speed chip used to live next to the send button; here it reads as the robot
 * speaking, and the input row stops resizing mid-generation.
 */
@Composable
internal fun RobotDock(
    state: UiState,
    live: StreamingAnswer,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current

    // --- dock mood ----------------------------------------------------------
    // [robotStateFor] maps the app state (including the prompt phase); one
    // local override keeps the robot thinking while generation streams
    // nothing visible yet — the same predicate the message bubble uses for
    // its "Думает…" line. text is read inside the flow only, so a token does
    // not recompose the dock.
    var answerEmpty by remember { mutableStateOf(true) }
    LaunchedEffect(live) {
        snapshotFlow { splitThinking(live.text).answer.isEmpty() }
            .distinctUntilChanged()
            .collect { answerEmpty = it }
    }
    val base = robotStateFor(state)
    val robotState =
        if (base == RobotState.Generating && answerEmpty) RobotState.Thinking else base

    // --- one mouth beat per streamed token ----------------------------------
    // [StreamingAnswer.revision] bumps on begin() and on every appended
    // chunk; the mouth opens and closes in two discrete steps — the pixel
    // robot beats, it does not fade. Emissions are conflated, so faster token
    // rates simply shorten the pauses instead of queueing work. Reduced
    // motion keeps the mouth at a static half-open width while generating.
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(live, reduced) {
        if (reduced) return@LaunchedEffect
        // drop(1): the first emission is the stale revision of a previous
        // generation; only fresh beats should open the mouth.
        snapshotFlow { live.revision }.drop(1).collect {
            pulse = 1f
            delay(90)
            pulse = 0.45f
            delay(90)
            pulse = 0f
        }
    }
    val mouthPulse = when {
        reduced && robotState == RobotState.Generating -> 0.35f
        else -> pulse
    }

    val loadFill = rememberLoadFill(state.loadingModel)

    // --- the "stands up to speak" beat --------------------------------------
    // Tall while the model is busy, settled while it waits. The row height
    // follows the animated size, so the bottom chrome changes height exactly
    // twice per generation cycle — the answer list self-corrects on the next
    // token via its auto-follow.
    val speaking = robotState == RobotState.Loading ||
        robotState == RobotState.Thinking ||
        robotState == RobotState.Generating ||
        robotState == RobotState.Error
    // Rough viewport proxy: the configuration height minus the system bars.
    // The home hero measures its viewport exactly; the dock only needs the
    // size tier.
    val compact = LocalConfiguration.current.screenHeightDp < 700
    val robotSize by animateDpAsState(
        targetValue = if (speaking) {
            if (compact) 76.dp else 96.dp
        } else {
            if (compact) 52.dp else 64.dp
        },
        animationSpec = if (reduced) snap() else tween(MotionTokens.Durations.SHORT),
        label = "robotDockSize",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // RowScope.AnimatedVisibility with a weight keeps the slot even while
        // hidden, so the robot stays pinned to the trailing edge and the
        // tok/s line appears in the free space to its left.
        AnimatedVisibility(
            visible = robotState == RobotState.Generating,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, bottom = 7.dp),
            enter = fadeIn(tween(MotionTokens.Durations.MICRO)),
            exit = fadeOut(tween(MotionTokens.Durations.FADE_REDUCED)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(color = palette.silver, size = 4.dp)
                Spacer(Modifier.width(6.dp))
                AnimatedNumber(
                    value = live.speed,
                    style = MonoNumeric.copy(fontSize = 11.sp, color = palette.silver),
                    format = { String.format(Locale.ROOT, "%.1f t/s", it) },
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            // The dock robot is always on screen (unlike the drawer one, the
            // bottom chrome never scrolls away), so it stays active; the
            // ticker still pauses with the lifecycle and reduced motion.
            AsciiRobot(
                state = robotState,
                size = robotSize,
                progress = loadFill,
                pulse = mouthPulse,
                active = true,
                lively = true,
                onLongPress = onLongPress,
            )
            // The stage floor: the same fading hairline the hero stands on,
            // without the ring — the input bar below is the rest of the stage.
            Box(
                Modifier
                    .width(robotSize * 0.72f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                palette.silverDim.copy(alpha = 0.28f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}

/**
 * Loading fill for the [RobotState.Loading] eyes: a slow endless bottom-up
 * fill, parked at a readable half-filled state with reduced motion, 0 when
 * not loading. Shared by the home hero and the chat dock.
 */
@Composable
internal fun rememberLoadFill(loading: Boolean): Float = when {
    !loading -> 0f
    // With reduced motion the eyes sit at a readable half-filled state
    // instead of running an endless decorative fill.
    LocalReducedMotion.current -> 0.6f
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
