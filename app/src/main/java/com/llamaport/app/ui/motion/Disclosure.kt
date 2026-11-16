package com.llamaport.app.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/**
 * Shared enter/exit for collapsible content (settings sections, reasoning
 * blocks, tool details). Without reduced motion the container expands and
 * fades; with reduced motion only a short fade remains.
 */
@Composable
fun disclosureEnter(): EnterTransition = if (LocalReducedMotion.current) {
    fadeIn(tween(MotionTokens.Durations.FADE_REDUCED))
} else {
    expandVertically(
        animationSpec = tween(
            MotionTokens.Durations.ENTER,
            easing = MotionTokens.Easing.EmphasizedDecelerate,
        ),
        expandFrom = Alignment.Top,
    ) + fadeIn(tween(MotionTokens.Durations.ENTER))
}

@Composable
fun disclosureExit(): ExitTransition = if (LocalReducedMotion.current) {
    fadeOut(tween(MotionTokens.Durations.FADE_REDUCED))
} else {
    shrinkVertically(
        animationSpec = tween(
            MotionTokens.Durations.SHORT,
            easing = MotionTokens.Easing.EmphasizedAccelerate,
        ),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(tween(MotionTokens.Durations.SHORT))
}
