package com.unarchive.android.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Navigation Compose owns seeking/cancellation and the stack; these only describe drawing. */
internal fun MotionPolicy.pageEnter(forward: Boolean, mainDestination: Boolean): EnterTransition {
    if (!animateLayout) return EnterTransition.None
    val fadeDurationMillis = if (mainDestination) standardDurationMillis else secondaryPageEnterDurationMillis
    val fade = fadeIn(tween(fadeDurationMillis))
    if (mainDestination) return fade
    return fade + slideInHorizontally(
        animationSpec = if (forward) {
            tween(secondaryPageEnterDurationMillis, easing = FastOutSlowInEasing)
        } else {
            spring(dampingRatio = springDampingRatio, stiffness = springStiffness)
        },
        initialOffsetX = { width -> (width * secondaryPageTravelFraction * if (forward) 1 else -1).toInt() },
    )
}

internal fun MotionPolicy.pageExit(forward: Boolean, mainDestination: Boolean): ExitTransition {
    if (!animateLayout) return ExitTransition.None
    val fadeDurationMillis = if (mainDestination) shortDurationMillis else secondaryPageExitDurationMillis
    val fade = fadeOut(tween(fadeDurationMillis))
    if (mainDestination) return fade
    return fade + slideOutHorizontally(
        animationSpec = spring(dampingRatio = springDampingRatio, stiffness = springStiffness),
        targetOffsetX = { width -> (width * secondaryPageTravelFraction * if (forward) -1 else 1).toInt() },
    )
}
