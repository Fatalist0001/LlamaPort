package com.llamaport.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One corner system for the whole app. Widgets take radii from
 * [MaterialTheme.shapes] instead of hard-coding numbers; the special shapes
 * below cover pills and the chat bubble tail.
 */
val LlamaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Fully rounded capsule for badges and counters. */
val PillShape: Shape = RoundedCornerShape(percent = 50)

/** User bubble: round on three sides, small tail corner at the bottom end. */
val ChatBubbleShape: Shape = RoundedCornerShape(
    topStart = 22.dp,
    topEnd = 22.dp,
    bottomStart = 22.dp,
    bottomEnd = 8.dp,
)
