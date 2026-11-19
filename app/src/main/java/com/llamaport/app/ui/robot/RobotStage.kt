package com.llamaport.app.ui.robot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.theme.ObsidianTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Static Obsidian backdrop for the home hero robot: one very weak silver
 * reflection behind it, a thin precision ring with tick marks and a hairline
 * "floor". Nothing here animates — the robot itself is the living object, the
 * stage only frames it. This keeps the home screen free of continuous 60 fps
 * work while preserving the Obsidian character.
 */
@Composable
internal fun RobotStage(
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = ObsidianTheme.palette
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val base = this.size.minDimension
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f

            // Ambient reflection: barely-there silver behind the robot.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.silver.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = base * 0.52f,
                ),
                radius = base * 0.52f,
                center = Offset(cx, cy),
            )

            // Precision ring with tick marks — a technical, not a "glowing AI
            // orb" frame.
            val ringRadius = base * 0.44f
            drawCircle(
                color = palette.silverDim.copy(alpha = 0.16f),
                radius = ringRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = palette.silverDim.copy(alpha = 0.09f),
                radius = ringRadius * 1.06f,
                center = Offset(cx, cy),
                style = Stroke(width = 0.7.dp.toPx()),
            )
            val ticks = 36
            for (index in 0 until ticks) {
                val angle = (index / ticks.toFloat()) * 2.0 * Math.PI
                val major = index % 6 == 0
                val outer = ringRadius * 1.065f
                val inner = outer - (if (major) 5.dp.toPx() else 2.dp.toPx())
                drawLine(
                    color = palette.silverDim.copy(alpha = if (major) 0.20f else 0.10f),
                    start = Offset(
                        cx + cos(angle).toFloat() * inner,
                        cy + sin(angle).toFloat() * inner,
                    ),
                    end = Offset(
                        cx + cos(angle).toFloat() * outer,
                        cy + sin(angle).toFloat() * outer,
                    ),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            // Floor: a thin hairline the robot hovers over, fading at the ends.
            val floorY = cy + base * 0.40f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.silverDim.copy(alpha = 0.28f),
                        Color.Transparent,
                    ),
                    startX = cx - base * 0.30f,
                    endX = cx + base * 0.30f,
                ),
                topLeft = Offset(cx - base * 0.30f, floorY),
                size = androidx.compose.ui.geometry.Size(base * 0.60f, 1.dp.toPx()),
            )
        }
        content()
    }
}