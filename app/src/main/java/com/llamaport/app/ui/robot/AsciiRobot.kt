package com.llamaport.app.ui.robot

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.llamaport.app.ui.theme.ObsidianTheme
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Solid pixel cell (U+2588). If a font lacks it, [#] is a safe fallback. */
private const val BLOCK = '█'

/** Glyph pool for the scramble intro and flickers. */
private val SCRAMBLE = "*+#=:.·".toList()

/** Everything we ever render, pre-measured once per size. */
private val ALL_GLYPHS = (SCRAMBLE + listOf(BLOCK, 'x', '-', '·')).toSet()

/** Cell tick during a gesture/reaction: ~16 fps. */
private const val TICK_MS = 60L

/** Slower breathing tick while the robot only hangs and waits. */
private const val IDLE_TICK_MS = 120L

/** One slow breathing cycle in ticks (about 2.5 s at 50 ms per tick). */
private const val BREATH_TICKS = 50

/** A measured glyph plus its tight ink bounds inside the layout box. */
private class Glyph(val layout: TextLayoutResult, val bounds: Rect)

/**
 * The LlamaPort logo as an animated ASCII robot.
 *
 * The glyph grid comes from [AppIcons.Robot] (rasterized once); state behavior
 * is layered on top: sleeping/loading/idle/thinking/generating/error.
 *
 * With [lively] the robot performs on its own: a weighted schedule of idle
 * gestures (looks, blinks, emotions, nods, glitches…) and varied tap reactions
 * that also look towards the pressed spot. The performance only runs for
 * [RobotState.Idle] and [RobotState.Sleeping]; other states keep their
 * functional overlays. Reduced motion disables the performance entirely.
 *
 * @param progress model load progress in 0..1 (Loading).
 * @param pulse token pulse in 0..1, one beat per streamed token (Generating).
 * @param active when false the robot freezes: the ticker stops and no frames
 *   are invalidated. Use for robots that are not on screen (closed drawer).
 */
@Composable
fun AsciiRobot(
    state: RobotState,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    progress: Float = 0f,
    pulse: Float = 0f,
    active: Boolean = true,
    lively: Boolean = false,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val painter = rememberVectorPainter(AppIcons.Robot)
    val grid = remember(painter) { RobotGrid.rasterize(painter) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current

    // --- performance scheduler state --------------------------------------
    // One weighted catalogue of idle gestures and tap reactions (see
    // [RobotPerformance]). Runs only while the robot is lively, on screen and
    // in a state that has nothing more important to say.
    val performs = lively && active && !reduced &&
        (state == RobotState.Idle || state == RobotState.Sleeping)
    val random = remember { Random(System.nanoTime()) }

    var idleAction by remember { mutableStateOf<IdleAction?>(null) }
    var idleStartFrame by remember { mutableIntStateOf(0) }
    var idleTicks by remember { mutableIntStateOf(1) }
    var idleCycle by remember { mutableStateOf<List<IdleAction>>(emptyList()) }

    var tapReaction by remember { mutableStateOf<TapReaction?>(null) }
    var reactionStartFrame by remember { mutableIntStateOf(0) }
    var reactionTicks by remember { mutableIntStateOf(1) }
    var reactionCycle by remember { mutableStateOf<List<TapReaction>>(emptyList()) }
    var tapX by remember { mutableFloatStateOf(0f) }
    var tapY by remember { mutableFloatStateOf(0f) }

    var resumeAtFrame by remember { mutableIntStateOf(-1) }

    // --- single animation ticker (~20 fps) + scheduler -------------------
    // The ticker is the only animation clock: breathing, scramble, state
    // overlays and the performance scheduler all derive from it, so the robot
    // never invalidates at display rate. The scheduler steps in the same loop
    // so a state change never leaves a coroutine holding a stale mood.
    var frame by remember { mutableIntStateOf(0) }
    val liveState by rememberUpdatedState(state)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(active, reduced, lively, lifecycleOwner) {
        if (!active || reduced) return@LaunchedEffect
        // Tie the only animation clock to STARTED: when the app is backgrounded
        // the ticker is cancelled and revived on return. Gestures run at 60 ms;
        // between them the robot only breathes, so the tick slows to 120 ms.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val motion = tapReaction != null || idleAction != null
                delay(if (motion) TICK_MS else IDLE_TICK_MS)
                frame++
                val sleeping = liveState == RobotState.Sleeping
                val performsNow = lively &&
                    (liveState == RobotState.Idle || sleeping)
                if (!performsNow) continue
                if (tapReaction != null) {
                    if (frame >= reactionStartFrame + reactionTicks) {
                        tapReaction = null
                        resumeAtFrame = frame + RobotPerformance.reactionResumeTicks(random)
                    }
                } else if (idleAction != null) {
                    if (frame >= idleStartFrame + idleTicks) {
                        idleAction = null
                        resumeAtFrame = frame + RobotPerformance.pauseTicks(random, sleeping)
                    }
                } else if (resumeAtFrame < 0) {
                    resumeAtFrame = frame + RobotPerformance.firstDelayTicks(random)
                } else if (frame >= resumeAtFrame) {
                    if (idleCycle.isEmpty()) {
                        idleCycle = RobotPerformance.idleCycle(random, sleeping)
                    }
                    val action = idleCycle.first()
                    idleCycle = idleCycle.drop(1)
                    idleAction = action
                    idleStartFrame = frame
                    idleTicks = RobotPerformance.ticksFor(action.durationMs)
                }
            }
        }
    }

    // --- glyph layout cache ----------------------------------------------
    var canvasPx by remember { mutableStateOf(IntSize.Zero) }
    // The solid silhouette never changes geometry, so it is built once per
    // size and then drawn as a single path instead of ~576 rects.
    val silhouette = remember(grid, canvasPx) { buildSilhouettePath(grid, canvasPx) }
    val glyphs: Map<Char, Glyph> = remember(canvasPx) {
        if (canvasPx == IntSize.Zero) {
            emptyMap()
        } else {
            val cellPx = canvasPx.width.toFloat() / grid.size
            val fontSize = with(density) { cellPx.toSp() }
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize)
            ALL_GLYPHS.associateWith { ch ->
                val layout = textMeasurer.measure(text = ch.toString(), style = style)
                Glyph(layout, layout.getBoundingBox(0))
            }
        }
    }

    // --- intro scramble ---------------------------------------------------
    val scramble = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            scramble.snapTo(1f)
        } else {
            scramble.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 550,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }

    // --- smooth state transition -----------------------------------------
    val enter = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (reduced) {
            enter.snapTo(1f)
        } else {
            enter.snapTo(0f)
            enter.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.Durations.SHORT,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }

    // --- colors -----------------------------------------------------------
    val palette = ObsidianTheme.palette
    val bodyTarget = when (state) {
        RobotState.Sleeping -> palette.textSecondary
        else -> palette.silver
    }
    val bodyColor by animateColorAsState(
        targetValue = bodyTarget,
        animationSpec = tween(durationMillis = MotionTokens.Durations.ENTER),
        label = "robotBodyColor",
    )

    // Leaving a performant state (or losing the screen) clears any action so
    // a stale pose never leaks into Loading/Generating.
    LaunchedEffect(performs) {
        if (!performs) {
            idleAction = null
            tapReaction = null
            resumeAtFrame = -1
        }
    }

    // --- tap: weighted reaction aimed at the pressed spot ------------------
    fun handleTap(offset: Offset?) {
        onTap?.invoke()
        // Feedback even with reduced motion or in a non-performing state.
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (!performs) return
        // Normalise the tap relative to the robot centre: the reaction looks
        // towards where the user actually touched.
        if (canvasPx.width > 0 && offset != null) {
            tapX = ((offset.x - canvasPx.width / 2f) / (canvasPx.width / 2f))
                .coerceIn(-1f, 1f)
            tapY = ((offset.y - canvasPx.height / 2f) / (canvasPx.height / 2f))
                .coerceIn(-1f, 1f)
        } else {
            tapX = 0f
            tapY = 0f
        }
        if (reactionCycle.isEmpty()) {
            reactionCycle = RobotPerformance.reactionCycle(random)
        }
        val reaction = reactionCycle.first()
        reactionCycle = reactionCycle.drop(1)
        tapReaction = reaction
        reactionStartFrame = frame
        reactionTicks = RobotPerformance.ticksFor(reaction.durationMs)
        idleAction = null
    }

    // --- error shake --------------------------------------------------------
    val shake = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == RobotState.Error && !reduced) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 450,
                    easing = MotionTokens.Easing.EmphasizedAccelerate,
                ),
            )
        } else {
            shake.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .aspectRatio(1f)
            .semantics {
                contentDescription = "Робот LlamaPort"
                role = Role.Button
                onClick(label = "Взаимодействовать") {
                    handleTap(null)
                    true
                }
            }
            .pointerInput(performs, onTap, onLongPress) {
                detectTapGestures(
                    onTap = { offset -> handleTap(offset) },
                    onLongPress = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress?.invoke()
                    },
                )
            },
    ) {
        Canvas(
            Modifier
                .size(size)
                .onSizeChanged { canvasPx = it }
                // Own layer: a tick redraws just the robot instead of
                // re-recording the whole screen.
                .graphicsLayer(),
        ) {
            if (glyphs.isEmpty()) return@Canvas
            // Read the ticker and performance state in the draw phase only:
            // a 20 fps tick invalidates the draw, not the composition.
            val currentFrame = frame
            val breath = if (active && !reduced) {
                (currentFrame % BREATH_TICKS).toFloat() / BREATH_TICKS
            } else {
                0.25f
            }
            val reaction = tapReaction
            val idle = idleAction
            val pose = when {
                !performs -> null
                reaction != null -> RobotPerformance.reactionPose(
                    reaction = reaction,
                    progress = ((currentFrame - reactionStartFrame).toFloat() / reactionTicks)
                        .coerceIn(0f, 1f),
                    tapX = tapX,
                    tapY = tapY,
                )
                idle != null -> RobotPerformance.idlePose(
                    action = idle,
                    progress = ((currentFrame - idleStartFrame).toFloat() / idleTicks)
                        .coerceIn(0f, 1f),
                )
                else -> null
            }
            drawRobot(
                grid = grid,
                state = state,
                frame = currentFrame,
                scramble = scramble.value,
                enter = enter.value,
                breath = breath,
                shake = shake.value,
                progress = progress,
                pulse = pulse,
                pose = pose,
                bodyColor = bodyColor,
                background = palette.surface,
                accentColor = palette.silver,
                errorColor = palette.error,
                dimColor = palette.textSecondary,
                glyphs = glyphs,
                silhouette = silhouette,
            )
        }
    }
}

