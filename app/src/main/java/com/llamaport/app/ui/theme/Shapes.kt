package com.llamaport.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One corner system for the whole app:
 *   10 dp — inline atoms, tags, tiny fields;
 *   14 dp — chips, buttons, inputs;
 *   18 dp — cards and list rows;
 *   24 dp — large panels and settings sections;
 *   28 dp — sheets and the memory dialog.
 */
val LlamaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Fully rounded capsule for status dots, counters and speed chips. */
val PillShape: Shape = RoundedCornerShape(percent = 50)

/** User bubble: round on three sides, one tighter corner at the bottom end. */
val ChatBubbleShape: Shape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 7.dp,
)

/** Cards use the medium radius; keep a named alias for readability. */
val CardShape: Shape = RoundedCornerShape(18.dp)

/** Large technical panels (settings sections, RAM summary). */
val PanelShape: Shape = RoundedCornerShape(24.dp)

/** Code and tool output surfaces. */
val CodeShape: Shape = RoundedCornerShape(14.dp)