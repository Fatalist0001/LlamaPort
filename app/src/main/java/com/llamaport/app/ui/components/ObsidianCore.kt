package com.llamaport.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens
import com.llamaport.app.ui.robot.AsciiRobot
import com.llamaport.app.ui.robot.RobotState
import com.llamaport.app.ui.theme.ObsidianTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home hero: a polished obsidian slab stack with silver bevels, a precision
 * ring and the pixel robot engraved in a dark seal. Deliberately not a glowing
 * AI orb: the object is matte, geometric and lit only by a thin rim.
 *
 * Motion is cheap — one draw-phase clock drives the light drift and rings; the
 * robot keeps its own 13 fps cell ticker. Reduced motion freezes everything
 * except the short completion flash, and off-screen composition (empty chat
 * replaced by messages) removes the object entirely.
 */
@Composable
fun ObsidianCore(
    state: RobotState,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    loadingProgress: Float = 0f,
    inputActive: Boolean = false,
    completionTick: Int = 0,
    active: Boolean = true,
    onLongPress: (() -> Unit)? = null,
) {
    val palette = ObsidianTheme.palette
    val reduced = LocalReducedMotion.current

    val drift = if (active && !reduced) {
        rememberInfiniteTransition(label = "coreDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(24_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "coreDriftPhase",
        )
    } else {
        null
    }
    val fast = if (active && !reduced) {
        rememberInfiniteTransition(label = "coreFast").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "coreFastPhase",
        )
    } else {
        null
    }

    val inputGlow by animateFloatAsState(
        targetValue = if (inputActive) 1f else 0f,
        animationSpec = tween(MotionTokens.Durations.SHORT, easing = MotionTokens.Easing.Precision),
        label = "coreInputGlow",
    )

    val flash = remember { Animatable(1f) }
    LaunchedEffect(completionTick, reduced) {
        if (completionTick > 0 && !reduced) {
            flash.snapTo(0f)
            flash.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.Durations.PULSE,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(state, reduced) {
        if (state == RobotState.Error && !reduced) {
            shake.snapTo(0f)
            shake.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 520,
                    easing = MotionTokens.Easing.EmphasizedAccelerate,
                ),
            )
        }
    }

    val rim = when (state) {
        RobotState.Error -> palette.error
        RobotState.Loading, RobotState.Generating -> palette.silver
        else -> palette.silverDim
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val reaction = 1f + 0.012f * inputGlow
                scaleX = reaction
                scaleY = reaction
                translationX = if (shake.value in 0.001f..0.999f) {
                    sin(shake.value * 3f * PI).toFloat() * 3.dp.toPx() * (1f - shake.value)
                } else {
                    0f
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val base = this.size.minDimension
            val cx = this.size.width / 2f
            val cy = this.size.height * 0.49f
            val driftPhase = drift?.value ?: 0.24f
            val fastPhase = fast?.value ?: 0f

            drawCore(
                palette = palette,
                state = state,
                cx = cx,
                cy = cy,
                base = base,
                drift = driftPhase,
                fast = fastPhase,
                reduced = reduced,
                rim = rim,
                inputGlow = inputGlow,
                flash = flash.value,
                loadingProgress = loadingProgress,
            )
        }
        // The pixel robot is the brand mark; it sits engraved in the dark seal.
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-baseOffset(size)).dp)
                .size(size * 0.225f),
            contentAlignment = Alignment.Center,
        ) {
            AsciiRobot(
                state = state,
                size = size * 0.225f,
                progress = loadingProgress,
                pulse = if (state == RobotState.Generating) 0.68f else 0f,
                active = active,
                onTap = {},
                onLongPress = onLongPress,
            )
        }
        SilverPulseOverlay(
            trigger = completionTick,
            color = palette.silver,
            modifier = Modifier.matchParentSize(),
            maxScale = 1.15f,
        )
    }
}

/** Matches the stacked-plate gap so the robot sits in the top slab. */
private fun baseOffset(size: Dp): Float = (size.value * 0.035f)

