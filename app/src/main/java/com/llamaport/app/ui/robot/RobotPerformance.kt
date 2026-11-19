package com.llamaport.app.ui.robot

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The eye language of the pixel robot. */
internal enum class EyeExpression {
    Open,
    Closed,
    Happy,
    Sad,
    Angry,
    Wide,
    Squint,
    WinkLeft,
    WinkRight,
    Cross,
}

/** The mouth language of the pixel robot. */
internal enum class MouthExpression {
    Neutral,
    Smile,
    Frown,
    Open,
    O,
    Smirk,
    Flat,
}

/**
 * One immutable frame of the robot performance. Offsets are expressed in grid
 * cells so drawing is resolution independent.
 */
@Immutable
internal data class RobotPose(
    val eyes: EyeExpression = EyeExpression.Open,
    val pupilX: Float = 0f,
    val pupilY: Float = 0f,
    val mouth: MouthExpression = MouthExpression.Neutral,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val antenna: Float = 0f,
    val glitch: Float = 0f,
    val brightness: Float = 1f,
)

/**
 * Idle behaviours. Weights are kept separate for a loaded model and the dimmed
 * "no model" state, so the robot stays calm when it has nothing to serve while
 * still performing the full vocabulary of gestures.
 */
internal enum class IdleAction(
    val durationMs: Int,
    val weight: Int,
    val sleepyWeight: Int,
) {
    Blink(180, 14, 14),
    DoubleBlink(380, 6, 5),
    LookLeft(700, 8, 5),
    LookRight(700, 8, 5),
    LookUp(650, 6, 4),
    LookDown(650, 6, 7),
    LookAround(1100, 5, 4),
    EyeRoll(900, 4, 3),
    Squint(650, 5, 3),
    Happy(700, 6, 3),
    Wink(500, 4, 2),
    Sad(900, 3, 6),
    Angry(650, 2, 1),
    Curious(800, 6, 4),
    Nod(600, 5, 4),
    ShakeHead(700, 3, 2),
    Yawn(950, 2, 8),
    AntennaTwitch(550, 5, 3),
    Glitch(450, 2, 2),
    Sway(1200, 6, 10),
}

/** Reactions invoked by a tap. */
internal enum class TapReaction(
    val durationMs: Int,
    val weight: Int,
) {
    Hop(480, 10),
    Giggle(760, 8),
    Wink(520, 6),
    LookAtTap(720, 12),
    Surprised(600, 6),
    Dizzy(900, 4),
    Annoyed(700, 4),
    Shy(800, 5),
    Laugh(900, 6),
    No(620, 3),
    Yes(620, 6),
    RollEyes(780, 4),
    Glitch(500, 2),
    Sleepy(950, 3),
    Spark(520, 5),
    Tilt(720, 8),
}

/**
 * Catalog, timing and weighted selection for the robot performance. Pure
 * functions live here so the renderer only has to draw a pose.
 */
internal object RobotPerformance {

    const val TICK_MS = 60L

    private const val PI_F = PI.toFloat()
    private const val TWO_PI = (PI * 2.0).toFloat()

    fun ticksFor(milliseconds: Int): Int =
        ceil(milliseconds / TICK_MS.toFloat()).toInt().coerceAtLeast(1)

    fun firstDelayTicks(random: Random): Int = random.nextInt(16, 37)

    fun pauseTicks(random: Random, sleeping: Boolean): Int =
        if (sleeping) random.nextInt(50, 121) else random.nextInt(36, 91)

    fun reactionResumeTicks(random: Random): Int = random.nextInt(30, 61)

    /**
     * Returns a whole shuffled cycle of idle gestures. Every one of the 20
     * behaviours appears once before any repeats, while the weights only
     * decide the order: calm/sleepy gestures tend to come earlier when the
     * robot has no model.
     */
    fun idleCycle(random: Random, sleeping: Boolean): List<IdleAction> =
        weightedShuffle(IdleAction.entries, random) {
            if (sleeping) it.sleepyWeight else it.weight
        }

    /** A whole shuffled cycle of tap reactions, again without repeats. */
    fun reactionCycle(random: Random): List<TapReaction> =
        weightedShuffle(TapReaction.entries, random) { it.weight }

    private fun <T> weightedShuffle(
        source: List<T>,
        random: Random,
        weight: (T) -> Int,
    ): List<T> {
        val remaining = source.toMutableList()
        val result = ArrayList<T>(remaining.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { weight(it).coerceAtLeast(1) }
            var roll = random.nextInt(total)
            var chosen = remaining.lastIndex
            for (index in remaining.indices) {
                roll -= weight(remaining[index]).coerceAtLeast(1)
                if (roll < 0) {
                    chosen = index
                    break
                }
            }
            result += remaining.removeAt(chosen)
        }
        return result
    }

    // ------------------------------------------------------------------ idle

