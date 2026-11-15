package com.unarchive.android.ui.components

import android.view.View
import android.view.WindowInsets
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One session owns dragging and settling; Navigation receives cancellation only at zero. */
internal class ContentBackGestureController(
    private val dispatcher: OnBackPressedDispatcher,
    private val scope: CoroutineScope,
    private val awaitFrames: suspend () -> Unit = { repeat(3) { withFrameNanos { } } },
    private val animateProgress: suspend (Float, Float, Float, Int, (Float) -> Unit) -> Unit =
        { from, to, velocity, duration, update ->
            val slope = if (to == from) 0f else (velocity * duration / 1000f / (to - from)).coerceIn(0f, 3f)
            // Cubic Hermite: preserve release velocity where possible, finish at rest, no overshoot.
            val easing = Easing { t ->
                val t2 = t * t
                val t3 = t2 * t
                (-2f * t3 + 3f * t2 + slope * (t3 - 2f * t2 + t)).coerceIn(0f, 1f)
            }
            animate(from, to, animationSpec = tween(duration, easing = easing)) { value, _ -> update(value) }
        },
) {
    var enabled = false
    var animateLayout = true
    var returnDurationMillis = 280
    var cancelDurationMillis = 200
    var seeking by mutableStateOf(false)
        private set
    var systemPreparing by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var dragging = false
    private var ending = false
    private var committing = false
    private var endDispatched = false
    private var settling: Job? = null
    private var routeId: String? = null

    // Registered on the real dispatcher. NavHost uses a separate dispatcher so a
    // system edge gesture can never overwrite an in-content progress session.
    val systemBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (enabled && !seeking && !systemPreparing) {
                systemPreparing = true
                // Prepare the real previous page while the OS owns the edge gesture.
                // Always seek zero: system progress must never move the page.
                dispatcher.dispatchOnBackStarted(backEvent(0f))
                dispatcher.dispatchOnBackProgressed(backEvent(0f))
            }
        }

        override fun handleOnBackCancelled() {
            if (systemPreparing) {
                systemPreparing = false
                dispatcher.dispatchOnBackCancelled()
            }
        }

        override fun handleOnBackPressed() {
            if (systemPreparing) {
                systemPreparing = false
                dispatcher.onBackPressed()
            } else if (seeking && !ending) finish(commit = true)
            else if (!seeking) dispatcher.onBackPressed()
        }
    }

    fun changeRoute(id: String?) {
        if (routeId == id) return
        dispose()
        routeId = id
    }

    fun start(): Boolean {
        if (!enabled || ending || committing || systemPreparing) return false
        settling?.cancel()
        settling = null
        dragging = true
        if (!seeking) {
            progress = 0f
            endDispatched = false
            seeking = true
            dispatcher.dispatchOnBackStarted(backEvent(0f))
        }
        return true
    }

    fun progress(value: Float) {
        if (!dragging || ending) return
        updateProgress(value)
    }

    fun finish(commit: Boolean, velocity: Float = 0f) {
        if (!seeking || ending || (!dragging && settling != null)) return
        dragging = false
        committing = commit && enabled
        val target = if (committing) 1f else 0f
        val duration = ContentBackGesturePolicy.settleDuration(
            progress, target, velocity,
            if (committing) returnDurationMillis else cancelDurationMillis,
        )
        settling = scope.launch {
            if (animateLayout && progress != target) {
                animateProgress(progress, target, velocity, duration, ::updateProgress)
            }
            updateProgress(target)
            // Let NavHost's progress collector and seek effect observe the endpoint.
            // Dispatching cancellation earlier leaves its asynchronous rollback in flight.
            ending = true
            awaitFrames()
            endDispatched = true
            if (committing) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
            awaitFrames()
            progress = 0f
            seeking = false
            ending = false
            committing = false
            settling = null
        }
    }

    fun dispose() {
        settling?.cancel()
        settling = null
        if (seeking && !endDispatched) dispatcher.dispatchOnBackCancelled()
        if (systemPreparing) dispatcher.dispatchOnBackCancelled()
        systemPreparing = false
        progress = 0f
        seeking = false
        dragging = false
        ending = false
        committing = false
        endDispatched = false
    }

    private fun updateProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        dispatcher.dispatchOnBackProgressed(backEvent(progress))
    }

    private fun backEvent(value: Float) = BackEventCompat(0f, 0f, value, BackEventCompat.EDGE_LEFT)
}