private fun DrawScope.drawCore(
    palette: com.llamaport.app.ui.theme.ObsidianPalette,
    state: RobotState,
    cx: Float,
    cy: Float,
    base: Float,
    drift: Float,
    fast: Float,
    reduced: Boolean,
    rim: Color,
    inputGlow: Float,
    flash: Float,
    loadingProgress: Float,
) {
    val plateW = base * 0.50f
    val plateH = base * 0.40f
    val chamfer = base * 0.072f
    val gap = base * 0.035f
    val silver = palette.silver

    // Ambient reflection behind the object.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                palette.coreGlow.copy(alpha = 0.07f + 0.03f * inputGlow),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = base * 0.52f,
        ),
        radius = base * 0.52f,
        center = Offset(cx, cy),
    )

    // Precision bezel: two thin arcs and a tick ring, all rotating very slowly.
    val ringRadius = base * 0.395f
    drawPrecisionRing(
        cx = cx,
        cy = cy,
        radius = ringRadius,
        rotation = drift * 360f,
        color = rim.copy(alpha = if (reduced) 0.16f else 0.20f),
        tickColor = silver.copy(alpha = 0.10f),
    )
    rotate(degrees = -drift * 150f, pivot = Offset(cx, cy)) {
        drawArc(
            color = rim.copy(alpha = 0.24f),
            startAngle = 18f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(cx - ringRadius * 1.10f, cy - ringRadius * 1.10f),
            size = Size(ringRadius * 2.20f, ringRadius * 2.20f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // Three stacked polished plates, bottom to top.
    for (index in 2 downTo 0) {
        val scale = 1f - index * 0.045f
        val plateCy = cy + (index - 1) * gap
        val w = plateW * scale
        val h = plateH * scale
        val c = chamfer * scale
        val path = octagon(cx, plateCy, w, h, c)

        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(
                    palette.coreTop,
                    palette.coreFace,
                    palette.coreSide,
                ),
                startY = plateCy - h / 2f,
                endY = plateCy + h / 2f,
            ),
        )

        // Rim light: bright on the upper-left edge, nearly gone at the bottom.
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(
                    silver.copy(alpha = 0.62f),
                    silver.copy(alpha = 0.10f),
                    silver.copy(alpha = 0.02f),
                    rim.copy(alpha = 0.28f),
                ),
                start = Offset(cx - w / 2f, plateCy - h / 2f),
                end = Offset(cx + w / 2f, plateCy + h / 2f),
            ),
            style = Stroke(width = 1.dp.toPx()),
        )

        // Polished reflection clipped to the face.
        clipPath(path) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        silver.copy(alpha = 0.10f + 0.04f * inputGlow),
                        Color.Transparent,
                        Color.Transparent,
                    ),
                    start = Offset(cx - w / 2f, plateCy - h / 2f),
                    end = Offset(cx + w * 0.4f, plateCy + h * 0.55f),
                ),
                topLeft = Offset(cx - w / 2f, plateCy - h / 2f),
                size = Size(w, h),
            )
            // A single slow specular sweep; it is the only moving light at idle.
            if (!reduced) {
                val sweepX = (cx - w * 1.3f) + w * 2.6f * drift
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            silver.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        start = Offset(sweepX - w * 0.22f, plateCy - h),
                        end = Offset(sweepX + w * 0.22f, plateCy + h),
                    ),
                    topLeft = Offset(cx - w / 2f, plateCy - h / 2f),
                    size = Size(w, h),
                )
            }
            // Bottom inner shadow keeps the bevel from looking flat.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, palette.coreSide.copy(alpha = 0.55f)),
                    startY = plateCy + h * 0.15f,
                    endY = plateCy + h / 2f,
                ),
                topLeft = Offset(cx - w / 2f, plateCy),
                size = Size(w, h / 2f),
            )
        }
    }

    // Seal in the top plate.
    val sealCy = cy - gap
    val sealR = base * 0.115f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette.coreTop, palette.coreSide),
            center = Offset(cx, sealCy),
            radius = sealR,
        ),
        radius = sealR,
        center = Offset(cx, sealCy),
    )
    drawCircle(
        color = silver.copy(alpha = 0.26f + 0.10f * inputGlow),
        radius = sealR,
        center = Offset(cx, sealCy),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = silver.copy(alpha = 0.10f),
        radius = sealR * 1.22f,
        center = Offset(cx, sealCy),
        style = Stroke(width = 0.7.dp.toPx()),
    )

    // Loading: an honest indeterminate silver arc around the seal.
    if (state == RobotState.Loading) {
        rotate(degrees = fast * 360f, pivot = Offset(cx, sealCy)) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        silver.copy(alpha = 0.85f),
                    ),
                    center = Offset(cx, sealCy),
                ),
                startAngle = -90f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(cx - sealR * 1.36f, sealCy - sealR * 1.36f),
                size = Size(sealR * 2.72f, sealR * 2.72f),
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }
    }

    // Error: a restrained rose rim, not a flashing alert.
    if (state == RobotState.Error) {
        drawCircle(
            color = palette.error.copy(alpha = 0.35f),
            radius = sealR * 1.5f,
            center = Offset(cx, sealCy),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // Generation: a bright silver segment travels around the bezel.
    if (state == RobotState.Generating && !reduced) {
        rotate(degrees = fast * 360f, pivot = Offset(cx, cy)) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        silver.copy(alpha = 0.0f),
                        silver.copy(alpha = 0.90f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                ),
                startAngle = -20f,
                sweepAngle = 74f,
                useCenter = false,
                topLeft = Offset(cx - ringRadius * 1.10f, cy - ringRadius * 1.10f),
                size = Size(ringRadius * 2.20f, ringRadius * 2.20f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }

    // Completion flash: one local silver bloom.
    if (flash < 1f) {
        val fade = (1f - flash) * (1f - flash)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    silver.copy(alpha = 0.22f * fade),
                    Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = base * 0.46f,
            ),
            radius = base * 0.46f,
            center = Offset(cx, cy),
        )
    }
}

