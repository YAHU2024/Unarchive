package com.unarchive.android.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Feeds an in-content right swipe into the same predictive-back dispatcher
 * used by the system edge gesture. Navigation Compose therefore draws the
 * previous page underneath, follows the finger, and restores it on cancel.
 */
@Composable
internal fun Modifier.appContentBackGesture(
    enabled: Boolean,
    onBack: () -> Unit,
): Modifier {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val latestOnBack = rememberUpdatedState(onBack)
    val density = LocalDensity.current
    val state = remember { ContentBackGestureState() }
    val slop = with(density) { 18.dp.toPx() }

    return this
        .onSizeChanged { state.widthPx = it.width.toFloat().coerceAtLeast(1f) }
        .draggable(
        state = rememberDraggableState { delta ->
            if (!state.active) return@rememberDraggableState
            state.totalX += delta
            state.totalY += 0f
            if (state.totalX > slop && state.totalX > kotlin.math.abs(state.totalY)) {
                val progress = (state.totalX / state.widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                dispatcher?.dispatchOnBackProgressed(
                    BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT),
                )
            }
        },
        orientation = Orientation.Horizontal,
        enabled = enabled,
        onDragStarted = {
            state.reset()
            state.active = true
            dispatcher?.dispatchOnBackStarted(
                BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT),
            )
        },
        onDragStopped = {
            val commit = state.totalX >= state.widthPx * COMMIT_FRACTION
            state.active = false
            if (commit) {
                dispatcher?.onBackPressed() ?: latestOnBack.value()
            } else {
                dispatcher?.dispatchOnBackCancelled()
            }
            state.reset()
        },
    )
}

private const val COMMIT_FRACTION = 0.32f

private class ContentBackGestureState {
    var active: Boolean = false
    var totalX: Float = 0f
    var totalY: Float = 0f
    var widthPx: Float = 1f

    fun reset() {
        totalX = 0f
        totalY = 0f
    }
}
