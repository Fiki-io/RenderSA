package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object RenderSAMotion {
    const val DURATION_FAST_MS = 135
    const val DURATION_STANDARD_MS = 220
    const val DURATION_LONG_MS = 320

    const val PRESS_SCALE = 0.97f
    const val DIALOG_INITIAL_SCALE = 0.92f

    val StandardEasing = FastOutSlowInEasing

    fun <T> fastTween() = tween<T>(
        durationMillis = DURATION_FAST_MS,
        easing = StandardEasing
    )

    fun <T> standardTween() = tween<T>(
        durationMillis = DURATION_STANDARD_MS,
        easing = StandardEasing
    )

    fun <T> longTween() = tween<T>(
        durationMillis = DURATION_LONG_MS,
        easing = StandardEasing
    )

    fun <T> sheetSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}
