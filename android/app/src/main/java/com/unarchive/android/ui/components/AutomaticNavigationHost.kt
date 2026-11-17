package com.unarchive.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.CompletableDeferred

internal val LocalAutomaticNavigation = staticCompositionLocalOf<AutomaticNavigationState?> { null }
internal data class AutomaticMotionStatus(val active: Boolean, val prepared: Boolean,
    val forward: Boolean, val progress: Float, val retainedFrames: Int)
internal val AutomaticMotionStatusKey = SemanticsPropertyKey<() -> AutomaticMotionStatus>("AutomaticMotionStatus")

internal class AutomaticPageFrame(val layer: GraphicsLayer) {
    var recorded = false
    var mounted = true
}

internal class PreparedAutomaticMotion(
    val targetId: String,
    val source: AutomaticPageFrame,
    val forward: Boolean,
    val durationMillis: Int,
) {
    val ready = CompletableDeferred<Unit>()
    val progress = Animatable(0f)
    var prepared by mutableStateOf(false)
    var active by mutableStateOf(true)
}

/** Keeps only mounted frames plus the outgoing render layer for one automatic transition. */
internal class AutomaticNavigationState(private val graphics: GraphicsContext) {
    private val frames = mutableMapOf<String, AutomaticPageFrame>()
    private var currentId: String? = null
    private var previousIds = emptyList<String>()
    private var previousMain = false
    fun status() = motion?.let {
        AutomaticMotionStatus(it.active, it.prepared, it.forward, it.progress.value, frames.size)
    } ?: AutomaticMotionStatus(false, false, true, 1f, frames.size)
    var motion by mutableStateOf<PreparedAutomaticMotion?>(null)
        private set

    fun prepare(id: String?, ids: List<String>, main: Boolean, interactive: Boolean,
                animate: Boolean, enterMillis: Int, exitMillis: Int): PreparedAutomaticMotion? {
        if (id == currentId) return motion
        val source = frames[currentId]
        motion?.let { finish(it) }
        motion = if (id != null && source?.recorded == true && animate && !interactive && !(main && previousMain)) {
            PreparedAutomaticMotion(id, source, id !in previousIds,
                if (id !in previousIds) enterMillis else exitMillis).also { prepared ->
                // A zero-progress system gesture may already have rendered the target.
                if (frames[id]?.let { it.mounted && it.recorded } == true) prepared.ready.complete(Unit)
            }
        } else null
        currentId = id
        previousIds = ids
        previousMain = main
        return motion
    }

    fun frame(id: String): AutomaticPageFrame = frames.getOrPut(id) {
        AutomaticPageFrame(graphics.createGraphicsLayer())
    }.also { it.mounted = true }

    fun unmount(id: String) {
        frames[id]?.let {
            it.mounted = false
            if (motion?.active != true || motion?.source !== it) release(id)
        }
    }

    fun finish(segment: PreparedAutomaticMotion) {
        segment.active = false
        frames.entries.filter { !it.value.mounted && it.value === segment.source }
            .map { it.key }.forEach(::release)
    }

    private fun release(id: String) { frames.remove(id)?.let { graphics.releaseGraphicsLayer(it.layer) } }
    fun dispose() { frames.keys.toList().forEach(::release) }
}

@Composable
internal fun AutomaticNavigationHost(
    entryId: String?,
    backStackIds: List<String>,
    main: Boolean,
    interactive: Boolean,
    content: @Composable () -> Unit,
) {
    val graphics = LocalGraphicsContext.current
    val state = remember(graphics) { AutomaticNavigationState(graphics) }
    val policy = LocalMotionPolicy.current
    // Plan before composing NavHost's target, so the outgoing frame covers preparation.
    val segment = state.prepare(entryId, backStackIds, main, interactive, policy.animateLayout,
        policy.automaticPageEnterDurationMillis, policy.automaticPageExitDurationMillis)
    LaunchedEffect(segment) {
        if (segment != null) {
            val alreadyDrawn = segment.ready.isCompleted
            segment.ready.await()
            // A real first draw, rather than a fixed delay or a layout callback, opens
            // the gate. Reset the animation clock after that expensive frame completes.
            if (!alreadyDrawn) withFrameNanos { }
            segment.prepared = true
            // A cold target also needs its zero-progress layer transform applied.
            // System preparation has already drawn that page before confirmation.
            if (!alreadyDrawn) withFrameNanos { }
            segment.progress.animateTo(1f, tween(segment.durationMillis, easing = FastOutSlowInEasing))
            state.finish(segment)
        }
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    CompositionLocalProvider(LocalAutomaticNavigation provides state) {
        Box(Modifier.fillMaxSize().testTag("automatic-navigation-host")
            .semantics { this[AutomaticMotionStatusKey] = { state.status() } }
            .pointerInput(segment) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Underlying target controls must not accept taps through the
                        // outgoing drawing while preparation/automatic movement runs.
                        if (segment?.active == true) event.changes.forEach { it.consume() }
                    }
                }
            }.drawWithCache {
            val shadowWidth = 24.dp.toPx()
            val shadow = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                startX = -shadowWidth, endX = 0f)
            onDrawWithContent {
                val running = segment?.takeIf { it.active }
                if (running == null) {
                    drawContent()
                } else if (!running.prepared) {
                    // Build/draw the incoming page underneath a stable outgoing frame.
                    drawContent()
                    drawLayer(running.source.layer)
                } else {
                    val progress = running.progress.value
                    val edge = if (running.forward) size.width * (1f - progress) else size.width * progress
                    if (running.forward) {
                        translate(left = -size.width * policy.secondaryPageParallaxFraction * progress) {
                            drawLayer(running.source.layer)
                            drawRect(Color.Black.copy(alpha = 0.10f * progress))
                        }
                        drawContent()
                    } else {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.10f * (1f - progress)))
                        translate(left = edge) { drawLayer(running.source.layer) }
                    }
                    translate(left = edge) {
                        drawRect(shadow, Offset(-shadowWidth, 0f), Size(shadowWidth, size.height),
                            alpha = NavigationDepthPolicy.shadowAlpha(edge, size.width))
                    }
                }
            }
        }) { content() }
    }
}

/** Capture at rest; automatic movement only updates the render layer's transform. */
@Composable
internal fun Modifier.preparedAutomaticPage(entryId: String): Modifier {
    val state = LocalAutomaticNavigation.current ?: return this
    val frame = remember(state, entryId) { state.frame(entryId) }
    val segment = state.motion?.takeIf { it.targetId == entryId }
    val parallax = LocalMotionPolicy.current.secondaryPageParallaxFraction
    DisposableEffect(state, entryId) { onDispose { state.unmount(entryId) } }
    return this.graphicsLayer {
        translationX = if (segment?.active == true && segment.prepared) {
            if (segment.forward) size.width * (1f - segment.progress.value)
            else -size.width * parallax * (1f - segment.progress.value)
        } else 0f
    }.drawWithCache {
        onDrawWithContent {
            frame.layer.record { this@onDrawWithContent.drawContent() }
            frame.recorded = true
            drawLayer(frame.layer)
            if (segment?.active == true) segment.ready.complete(Unit)
        }
    }
}
