package com.llamaport.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * One corner system for the whole app. Widgets take radii from
 * [MaterialTheme.shapes] instead of hard-coding numbers; the few special
 * shapes below cover the brand motif and the chat bubble tail.
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

/**
 * Pixel motif: corners clipped as a two-step staircase. Used by the model
 * chip in the top bar and the other chip-like readouts, so the pixel accent
 * appears in one consistent place.
 */
class PixelChipShape(private val step: Dp = 3.dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val s = with(density) { step.toPx() }
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (w < s * 4f || h < s * 4f) {
                // Too small for the motif: fall back to a plain rounded box.
                addRoundRect(RoundRect(Rect(0f, 0f, w, h), s, s))
                return@apply
            }
            moveTo(2f * s, 0f)
            lineTo(w - 2f * s, 0f)
            lineTo(w - 2f * s, s)
            lineTo(w - s, s)
            lineTo(w - s, 2f * s)
            lineTo(w, 2f * s)
            lineTo(w, h - 2f * s)
            lineTo(w - s, h - 2f * s)
            lineTo(w - s, h - s)
            lineTo(w - 2f * s, h - s)
            lineTo(w - 2f * s, h)
            lineTo(2f * s, h)
            lineTo(2f * s, h - s)
            lineTo(s, h - s)
            lineTo(s, h - 2f * s)
            lineTo(0f, h - 2f * s)
            lineTo(0f, 2f * s)
            lineTo(s, 2f * s)
            lineTo(s, s)
            lineTo(2f * s, s)
            close()
        }
        return Outline.Generic(path)
    }
}

/** The brand pixel chip uses a 3 dp stair. */
val ModelChipShape: Shape = PixelChipShape(3.dp)

/** Smaller instances of the same motif (chat list badge) use a 2 dp stair. */
val SmallModelChipShape: Shape = PixelChipShape(2.dp)
