package com.unarchive.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unarchive.android.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Keep this call composed while closed: the Dialog owns focus until its exit finishes. */
@Composable
internal fun GlassBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    onDismissed: () -> Unit = {},
    visible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionPolicy.current
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    var dismissalSent by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestDismissed by rememberUpdatedState(onDismissed)
    fun dismissOnce() {
        if (visible && !dismissalSent) {
            dismissalSent = true
            latestDismiss()
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            dismissalSent = false
            dragOffset = 0f
            dragging = false
        } else if (!motion.animateLayout) {
            latestDismissed()
        } else {
            snapshotFlow { transition.currentState to transition.targetState }
                .first { (current, target) -> !current && !target }
            latestDismissed()
        }
    }
    val drawnOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (dragging || !motion.animateLayout) snap() else spring(dampingRatio = .82f, stiffness = 500f),
        label = "sheet-drag-rebound",
    )
    val dialogVisible = visible || (motion.animateLayout && transition.currentState)
    if (dialogVisible) Dialog(
        onDismissRequest = ::dismissOnce,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val focusRequester = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
            Box(Modifier.matchParentSize().testTag("sheet-outside").pointerInput(visible) {
                detectTapGestures { dismissOnce() }
            })
            AnimatedVisibility(
                visibleState = transition,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (motion.animateLayout) slideInVertically(tween(motion.standardDurationMillis)) { it } + fadeIn(tween(motion.shortDurationMillis)) else androidx.compose.animation.EnterTransition.None,
                exit = if (motion.animateLayout) slideOutVertically(tween(motion.standardDurationMillis)) { it } + fadeOut(tween(motion.shortDurationMillis)) else androidx.compose.animation.ExitTransition.None,
            ) {
                GlassSurface(
                    modifier = Modifier.padding(12.dp).fillMaxWidth()
                        .offset { IntOffset(0, drawnOffset.roundToInt()) }
                        .semantics { paneTitle = title; dismiss { dismissOnce(); true } }
                        .testTag("glass-bottom-sheet"),
                    emphasized = true,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).testTag("sheet-drag-handle")
                                .semantics { contentDescription = "向下拖动关闭" }
                                .draggable(
                                    state = rememberDraggableState { delta -> dragOffset = (dragOffset + delta).coerceAtLeast(0f) },
                                    orientation = Orientation.Vertical,
                                    enabled = visible && !dismissalSent,
                                    onDragStarted = { dragging = true },
                                    onDragStopped = { velocity ->
                                        dragging = false
                                        if (dragOffset >= threshold || (velocity > 1500f && dragOffset > threshold / 4)) dismissOnce()
                                        else dragOffset = 0f
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(36.dp).height(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f), RoundedCornerShape(3.dp)))
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.focusRequester(focusRequester).focusable().semantics { heading() })
                        content()
                    }
                }
                // During exit retain the modal but prevent actions from executing twice.
                if (!visible || dismissalSent) Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { }
                })
            }
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