internal object ContentBackGesturePolicy {
    const val ActivationDp = 72f
    const val ReversalDp = 28f

    fun progress(distance: Float, width: Float): Float =
        (distance / width.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun settleDuration(from: Float, to: Float, velocity: Float, fullDuration: Int): Int {
        if (fullDuration <= 0) return 0
        val remaining = kotlin.math.abs(to - from)
        val speed = (if ((to - from) * velocity > 0f) kotlin.math.abs(velocity) else 0f)
            .coerceAtLeast(1000f / fullDuration)
        return (remaining / speed * 1000f).toInt().coerceIn(minOf(100, fullDuration), fullDuration)
    }
}

/** Direction hysteresis measures intent in dp, independently of the visual page offset. */
internal class ContentBackGestureIntent(private val activation: Float, private val reversal: Float) {
    var shouldCommit = false
        private set
    private var extreme = 0f
    private var activated = false

    fun update(distance: Float) {
        if (!activated) {
            if (distance < activation) return
            activated = true
            shouldCommit = true
            extreme = distance
        } else if (shouldCommit) {
            extreme = maxOf(extreme, distance)
            if (extreme - distance >= reversal) {
                shouldCommit = false
                extreme = distance
            }
        } else {
            extreme = minOf(extreme, distance)
            if (distance - extreme >= reversal) {
                shouldCommit = true
                extreme = distance
            }
        }
    }
}

/** Install on the stationary host, never on a page translated by the gesture itself. */
internal fun Modifier.appContentBackGesture(
    enabled: Boolean,
    controller: ContentBackGestureController,
    view: View,
    density: Density,
): Modifier = pointerInput(enabled, controller, view, density) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val edges = view.rootView.rootWindowInsets?.getInsets(WindowInsets.Type.systemGestures())
        if (down.position.x < (edges?.left ?: 0) || down.position.x >= size.width - (edges?.right ?: 0)) {
            return@awaitEachGesture
        }
        // Main-pass child consumption wins over navigation (scrolling/selection).
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
            if (change.position.x - down.position.x > kotlin.math.abs(change.position.y - down.position.y)) {
                change.consume()
            }
        } ?: return@awaitEachGesture
        if (drag.position.x <= down.position.x || currentEvent.changes.count { it.pressed } > 1) {
            return@awaitEachGesture
        }
        if (!controller.start()) return@awaitEachGesture
        val initialOffset = controller.progress * size.width
        // Subtract touch slop so crossing it never makes the page jump forward.
        val dragStartX = down.position.x + viewConfiguration.touchSlop
        val intent = ContentBackGestureIntent(
            with(density) { ContentBackGesturePolicy.ActivationDp.dp.toPx() },
            with(density) { ContentBackGesturePolicy.ReversalDp.dp.toPx() },
        )
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, down.position)
        velocity.addPosition(drag.uptimeMillis, drag.position)
        var interrupted = false
        try {
            val initialDistance = drag.position.x - dragStartX
            intent.update(drag.position.x - down.position.x)
            controller.progress(ContentBackGesturePolicy.progress(initialOffset + initialDistance, size.width.toFloat()))
            val released = horizontalDrag(drag.id) { change ->
                if (currentEvent.changes.count { it.pressed } > 1) interrupted = true
                if (!interrupted) {
                    val distance = change.position.x - dragStartX
                    intent.update(change.position.x - down.position.x)
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    controller.progress(ContentBackGesturePolicy.progress(initialOffset + distance, size.width.toFloat()))
                }
            }
            currentEvent.changes.firstOrNull { it.id == drag.id }?.let {
                velocity.addPosition(it.uptimeMillis, it.position)
            }
            controller.finish(
                commit = released && !interrupted && intent.shouldCommit,
                velocity = velocity.calculateVelocity().x / size.width.coerceAtLeast(1),
            )
        } finally {
            controller.finish(commit = false)
        }
    }
}