    fun idlePose(action: IdleAction, progress: Float): RobotPose {
        val t = progress.coerceIn(0f, 1f)
        val bell = sin(PI_F * t).toFloat()
        val oscillate = sin(TWO_PI * t).toFloat()
        return when (action) {
            IdleAction.Blink -> RobotPose(
                eyes = if (t in 0.22f..0.72f) EyeExpression.Closed else EyeExpression.Open,
            )

            IdleAction.DoubleBlink -> RobotPose(
                eyes = if (t in 0.12f..0.30f || t in 0.55f..0.73f) {
                    EyeExpression.Closed
                } else {
                    EyeExpression.Open
                },
            )

            IdleAction.LookLeft -> RobotPose(
                pupilX = -0.9f,
                offsetX = -0.5f * bell,
                rotation = -2.2f * bell,
            )

            IdleAction.LookRight -> RobotPose(
                pupilX = 0.9f,
                offsetX = 0.5f * bell,
                rotation = 2.2f * bell,
            )

            IdleAction.LookUp -> RobotPose(
                pupilY = -0.9f,
                offsetY = -0.55f * bell,
                scale = 1f + 0.01f * bell,
            )

            IdleAction.LookDown -> RobotPose(
                pupilY = 0.9f,
                offsetY = 0.55f * bell,
            )

            IdleAction.LookAround -> RobotPose(
                pupilX = when {
                    t < 0.28f -> -1f
                    t < 0.52f -> 0f
                    t < 0.78f -> 1f
                    else -> 0f
                },
                offsetX = 0.25f * oscillate,
                mouth = MouthExpression.Smile,
            )

            IdleAction.EyeRoll -> RobotPose(
                pupilX = cos(TWO_PI * t),
                pupilY = sin(TWO_PI * t),
                mouth = MouthExpression.Flat,
                rotation = 2f * sin(TWO_PI * t).toFloat(),
            )

            IdleAction.Squint -> RobotPose(
                eyes = EyeExpression.Squint,
                mouth = MouthExpression.Flat,
                rotation = -2f * bell,
                offsetY = 0.25f * bell,
            )

            IdleAction.Happy -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Smile,
                offsetY = -1.8f * bell,
                scale = 1f + 0.03f * bell,
                antenna = 0.8f * bell,
            )

            IdleAction.Wink -> RobotPose(
                eyes = if (t in 0.15f..0.80f) EyeExpression.WinkRight else EyeExpression.Open,
                mouth = MouthExpression.Smirk,
                rotation = 2f * bell,
            )

            IdleAction.Sad -> RobotPose(
                eyes = EyeExpression.Sad,
                mouth = MouthExpression.Frown,
                offsetY = 0.7f * bell,
                rotation = -2f * bell,
                brightness = 0.84f,
            )

            IdleAction.Angry -> RobotPose(
                eyes = EyeExpression.Angry,
                mouth = MouthExpression.Frown,
                offsetX = 0.35f * sin(TWO_PI * 2f * t).toFloat(),
                rotation = 1.4f * sin(TWO_PI * 2f * t).toFloat(),
                antenna = 0.35f,
            )

            IdleAction.Curious -> RobotPose(
                pupilX = -0.8f,
                pupilY = -0.8f,
                mouth = MouthExpression.O,
                rotation = 5f * bell,
                offsetX = -0.2f * bell,
                antenna = 1f,
            )

            IdleAction.Nod -> RobotPose(
                eyes = if (t in 0.34f..0.44f || t in 0.74f..0.84f) {
                    EyeExpression.Closed
                } else {
                    EyeExpression.Open
                },
                mouth = MouthExpression.Smile,
                offsetY = 1.1f * sin(TWO_PI * 2f * t).toFloat() * (1f - 0.35f * t),
            )

            IdleAction.ShakeHead -> RobotPose(
                mouth = MouthExpression.Flat,
                rotation = 4.5f * sin(TWO_PI * 2f * t).toFloat() * (1f - 0.2f * t),
                offsetX = 0.45f * sin(TWO_PI * 2f * t).toFloat(),
            )

            IdleAction.Yawn -> RobotPose(
                eyes = if (t in 0.08f..0.86f) EyeExpression.Closed else EyeExpression.Open,
                mouth = MouthExpression.Open,
                scale = 1f + 0.035f * bell,
                offsetY = 0.35f * bell,
                antenna = 0.25f,
                brightness = 0.92f,
            )

            IdleAction.AntennaTwitch -> RobotPose(
                pupilY = -0.8f,
                mouth = MouthExpression.Smile,
                antenna = triangleWave(t * 3f),
            )

            IdleAction.Glitch -> RobotPose(
                eyes = if (t in 0.1f..0.65f) EyeExpression.Wide else EyeExpression.Open,
                mouth = MouthExpression.Flat,
                offsetX = 0.45f * sin(TWO_PI * 4f * t).toFloat() * (1f - t),
                glitch = if (t in 0.1f..0.65f) 1f else 0f,
                brightness = 0.94f,
            )

            IdleAction.Sway -> RobotPose(
                pupilX = 0.25f * oscillate,
                mouth = MouthExpression.Smile,
                offsetX = 1.2f * oscillate,
                rotation = 2f * oscillate,
                antenna = 0.2f,
            )
        }
    }

    // -------------------------------------------------------------- reactions

    fun reactionPose(
        reaction: TapReaction,
        progress: Float,
        tapX: Float,
        tapY: Float,
    ): RobotPose {
        val t = progress.coerceIn(0f, 1f)
        val bell = sin(PI_F * t).toFloat()
        val nx = tapX.coerceIn(-1f, 1f)
        val ny = tapY.coerceIn(-1f, 1f)
        return when (reaction) {
            TapReaction.Hop -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Smile,
                offsetY = -2.4f * bell,
                scale = 1f + 0.04f * bell,
                antenna = 0.8f * bell,
            )

            TapReaction.Giggle -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Open,
                offsetY = -1.5f * abs(sin(TWO_PI * 2f * t)).toFloat() * (1f - 0.3f * t),
                scale = 1f + 0.025f * abs(sin(PI_F * t)).toFloat(),
            )

            TapReaction.Wink -> RobotPose(
                eyes = if (t in 0.12f..0.82f) EyeExpression.WinkRight else EyeExpression.Open,
                mouth = MouthExpression.Smirk,
                rotation = 2.5f * bell,
            )

            TapReaction.LookAtTap -> RobotPose(
                pupilX = nx * 1.1f,
                pupilY = ny * 1.1f,
                mouth = MouthExpression.Smile,
                rotation = nx * 5f * bell,
                offsetX = nx * 0.6f * bell,
                offsetY = ny * 0.3f * bell,
            )

            TapReaction.Surprised -> RobotPose(
                eyes = EyeExpression.Wide,
                mouth = MouthExpression.O,
                offsetY = -0.5f * bell,
                scale = 1f + 0.07f * bell,
                antenna = 1f,
            )

            TapReaction.Dizzy -> RobotPose(
                pupilX = cos(TWO_PI * 1.5f * t),
                pupilY = sin(TWO_PI * 1.5f * t),
                mouth = MouthExpression.Open,
                rotation = 6f * sin(TWO_PI * 2f * t).toFloat() * (1f - 0.4f * t),
                brightness = 0.95f,
            )

            TapReaction.Annoyed -> RobotPose(
                eyes = EyeExpression.Squint,
                mouth = MouthExpression.Frown,
                offsetX = 0.3f * sin(TWO_PI * 2f * t).toFloat(),
                rotation = 3f * sin(TWO_PI * 2f * t).toFloat(),
                antenna = 0.2f,
            )

            TapReaction.Shy -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Smile,
                offsetY = 0.8f * bell,
                scale = 1f - 0.04f * bell,
                rotation = -1.5f * bell,
                brightness = 0.9f,
            )

            TapReaction.Laugh -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Open,
                offsetY = -1.8f * abs(sin(TWO_PI * 3f * t)).toFloat() * (1f - 0.25f * t),
                scale = 1f + 0.035f * abs(sin(PI_F * t)).toFloat(),
                antenna = 0.6f,
            )

            TapReaction.No -> RobotPose(
                eyes = EyeExpression.Open,
                mouth = MouthExpression.Flat,
                rotation = 5f * sin(TWO_PI * 2f * t).toFloat(),
                offsetX = 0.5f * sin(TWO_PI * 2f * t).toFloat(),
            )

            TapReaction.Yes -> RobotPose(
                eyes = EyeExpression.Happy,
                mouth = MouthExpression.Smile,
                offsetY = 1f * sin(TWO_PI * 2f * t).toFloat(),
            )

            TapReaction.RollEyes -> RobotPose(
                pupilX = cos(TWO_PI * t),
                pupilY = sin(TWO_PI * t),
                mouth = MouthExpression.Flat,
            )

            TapReaction.Glitch -> RobotPose(
                eyes = EyeExpression.Wide,
                mouth = MouthExpression.Flat,
                offsetX = 0.8f * sin(TWO_PI * 5f * t).toFloat() * (1f - t),
                glitch = 1f,
                brightness = 0.94f,
            )

            TapReaction.Sleepy -> RobotPose(
                eyes = if (t > 0.25f) EyeExpression.Closed else EyeExpression.Open,
                mouth = MouthExpression.Neutral,
                offsetY = 1.2f * bell,
                antenna = 0f,
                brightness = 0.8f,
            )

            TapReaction.Spark -> RobotPose(
                eyes = EyeExpression.Wide,
                mouth = MouthExpression.O,
                offsetY = -1f * bell,
                scale = 1f + 0.05f * bell,
                antenna = 1f,
            )

            TapReaction.Tilt -> RobotPose(
                pupilX = nx,
                pupilY = ny,
                mouth = MouthExpression.Smile,
                rotation = 6f * bell,
                offsetX = nx * 0.4f * bell,
                antenna = 0.3f * bell,
            )
        }
    }

    private fun triangleWave(value: Float): Float {
        val phase = value % 1f
        return 1f - abs(2f * phase - 1f)
    }
}
