package com.llamaport.app.ui.motion

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Number that animates smoothly between values (RAM, tokens/s, sizes).
 * With reduced motion the value switches instantly.
 */
@Composable
fun AnimatedNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    format: (Int) -> String = Int::toString,
) {
    val reduced = LocalReducedMotion.current
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = if (reduced) {
            snap()
        } else {
            tween(
                durationMillis = MotionTokens.Durations.SHORT,
                easing = MotionTokens.Easing.Emphasized,
            )
        },
        label = "animatedNumberInt",
    )
    Text(
        text = format(animated),
        style = style,
        modifier = modifier,
    )
}
