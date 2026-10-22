package com.unarchive.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.unarchive.android.ui.theme.LocalMotionPolicy

/** In-content right-swipe back; vertical scrolling keeps priority until horizontal intent is clear. */
@Composable
internal fun Modifier.appContentBackGesture(enabled: Boolean, onBack: () -> Unit): Modifier {
    val motion = LocalMotionPolicy.current
    val latestOnBack = rememberUpdatedState(onBack)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleTarget by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val settledOffset by animateFloatAsState(
        targetValue = settleTarget,
        animationSpec = if (motion.animateLayout) {
            spring(dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness)
        } else snap(),
        label = "content-back-settle",
    )
    val drawnOffset = if (dragging) dragOffset else settledOffset
    val threshold = widthPx * 0.32f

    return this
        .graphicsLayer {
            widthPx = size.width.coerceAtLeast(1f)
            translationX = drawnOffset
            alpha = 1f - (drawnOffset / widthPx) * 0.04f
        }
        .draggable(
            state = rememberDraggableState { delta ->
                dragging = true
                dragOffset = (dragOffset + delta).coerceIn(0f, widthPx)
            },
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStarted = {
                dragOffset = 0f
                settleTarget = 0f
                dragging = true
            },
            onDragStopped = {
                val commit = dragOffset >= threshold
                dragging = false
                settleTarget = if (commit) widthPx else 0f
                if (commit) latestOnBack.value()
            },
        )
}
