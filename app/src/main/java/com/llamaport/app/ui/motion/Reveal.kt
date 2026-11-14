package com.llamaport.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Entrance animation: fade + slide up + scale up, with an optional stagger
 * via [index] (see [MotionTokens.staggerDelay]).
 *
 * With reduced motion only a short fade is applied — no movement.
 */
@Composable
fun Reveal(
    modifier: Modifier = Modifier,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        val duration = if (reduced) {
            MotionTokens.Durations.FADE_REDUCED
        } else {
            MotionTokens.Durations.ENTER
        }
        val delay = if (reduced) 0 else MotionTokens.staggerDelay(index)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = duration,
                delayMillis = delay,
                easing = MotionTokens.Easing.EmphasizedDecelerate,
            ),
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            if (!reduced) {
                translationY = (1f - p) * 16.dp.toPx()
                val scale = 0.92f + 0.08f * p
                scaleX = scale
                scaleY = scale
            }
        },
    ) {
        content()
    }
}
