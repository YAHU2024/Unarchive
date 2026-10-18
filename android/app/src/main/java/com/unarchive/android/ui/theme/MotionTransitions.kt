package com.unarchive.android.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Navigation Compose owns seeking/cancellation and the stack; these only describe drawing. */
internal fun MotionPolicy.pageEnter(forward: Boolean, mainDestination: Boolean): EnterTransition {
    val fade = fadeIn(tween(standardDurationMillis))
    if (!animateLayout || mainDestination) return fade
    return fade + slideInHorizontally(
        animationSpec = spring(dampingRatio = springDampingRatio, stiffness = springStiffness),
        initialOffsetX = { width -> (width * pageTravelFraction * if (forward) 1 else -1).toInt() },
    )
}

internal fun MotionPolicy.pageExit(forward: Boolean, mainDestination: Boolean): ExitTransition {
    val fade = fadeOut(tween(shortDurationMillis))
    if (!animateLayout || mainDestination) return fade
    return fade + slideOutHorizontally(
        animationSpec = spring(dampingRatio = springDampingRatio, stiffness = springStiffness),
        targetOffsetX = { width -> (width * pageTravelFraction * if (forward) -1 else 1).toInt() },
    )
}
