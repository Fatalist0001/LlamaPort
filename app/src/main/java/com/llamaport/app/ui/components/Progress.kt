package com.llamaport.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.motion.LocalReducedMotion
import com.llamaport.app.ui.motion.MotionTokens

/**
 * Silver progress line. A non-null [progress] is a real, determinate value
 * (prompt processing); null is an honest indeterminate state (model load).
 */
@Composable
fun ObsidianProgressLine(
    progress: Float?,
    modifier: Modifier = Modifier,
    trackColor: Color,
    accent: Color,
    heightDp: Int = 2,
) {
    val reduced = LocalReducedMotion.current
    val animated by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = if (reduced) {
            snap()
        } else {
            tween(MotionTokens.Durations.SHORT, easing = MotionTokens.Easing.Emphasized)
        },
        label = "progressLine",
    )
    val phase = if (progress == null && !reduced) {
        rememberInfiniteTransition(label = "progressIndeterminate").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "progressIndeterminatePhase",
        )
    } else {
        null
    }

    Canvas(modifier) {
        val h = heightDp.dp.toPx().coerceAtMost(size.height)
        val y = (size.height - h) / 2f
        val radius = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, y),
            size = Size(size.width, h),
            cornerRadius = radius,
        )
        if (progress == null) {
            if (phase == null) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.45f),
                    topLeft = Offset(size.width * 0.28f, y),
                    size = Size(size.width * 0.44f, h),
                    cornerRadius = radius,
                )
            } else {
                // Draw-phase read: drift without recomposing the host.
                val p = phase.value
                val band = size.width * 0.38f
                val left = -band + (size.width + band * 2f) * p
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            accent.copy(alpha = 0.75f),
                            Color.Transparent,
                        ),
                        startX = left,
                        endX = left + band,
                    ),
                    topLeft = Offset(left, y),
                    size = Size(band, h),
                    cornerRadius = radius,
                )
            }
        } else {
            val width = size.width * animated
            if (width > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.55f),
                            accent,
                        ),
                    ),
                    topLeft = Offset(0f, y),
                    size = Size(width, h),
                    cornerRadius = radius,
                )
            }
        }
    }
}