package com.unarchive.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unarchive.android.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.launch

/** Draw-only emphasis: one semantic tree, current text/actions, and no completion callbacks. */
@Composable
internal fun rememberStateMotionModifier(
    key: String,
    animateOnEntry: Boolean = false,
    sheet: Boolean = false,
): Modifier {
    val motion = LocalMotionPolicy.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val progress = remember { Animatable(0f) }
    var previousKey by rememberSaveable { mutableStateOf(key) }
    var presented by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) scope.launch { progress.snapTo(0f) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(key, motion) {
        val changed = previousKey != key || (!presented && animateOnEntry)
        previousKey = key
        presented = true
        if (changed && motion.animateLayout && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            progress.snapTo(1f)
            progress.animateTo(
                0f,
                spring(dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness),
            )
        } else {
            progress.snapTo(0f)
        }
    }
    return Modifier.graphicsLayer {
        // Read policy directly: changing to Reduced never waits for a coroutine/frame to settle.
        val fraction = if (motion.animateLayout) progress.value.coerceIn(0f, 1f) else 0f
        translationY = (if (sheet) motion.sheetTravelDp else motion.stateTravelDp) * density * fraction
        alpha = 1f - (1f - motion.stateInitialAlpha) * fraction
    }
}
