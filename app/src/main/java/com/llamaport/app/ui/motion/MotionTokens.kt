package com.llamaport.app.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Single source of truth for motion: durations, easings, springs and stagger.
 * Widgets must not contain raw animation numbers — they come from here.
 */
object MotionTokens {

    /** Durations in milliseconds. */
    object Durations {
        /** Very short state changes: switches, chips, icon color swaps. */
        const val MICRO = 150

        /** Short state changes that also move something (size, position). */
        const val SHORT = 250

        /** Enter/exit of cards, list items, sheet content blocks. */
        const val ENTER = 300

        /** Screen-level and container transitions. */
        const val TRANSITION = 400

        /** The only animation kept when the user disabled system animations:
         *  a quick fade so state changes remain readable. */
        const val FADE_REDUCED = 120
    }

    /** Material 3 emphasized easing family. */
    object Easing {
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }

    /** Spring configs. Springs are used where motion should feel physical. */
    object Springs {
        /** Button/card press release. */
        val Press = spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )

        /** Message bubbles popping in. */
        val Pop = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )

        /** Gentle continuous motion (breathing, pulse). */
        val Gentle = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessVeryLow,
        )
    }

    /** Press scale applied by [pressable]. */
    const val PRESSED_SCALE = 0.96f

    /** Stagger delay between list elements, per index. */
    const val STAGGER_STEP_MS = 40

    /** Maximum total stagger delay so long lists do not wait forever. */
    const val STAGGER_MAX_MS = 400

    /** Stagger delay for element [index]. */
    fun staggerDelay(index: Int): Int =
        (index * STAGGER_STEP_MS).coerceAtMost(STAGGER_MAX_MS)
}
