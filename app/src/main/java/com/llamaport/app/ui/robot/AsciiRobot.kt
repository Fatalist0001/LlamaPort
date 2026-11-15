package com.llamaport.app.ui.robot

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Solid pixel cell (U+2588). If a font lacks it, [#] is a safe fallback. */
private const val BLOCK = '█'

/** Glyph pool for the scramble intro and flickers. */
private val SCRAMBLE = "*+#=:.·".toList()

/** Everything we ever render, pre-measured once per size. */
private val ALL_GLYPHS = (SCRAMBLE + listOf(BLOCK, 'x', '-', '·')).toSet()

/** Cell tick: ~13 fps for the pixel-style cell animation. */
private const val TICK_MS = 75L

/** Blink length in ticks (about 150 ms). */
private const val BLINK_TICKS = 2

/** One slow breathing cycle in ticks (about 2.6 s at 75 ms per tick). */
private const val BREATH_TICKS = 35

/** A measured glyph plus its tight ink bounds inside the layout box. */
private class Glyph(val layout: TextLayoutResult, val bounds: Rect)

/**
 * The LlamaPort logo as an animated ASCII robot.
 *
 * The glyph grid comes from [AppIcons.Robot] (rasterized once); state behavior
 * is layered on top: sleeping/loading/idle/thinking/generating/error.
 * Tap makes the robot blink or hop.
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
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val painter = rememberVectorPainter(AppIcons.Robot)
    val grid = remember(painter) { RobotGrid.rasterize(painter) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // --- cell animation ticker (~13 fps) --------------------------------
    // The ticker is the only animation clock: blink, scramble and breathing
    // all derive from it, so the robot never invalidates at display rate.
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, reduced) {
        if (active && !reduced) {
            while (true) {
                delay(TICK_MS)
                frame++
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

    // --- breathing --------------------------------------------------------
    // Derived from the slow ticker (one sine cycle per BREATH_TICKS), so the
    // robot redraws at ~13 fps instead of on every display frame.
    val breath = if (active && !reduced) {
        (frame % BREATH_TICKS).toFloat() / BREATH_TICKS
    } else {
        0.25f
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
    val scheme = MaterialTheme.colorScheme
    val bodyTarget = when (state) {
        RobotState.Sleeping -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val bodyColor by animateColorAsState(
        targetValue = bodyTarget,
        animationSpec = tween(durationMillis = MotionTokens.Durations.ENTER),
        label = "robotBodyColor",
    )

    // --- idle blink scheduling --------------------------------------------
    var nextBlinkAt by remember { mutableIntStateOf(24) }
    var blinkEndAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(frame, state) {
        if (state == RobotState.Idle && !reduced && frame >= nextBlinkAt) {
            blinkEndAt = frame + BLINK_TICKS
            nextBlinkAt = frame + 40 + Random.nextInt(28)
        }
    }

    // --- tap: hop + blink --------------------------------------------------
    val scope = rememberCoroutineScope()
    val jump = remember { Animatable(0f) }
    fun handleTap() {
        onTap?.invoke()
        blinkEndAt = frame + BLINK_TICKS
        scope.launch {
            if (!reduced) {
                jump.animateTo(1f, animationSpec = MotionTokens.Springs.Press)
                jump.animateTo(0f, animationSpec = MotionTokens.Springs.Gentle)
            }
        }
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
            .pointerInput(onTap, onLongPress) {
                detectTapGestures(
                    onTap = { handleTap() },
                    onLongPress = { onLongPress?.invoke() },
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
            drawRobot(
                grid = grid,
                state = state,
                frame = frame,
                scramble = scramble.value,
                enter = enter.value,
                breath = breath,
                jump = jump.value,
                shake = shake.value,
                progress = progress,
                pulse = pulse,
                blinkNow = frame <= blinkEndAt,
                bodyColor = bodyColor,
                background = scheme.surface,
                accentColor = scheme.tertiary,
                errorColor = scheme.error,
                dimColor = scheme.onSurfaceVariant,
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
    jump: Float,
    shake: Float,
    progress: Float,
    pulse: Float,
    blinkNow: Boolean,
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
    val baseColor = if (state == RobotState.Sleeping) {
        lerp(bodyColor, background, sleepingDim)
    } else {
        bodyColor
    }

    val offsetY = -6.dp.toPx() * sin(PI * jump).toFloat()
    val offsetX = if (state == RobotState.Error && shake < 1f) {
        5.dp.toPx() * sin(shake * 3f * PI).toFloat() * (1f - shake)
    } else {
        0f
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

    translate(left = offsetX, top = offsetY) {
        // --- base silhouette -----------------------------------------------
        if (scramble < 1f) {
            // intro: per-cell glyphs so cells can flicker with scramble chars
            for (gy in 0 until grid.size) {
                for (gx in 0 until grid.size) {
                    if (!grid.isCovered[gy][gx]) continue
                    val ch = if (scramble < grid.noise[gy][gx]) {
                        SCRAMBLE[RobotGrid.cellHash(gx, gy, frame / 2) % SCRAMBLE.size]
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
            // idle: rare single-cell glitch (colour only, silhouette stays solid)
            if (state == RobotState.Idle) {
                for (gy in 0 until grid.size) {
                    for (gx in 0 until grid.size) {
                        if (!grid.isCovered[gy][gx]) continue
                        if ((RobotGrid.cellHash(gx, gy, frame / 3) % 1000) / 1000f > 0.9985f) {
                            cellRect(gx, gy, accentColor, 1f)
                        }
                    }
                }
            }
            // thinking: antenna blinks
            if (state == RobotState.Thinking && (frame / 5) % 2 == 0) {
                for (gy in 0 until grid.size) {
                    for (gx in 0 until grid.size) {
                        if (grid.isAntenna[gy][gx]) cellRect(gx, gy, accentColor, 1f)
                    }
                }
            }
        }

        val overlayAlpha = enter

        // --- state overlays on the eye sockets -----------------------------
        for (region in grid.eyeRegions) {
            val w = region.width + 1
            val h = region.height + 1
            when (state) {
                RobotState.Sleeping -> {
                    // closed lids: a dim dash in the bottom row of each socket
                    for (gx in region.left..region.right) {
                        glyphAt('-', gx, region.bottom, dimColor, overlayAlpha)
                    }
                }

                RobotState.Loading -> {
                    // sockets fill bottom-up with load progress
                    val rows = (progress.coerceIn(0f, 1f) * h).roundToInt()
                    for (row in 0 until rows) {
                        val gy = region.bottom - row
                        for (gx in region.left..region.right) {
                            cellRect(gx, gy, accentColor, overlayAlpha)
                        }
                    }
                }

                RobotState.Idle -> {
                    // blink: sockets shut for a moment
                    if (blinkNow) {
                        for (gy in region.top..region.bottom) {
                            for (gx in region.left..region.right) {
                                cellRect(gx, gy, bodyColor, 1f)
                            }
                        }
                    }
                }

                RobotState.Thinking -> {
                    // darting pupil inside each socket
                    val offsets = listOf(
                        0 to 1, 2 to 1, 1 to 0, 1 to 2, 0 to 0, 2 to 2,
                    )
                    val (dx, dy) = offsets[(frame / 4) % offsets.size]
                    cellRect(region.left + dx, region.top + dy, accentColor, overlayAlpha)
                }

                RobotState.Error -> {
                    // cross eyes
                    for (i in 0 until 3) {
                        glyphAt('x', region.left + i, region.top + i, errorColor, overlayAlpha)
                        glyphAt('x', region.left + i, region.top + 2 - i, errorColor, overlayAlpha)
                    }
                }

                RobotState.Generating -> Unit // handled by the mouth below
            }
        }

        // --- generating: pulsing mouth -------------------------------------
        if (state == RobotState.Generating) {
            val cols = grid.mouthCols.count()
            val width = (pulse.coerceIn(0f, 1f) * cols).roundToInt()
            if (width > 0) {
                val mid = (grid.mouthCols.first + grid.mouthCols.last) / 2f
                val start = (mid - width / 2f + 0.5f).roundToInt()
                for (gx in start until start + width) {
                    if (gx in grid.mouthCols) {
                        cellRect(gx, grid.mouthRow, accentColor, overlayAlpha)
                    }
                }
            }
        }
    }
}
