package com.unarchive.android.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Navigation Compose owns seeking/cancellation and the stack; these only describe drawing. */
internal fun MotionPolicy.pageEnter(forward: Boolean, mainDestination: Boolean, interactiveBack: Boolean = false): EnterTransition {
    if (!animateLayout) return EnterTransition.None
    if (mainDestination) return fadeIn(tween(standardDurationMillis))
    return slideInHorizontally(
        animationSpec = tween(
            if (forward) secondaryPageEnterDurationMillis else secondaryPageExitDurationMillis,
            easing = if (interactiveBack) LinearEasing else if (forward) FastOutSlowInEasing else LinearOutSlowInEasing,
        ),
        initialOffsetX = { width -> if (forward) width else -(width * secondaryPageParallaxFraction).toInt() },
    )
}

internal fun MotionPolicy.pageExit(forward: Boolean, mainDestination: Boolean, interactiveBack: Boolean = false): ExitTransition {
    if (!animateLayout) return ExitTransition.None
    if (mainDestination) return fadeOut(tween(shortDurationMillis))
    return slideOutHorizontally(
        animationSpec = tween(
            if (forward) secondaryPageEnterDurationMillis else secondaryPageExitDurationMillis,
            // Seeking must map finger distance to the same page distance.
            easing = if (interactiveBack) LinearEasing else if (forward) FastOutSlowInEasing else LinearOutSlowInEasing,
        ),
        targetOffsetX = { width -> if (forward) -(width * secondaryPageParallaxFraction).toInt() else width },
    )
}
