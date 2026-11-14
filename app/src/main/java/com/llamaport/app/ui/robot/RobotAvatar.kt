package com.llamaport.app.ui.robot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.llamaport.app.ui.AppIcons
import com.llamaport.app.ui.motion.LocalReducedMotion

/** Rasterized logo grid, computed once and shared by all avatars. */
private var cachedGrid: RobotGrid? = null

/** White pixel mask of the logo, drawn once and tinted per theme. */
private var cachedMask: ImageBitmap? = null

@Composable
internal fun rememberRobotGrid(): RobotGrid {
    val painter = rememberVectorPainter(AppIcons.Robot)
    return remember(painter) {
        cachedGrid ?: RobotGrid.rasterize(painter).also { cachedGrid = it }
    }
}

@Composable
private fun rememberRobotMask(): ImageBitmap {
    val grid = rememberRobotGrid()
    return remember(grid) {
        cachedMask ?: run {
            val bitmap = ImageBitmap(grid.size, grid.size)
            val canvas = GraphicsCanvas(bitmap)
            val paint = Paint().apply { color = Color.White }
            for (y in 0 until grid.size) {
                for (x in 0 until grid.size) {
                    if (!grid.isCovered[y][x] || grid.isHole[y][x]) continue
                    canvas.drawRect(
                        left = x.toFloat(),
                        top = y.toFloat(),
                        right = x + 1f,
                        bottom = y + 1f,
                        paint = paint,
                    )
                }
            }
            bitmap.also { cachedMask = it }
        }
    }
}

/**
 * Tiny static robot avatar for chat messages.
 *
 * The logo grid is pre-rendered once into a 24x24 pixel mask and drawn as a
 * single image with nearest-neighbour filtering, so a long chat full of
 * avatars costs one draw call each. Only a message that is currently thinking
 * gets a slow brightness pulse, applied on the layer so it never recomposes.
 */
@Composable
fun RobotAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    thinking: Boolean = false,
) {
    val mask = rememberRobotMask()
    val reduced = LocalReducedMotion.current
    val pulse = if (thinking && !reduced) {
        val infinite = rememberInfiniteTransition(label = "robotAvatar")
        val value by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 720, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "robotAvatarPulse",
        )
        value
    } else {
        1f
    }
    val tint = MaterialTheme.colorScheme.primary
    val tintFilter = remember(tint) { ColorFilter.tint(tint) }

    Canvas(
        modifier
            .size(size)
            .graphicsLayer { alpha = pulse.coerceIn(0f, 1f) },
    ) {
        drawImage(
            image = mask,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(
                this.size.width.toInt(),
                this.size.height.toInt(),
            ),
            filterQuality = FilterQuality.None,
            colorFilter = tintFilter,
        )
    }
}