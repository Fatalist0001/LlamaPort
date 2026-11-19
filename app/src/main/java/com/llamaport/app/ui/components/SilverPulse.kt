package com.llamaport.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens

/**
 * One local confirmation pulse: a thin silver ring expands once from the
 * triggering component and fades out. It never covers the screen, never blocks
 * input, and does nothing under reduced motion.
 *
 * Pass an incrementing [trigger]; the overlay is a no-op while idle.
 */
@Composable
fun SilverPulseOverlay(
    trigger: Int,
    color: Color,
    modifier: Modifier = Modifier,
    maxScale: Float = 1.35f,
) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0 && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.Durations.PULSE,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            )
        }
    }
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val fade = (1f - p) * (1f - p)
        val base = size.minDimension * 0.5f
        val radius = base * (0.55f + (maxScale - 0.55f) * p)
        drawCircle(
            color = color.copy(alpha = 0.55f * fade),
            radius = radius,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.16f * fade),
                    Color.Transparent,
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = radius * 1.25f,
            ),
            radius = radius * 1.25f,
            center = Offset(size.width / 2f, size.height / 2f),
        )
    }
}

/**
 * A thin moving reflection line used while a model loads. It is deliberately
 * indeterminate: the native layer exposes no real percentage.
 */
@Composable
fun SilverShimmerLine(
    modifier: Modifier = Modifier,
    color: Color,
    height: Float = 1.5f,
) {
    val reduced = LocalReducedMotion.current
    val phase = if (reduced) {
        null
    } else {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
            .animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(
                        durationMillis = 1500,
                        easing = MotionTokens.Easing.Line,
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
                ),
                label = "shimmerPhase",
            )
    }
    Canvas(modifier) {
        val h = height.dp.toPx().coerceAtMost(size.height)
        val top = (size.height - h) / 2f
        drawRect(
            color = color.copy(alpha = 0.08f),
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
        )
        if (phase == null) {
            drawRect(
                color = color.copy(alpha = 0.4f),
                topLeft = Offset(size.width * 0.3f, top),
                size = Size(size.width * 0.4f, h),
            )
        } else {
            // Draw-phase read: the line animates without recomposing its host.
            val p = phase.value
            val band = size.width * 0.32f
            val left = -band + (size.width + band * 2f) * p
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                    startX = left,
                    endX = left + band,
                ),
                topLeft = Offset(left, top),
                size = Size(band, h),
            )
        }
    }
}