private fun buildSilhouettePath(grid: RobotGrid, canvasPx: IntSize): Path {
    val path = Path()
    if (canvasPx == IntSize.Zero) return path
    val cell = canvasPx.width.toFloat() / grid.size
    val over = cell * 1.04f
    for (gy in 0 until grid.size) {
        for (gx in 0 until grid.size) {
            if (!grid.isCovered[gy][gx]) continue
            val cx = (gx + 0.5f) * cell
            val cy = (gy + 0.5f) * cell
            path.addRect(Rect(cx - over / 2f, cy - over / 2f, cx + over / 2f, cy + over / 2f))
        }
    }
    return path
}

private fun DrawScope.drawRobot(
    grid: RobotGrid,
    state: RobotState,
    frame: Int,
    scramble: Float,
    enter: Float,
    breath: Float,
    shake: Float,
    progress: Float,
    pulse: Float,
    pose: RobotPose?,
    bodyColor: Color,
    background: Color,
    accentColor: Color,
    errorColor: Color,
    dimColor: Color,
    glyphs: Map<Char, Glyph>,
    silhouette: Path,
) {
    val cell = size.width / grid.size
    val breathSine = sin(breath * 2f * PI).toFloat()

    // Dim by blending towards the background, not by alpha: glyph blocks
    // overlap slightly to tile seamlessly, and translucent overlap would
    // show up as a grid artifact.
    val sleepingDim = 0.52f + 0.14f * breathSine
    var baseColor = if (state == RobotState.Sleeping) {
        lerp(bodyColor, background, sleepingDim)
    } else {
        bodyColor
    }
    // Performance brightness (shy, sleepy, glitch…) dims the whole body.
    if (pose != null && pose.brightness < 1f) {
        baseColor = lerp(baseColor, background, (1f - pose.brightness).coerceIn(0f, 0.35f))
    }

    val over = cell * 1.04f

    fun glyphAt(ch: Char, gx: Int, gy: Int, color: Color, alpha: Float) {
        val g = glyphs[ch] ?: return
        val b = g.bounds
        if (b.width <= 0f || b.height <= 0f) return
        val cx = (gx + 0.5f) * cell
        val cy = (gy + 0.5f) * cell
        val tint = if (alpha >= 1f) color else color.copy(alpha = color.alpha * alpha)
        // The layout box includes line leading, so the glyph ink is shorter
        // than the box; slight overscan makes cells tile seamlessly.
        val sx = cell * 1.04f / b.width
        val sy = cell * 1.28f / b.height
        translate(left = cx - cell / 2f - b.left * sx, top = cy - cell / 2f - b.top * sy) {
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                drawText(
                    textLayoutResult = g.layout,
                    color = tint,
                    topLeft = Offset.Zero,
                )
            }
        }
    }

    fun cellRect(gx: Int, gy: Int, color: Color, alpha: Float) {
        val cx = (gx + 0.5f) * cell
        val cy = (gy + 0.5f) * cell
        val tint = if (alpha >= 1f) color else color.copy(alpha = color.alpha * alpha)
        drawRect(
            color = tint,
            topLeft = Offset(cx - over / 2f, cy - over / 2f),
            size = Size(over, over),
        )
    }

    // --- body transform: error shake + performance pose + gentle float ----
    val errorOffsetX = if (state == RobotState.Error && shake < 1f) {
        5.dp.toPx() * sin(shake * 3f * PI).toFloat() * (1f - shake)
    } else {
        0f
    }
    val poseOffsetX = (pose?.offsetX ?: 0f) * cell
    val poseOffsetY = (pose?.offsetY ?: 0f) * cell
    // Between gestures the robot still hangs with a barely visible float.
    val floatY = if (pose == null && state != RobotState.Error) {
        0.35f * cell * breathSine
    } else {
        0f
    }
    val rotation = pose?.rotation ?: 0f
    val scale = pose?.scale ?: 1f
    val center = Offset(size.width / 2f, size.height / 2f)

    translate(
        left = errorOffsetX + poseOffsetX,
        top = poseOffsetY + floatY,
    ) {
        rotate(degrees = rotation, pivot = center) {
            scale(scaleX = scale, scaleY = scale, pivot = center) {

                // --- base silhouette ---------------------------------------
                // The performance glitch re-uses the scramble path: a few
                // cells dissolve into glyph noise and return.
                val glitching = pose != null && pose.glitch > 0.5f
                if (scramble < 1f || glitching) {
                    // During a glitch roughly a quarter of the cells dissolve
                    // into glyph noise and reassemble; the intro resolves from
                    // full noise into the solid logo.
                    val dissolve = if (glitching) {
                        (scramble * 0.72f + 0.18f * sin(frame * 1.3f)).coerceIn(0.62f, 0.88f)
                    } else {
                        scramble
                    }
                    for (gy in 0 until grid.size) {
                        for (gx in 0 until grid.size) {
                            if (!grid.isCovered[gy][gx]) continue
                            val ch = if (dissolve < grid.noise[gy][gx]) {
                                SCRAMBLE[
                                    RobotGrid.cellHash(gx, gy, frame / 2) % SCRAMBLE.size
                                ]
                            } else {
                                BLOCK
                            }
                            if (ch == BLOCK) {
                                cellRect(gx, gy, baseColor, 1f)
                            } else {
                                glyphAt(ch, gx, gy, baseColor, 1f)
                            }
                        }
                    }
                } else {
                    drawPath(silhouette, color = baseColor)
                    // idle: rare single-cell glitch (colour only, silhouette
                    // stays solid)
                    if (state == RobotState.Idle) {
                        for (gy in 0 until grid.size) {
                            for (gx in 0 until grid.size) {
                                if (!grid.isCovered[gy][gx]) continue
                                val hash = (RobotGrid.cellHash(gx, gy, frame / 3) % 1000) / 1000f
                                if (hash > 0.9985f) {
                                    cellRect(gx, gy, background, 1f)
                                }
                            }
                        }
                    }
                    // thinking: antenna blinks
                    if (state == RobotState.Thinking && (frame / 5) % 2 == 0) {
                        for (gy in 0 until grid.size) {
                            for (gx in 0 until grid.size) {
                                if (grid.isAntenna[gy][gx]) {
                                    cellRect(gx, gy, background, 1f)
                                }
                            }
                        }
                    }
                }

                // Performance antenna glow: a dark flash engraved into the silver body.
                if (pose != null && pose.antenna > 0.05f) {
                    for (gy in 0 until grid.size) {
                        for (gx in 0 until grid.size) {
                            if (grid.isAntenna[gy][gx]) {
                                cellRect(gx, gy, background, pose.antenna.coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                val overlayAlpha = enter

                // --- eyes ---------------------------------------------------
                for ((index, region) in grid.eyeRegions.withIndex()) {
                    val w = region.width + 1
                    val h = region.height + 1
                    when {
                        // Functional states keep their informative overlays.
                        state == RobotState.Loading -> {
                            val rows = (progress.coerceIn(0f, 1f) * h).roundToInt()
                            for (row in 0 until rows) {
                                val gy = region.bottom - row
                                for (gx in region.left..region.right) {
                                    cellRect(gx, gy, accentColor, overlayAlpha)
                                }
                            }
                        }

                        state == RobotState.Thinking -> {
                            val offsets = listOf(
                                0 to 1, 2 to 1, 1 to 0, 1 to 2, 0 to 0, 2 to 2,
                            )
                            val (dx, dy) = offsets[(frame / 4) % offsets.size]
                            cellRect(region.left + dx, region.top + dy, accentColor, overlayAlpha)
                        }

                        state == RobotState.Error -> {
                            for (i in 0 until 3) {
                                glyphAt('x', region.left + i, region.top + i, errorColor, overlayAlpha)
                                glyphAt(
                                    'x',
                                    region.left + i,
                                    region.top + 2 - i,
                                    errorColor,
                                    overlayAlpha,
                                )
                            }
                        }

                        state == RobotState.Generating -> Unit // mouth only

                        pose != null -> drawEyeExpression(
                            expression = pose.eyes,
                            region = region,
                            pupilX = pose.pupilX,
                            pupilY = pose.pupilY,
                            leftEye = index == 0,
                            cellRect = ::cellRect,
                            glyphAt = ::glyphAt,
                            bodyColor = bodyColor,
                            dimColor = dimColor,
                        )

                        // Sleeping without a performance pose: resting lids.
                        state == RobotState.Sleeping -> {
                            for (gx in region.left..region.right) {
                                glyphAt('-', gx, region.bottom, dimColor, overlayAlpha)
                            }
                        }

                        // Plain idle: steady pupils.
                        else -> {
                            cellRect(region.left + 1, region.top + 1, bodyColor, 1f)
                            cellRect(region.left + 2, region.top + 1, bodyColor, 1f)
                        }
                    }
                }

                // --- mouths -------------------------------------------------
                val mouth: MouthExpression? = when {
                    state == RobotState.Generating -> null
                    pose != null -> pose.mouth
                    else -> null
                }
                if (mouth != null && mouth != MouthExpression.Neutral) {
                    drawMouthExpression(
                        expression = mouth,
                        grid = grid,
                        cellRect = ::cellRect,
                        color = background,
                        dimColor = dimColor,
                    )
                }

                // --- generating: pulsing mouth -----------------------------
                if (state == RobotState.Generating) {
                    val cols = grid.mouthCols.count()
                    val width = (pulse.coerceIn(0f, 1f) * cols).roundToInt()
                    if (width > 0) {
                        val mid = (grid.mouthCols.first + grid.mouthCols.last) / 2f
                        val start = (mid - width / 2f + 0.5f).roundToInt()
                        for (gx in start until start + width) {
                            if (gx in grid.mouthCols) {
                                cellRect(gx, grid.mouthRow, background, overlayAlpha)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws one eye socket according to [EyeExpression]. Socket geometry comes
 * from the rasterized logo: each socket is a small rectangle of background
 * (a hole in the silhouette), so any cell drawn inside reads as the eye.
 */
private fun DrawScope.drawEyeExpression(
    expression: EyeExpression,
    region: IntRect,
    pupilX: Float,
    pupilY: Float,
    leftEye: Boolean,
    cellRect: (Int, Int, Color, Float) -> Unit,
    glyphAt: (Char, Int, Int, Color, Float) -> Unit,
    bodyColor: Color,
    dimColor: Color,
) {
    val left = region.left
    val top = region.top
    val right = region.right
    val bottom = region.bottom
    when (expression) {
        EyeExpression.Open -> {
            // 2x2 pupil block that travels inside the socket.
            val w = right - left
            val h = bottom - top
            val px = ((pupilX + 1f) / 2f * (w - 1)).roundToInt().coerceIn(0, (w - 1).coerceAtLeast(0))
            val py = ((pupilY + 1f) / 2f * (h - 1)).roundToInt().coerceIn(0, (h - 1).coerceAtLeast(0))
            cellRect(left + px, top + py, bodyColor, 1f)
            cellRect(left + px + 1, top + py, bodyColor, 1f)
            cellRect(left + px, top + py + 1, bodyColor, 1f)
            cellRect(left + px + 1, top + py + 1, bodyColor, 1f)
        }

        EyeExpression.Closed -> {
            // Resting lids: a dim dash in the middle row.
            for (gx in left..right) {
                glyphAt('-', gx, top + 1, dimColor, 1f)
            }
        }

        EyeExpression.Happy -> {
            // "^": apex at the top-centre.
            cellRect(left, top + 1, bodyColor, 1f)
            cellRect(left + 1, top, bodyColor, 1f)
            cellRect(right, top + 1, bodyColor, 1f)
        }

        EyeExpression.Sad -> {
            // "v": apex at the bottom-centre.
            cellRect(left, top, bodyColor, 1f)
            cellRect(left + 1, bottom, bodyColor, 1f)
            cellRect(right, top, bodyColor, 1f)
        }

        EyeExpression.Angry -> {
            // Slanted glare: brows converge towards the face centre.
            if (leftEye) {
                cellRect(left, top, bodyColor, 1f)
                cellRect(left + 1, top + 1, bodyColor, 1f)
                cellRect(right, bottom, bodyColor, 1f)
            } else {
                cellRect(left, bottom, bodyColor, 1f)
                cellRect(left + 1, top + 1, bodyColor, 1f)
                cellRect(right, top, bodyColor, 1f)
            }
        }

        EyeExpression.Wide -> {
            // The whole socket lit: astonishment.
            for (gy in top..bottom) {
                for (gx in left..right) {
                    cellRect(gx, gy, bodyColor, 1f)
                }
            }
        }

        EyeExpression.Squint -> {
            // A solid half-closed lid line.
            for (gx in left..right) {
                cellRect(gx, top + 1, dimColor, 1f)
            }
        }

        EyeExpression.WinkLeft -> {
            if (leftEye) {
                for (gx in left..right) {
                    glyphAt('-', gx, top + 1, dimColor, 1f)
                }
            } else {
                cellRect(left + 1, top + 1, bodyColor, 1f)
                cellRect(left + 2, top + 1, bodyColor, 1f)
            }
        }

        EyeExpression.WinkRight -> {
            if (!leftEye) {
                for (gx in left..right) {
                    glyphAt('-', gx, top + 1, dimColor, 1f)
                }
            } else {
                cellRect(left + 1, top + 1, bodyColor, 1f)
                cellRect(left + 2, top + 1, bodyColor, 1f)
            }
        }

        EyeExpression.Cross -> {
            for (i in 0..2) {
                glyphAt('x', left + i.coerceAtMost(right - left), top + i, bodyColor, 1f)
                glyphAt('x', left + i.coerceAtMost(right - left), bottom - i, bodyColor, 1f)
            }
        }
    }
}

/** Draws the mouth row according to [MouthExpression]. */
private fun DrawScope.drawMouthExpression(
    expression: MouthExpression,
    grid: RobotGrid,
    cellRect: (Int, Int, Color, Float) -> Unit,
    color: Color,
    dimColor: Color,
) {
    val row = grid.mouthRow
    val cols = grid.mouthCols
    val first = cols.first
    val last = cols.last
    val mid = (first + last) / 2
    when (expression) {
        MouthExpression.Neutral -> Unit

        MouthExpression.Smile -> {
            // "∪": corners up, middle down.
            cellRect(first, row - 1, color, 1f)
            cellRect(first + 1, row, color, 1f)
            cellRect(mid, row, color, 1f)
            cellRect(last - 1, row, color, 1f)
            cellRect(last, row - 1, color, 1f)
        }

        MouthExpression.Frown -> {
            // "∩": corners down, middle up.
            cellRect(first, row, color, 1f)
            cellRect(first + 1, row - 1, color, 1f)
            cellRect(mid, row - 1, color, 1f)
            cellRect(last - 1, row - 1, color, 1f)
            cellRect(last, row, color, 1f)
        }

        MouthExpression.Open -> {
            // An open block: laughing / yawning.
            for (gx in (mid - 1)..(mid + 1)) {
                if (gx in cols) {
                    cellRect(gx, row - 1, color, 1f)
                    cellRect(gx, row, color, 1f)
                }
            }
        }

        MouthExpression.O -> {
            // A tiny diamond ring: "oh".
            cellRect(mid, row - 1, color, 1f)
            if (mid - 1 in cols) cellRect(mid - 1, row, color, 1f)
            if (mid + 1 in cols) cellRect(mid + 1, row, color, 1f)
            if (row + 1 < grid.size) cellRect(mid, row + 1, color, 1f)
        }

        MouthExpression.Smirk -> {
            // Rising towards one side.
            cellRect(first, row, color, 1f)
            cellRect(first + 1, row, color, 1f)
            if (mid + 1 in cols) cellRect(mid + 1, row - 1, color, 1f)
            if (last in cols) cellRect(last, row - 1, color, 1f)
        }

        MouthExpression.Flat -> {
            for (gx in cols) {
                cellRect(gx, row, dimColor, 1f)
            }
        }
    }
}
/**
 * Loading fill for the [RobotState.Loading] eyes: a slow endless bottom-up
 * fill, parked at a readable half-filled state with reduced motion, 0 when
 * not loading. Shared by the home hero and the chat stage header.
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