private fun DrawScope.drawPrecisionRing(
    cx: Float,
    cy: Float,
    radius: Float,
    rotation: Float,
    color: Color,
    tickColor: Color,
) {
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = color.copy(alpha = color.alpha * 0.55f),
        radius = radius * 1.06f,
        center = Offset(cx, cy),
        style = Stroke(width = 0.7.dp.toPx()),
    )
    val ticks = 36
    for (index in 0 until ticks) {
        val angle = (index / ticks.toFloat()) * 2f * PI + rotation * (PI.toFloat() / 180f)
        val major = index % 6 == 0
        val outer = radius * 1.065f
        val inner = outer - if (major) 5.dp.toPx() else 2.dp.toPx()
        val cosA = cos(angle.toDouble()).toFloat()
        val sinA = sin(angle.toDouble()).toFloat()
        drawLine(
            color = tickColor.copy(alpha = if (major) 0.20f else 0.10f),
            start = Offset(cx + cosA * inner, cy + sinA * inner),
            end = Offset(cx + cosA * outer, cy + sinA * outer),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}

/** Chamfered octagon used for every plate. */
private fun octagon(cx: Float, cy: Float, w: Float, h: Float, chamfer: Float): Path {
    val left = cx - w / 2f
    val right = cx + w / 2f
    val top = cy - h / 2f
    val bottom = cy + h / 2f
    return Path().apply {
        moveTo(left + chamfer, top)
        lineTo(right - chamfer, top)
        lineTo(right, top + chamfer)
        lineTo(right, bottom - chamfer)
        lineTo(right - chamfer, bottom)
        lineTo(left + chamfer, bottom)
        lineTo(left, bottom - chamfer)
        lineTo(left, top + chamfer)
        close()
    }
}