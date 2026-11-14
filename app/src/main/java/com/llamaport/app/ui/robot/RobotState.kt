package com.llamaport.app.ui.robot

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/** Robot mood reflected by the logo. */
enum class RobotState {
    /** No model loaded: eyes closed, dimmed, slow brightness "breathing". */
    Sleeping,

    /** Model is being loaded: eye sockets fill bottom-up by [AsciiRobot.progress]. */
    Loading,

    /** Model ready: occasional blink, rare cell flicker. */
    Idle,

    /** Generating a plan: eyes dart around, antenna blinks. */
    Thinking,

    /** Streaming tokens: the "mouth" row pulses with each token. */
    Generating,

    /** Load/generation error: cross eyes, short shake. */
    Error,
}

/**
 * The robot logo converted to a cell matrix. [AppIcons.Robot] stays the single
 * source of truth: it is rasterized once into coverage values, then eye
 * sockets are auto-detected as enclosed holes.
 */
internal class RobotGrid(
    val size: Int,
    /** 0..1 silhouette coverage per cell (from the vector raster). */
    val coverage: Array<FloatArray>,
    /** Enclosed holes in the silhouette — the eye sockets. */
    val isHole: Array<BooleanArray>,
    /** Antenna cells (top of the head), for the Thinking blink. */
    val isAntenna: Array<BooleanArray>,
    /** Bounding boxes of the hole clusters (eye sockets), left to right. */
    val eyeRegions: List<IntRect>,
    /** Mouth row used by Generating pulses. */
    val mouthRow: Int,
    /** Mouth columns used by Generating pulses. */
    val mouthCols: IntRange,
    /** Stable per-cell noise in 0..1, for scramble/flicker. */
    val noise: Array<FloatArray>,
) {
    val isCovered: Array<BooleanArray> =
        Array(size) { y -> BooleanArray(size) { x -> coverage[y][x] >= COVER_THRESHOLD } }

    fun cellNoise(x: Int, y: Int): Float = noise[y][x]

    companion object {
        const val COVER_THRESHOLD = 0.45f

        /**
         * Rasterizes [painter] (the vector logo) into a [gridSize] x [gridSize]
         * cell matrix with [ss]x supersampling, finds enclosed holes (eyes),
         * the antenna region and the mouth row.
         */
        fun rasterize(painter: VectorPainter, gridSize: Int = 24, ss: Int = 4): RobotGrid {
            val px = gridSize * ss
            val bitmap = ImageBitmap(px, px)
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(bitmap),
                size = Size(px.toFloat(), px.toFloat()),
            ) {
                with(painter) {
                    draw(size = Size(px.toFloat(), px.toFloat()))
                }
            }
            val pixels = bitmap.toPixelMap()

            val coverage = Array(gridSize) { y ->
                FloatArray(gridSize) { x ->
                    var hits = 0
                    for (sy in 0 until ss) {
                        for (sx in 0 until ss) {
                            if (pixels[x * ss + sx, y * ss + sy].alpha > 0.25f) hits++
                        }
                    }
                    hits.toFloat() / (ss * ss)
                }
            }
            val covered = Array(gridSize) { y ->
                BooleanArray(gridSize) { x -> coverage[y][x] >= COVER_THRESHOLD }
            }

            // Flood fill from the border over uncovered cells; whatever stays
            // unreachable is an enclosed hole of the silhouette (the eyes).
            val reachable = Array(gridSize) { BooleanArray(gridSize) }
            val queue = ArrayDeque<Int>()
            fun visit(x: Int, y: Int) {
                if (x < 0 || y < 0 || x >= gridSize || y >= gridSize) return
                if (covered[y][x] || reachable[y][x]) return
                reachable[y][x] = true
                queue.addLast(y * gridSize + x)
            }
            for (i in 0 until gridSize) {
                visit(i, 0)
                visit(i, gridSize - 1)
                visit(0, i)
                visit(gridSize - 1, i)
            }
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                val x = cell % gridSize
                val y = cell / gridSize
                visit(x + 1, y)
                visit(x - 1, y)
                visit(x, y + 1)
                visit(x, y - 1)
            }
            val isHole = Array(gridSize) { y ->
                BooleanArray(gridSize) { x -> !covered[y][x] && !reachable[y][x] }
            }

            // Cluster holes into eye regions.
            val seen = Array(gridSize) { BooleanArray(gridSize) }
            val eyeRegions = mutableListOf<IntRect>()
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    if (!isHole[y][x] || seen[y][x]) continue
                    var l = x
                    var t = y
                    var r = x
                    var b = y
                    val cells = ArrayDeque<Int>()
                    seen[y][x] = true
                    cells.addLast(y * gridSize + x)
                    while (cells.isNotEmpty()) {
                        val c = cells.removeFirst()
                        val cx = c % gridSize
                        val cy = c / gridSize
                        l = minOf(l, cx)
                        t = minOf(t, cy)
                        r = maxOf(r, cx)
                        b = maxOf(b, cy)
                        for (d in intArrayOf(1, -1)) {
                            val nx = cx + d
                            val ny = cy + d
                            if (nx in 0 until gridSize && isHole[cy][nx] && !seen[cy][nx]) {
                                seen[cy][nx] = true
                                cells.addLast(cy * gridSize + nx)
                            }
                            if (ny in 0 until gridSize && isHole[ny][cx] && !seen[ny][cx]) {
                                seen[ny][cx] = true
                                cells.addLast(ny * gridSize + cx)
                            }
                        }
                    }
                    eyeRegions += IntRect(l, t, r, b)
                }
            }
            eyeRegions.sortBy { it.left }

            // Antenna: covered cells in the top rows of the grid.
            val antennaTop = gridSize / 8 // 3 for a 24 grid
            val isAntenna = Array(gridSize) { y ->
                BooleanArray(gridSize) { x ->
                    y <= antennaTop && covered[y][x] &&
                        eyeRegions.none { x >= it.left - 2 && x <= it.right + 2 }
                }
            }

            // Mouth: centered between the eyes, a couple of rows below them.
            val mouthRow: Int
            val mouthCols: IntRange
            if (eyeRegions.size >= 2) {
                val bottom = eyeRegions.maxOf { it.bottom }
                mouthRow = (bottom + 3).coerceAtMost(gridSize - 2)
                val center0 = (eyeRegions[0].left + eyeRegions[0].right) / 2
                val center1 = (eyeRegions[1].left + eyeRegions[1].right) / 2
                val mid = (center0 + center1) / 2
                mouthCols = (mid - 2)..(mid + 3)
            } else {
                mouthRow = gridSize * 3 / 5
                mouthCols = (gridSize / 2 - 3)..(gridSize / 2 + 2)
            }

            // Stable per-cell noise (hash based, no RNG state).
            val noise = Array(gridSize) { y ->
                FloatArray(gridSize) { x ->
                    val h = cellHash(x, y, 0)
                    (h % 1000) / 1000f
                }
            }

            return RobotGrid(
                size = gridSize,
                coverage = coverage,
                isHole = isHole,
                isAntenna = isAntenna,
                eyeRegions = eyeRegions,
                mouthRow = mouthRow,
                mouthCols = mouthCols,
                noise = noise,
            )
        }

        fun cellHash(x: Int, y: Int, f: Int): Int {
            val v = (x * 73856093) xor (y * 19349663) xor (f * 83492791)
            return abs(v)
        }
    }
}
