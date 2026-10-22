package com.unarchive.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.unarchive.android.ui.theme.LocalMotionPolicy

/** Small project-owned menu, anchored to the adjacent toolbar button. */
@Composable
internal fun AnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    testTag: String = "anchored-menu",
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionPolicy.current
    val density = LocalDensity.current
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(expanded) { visibility.targetState = expanded }
    if (visibility.currentState || visibility.targetState) {
        Popup(
            popupPositionProvider = remember(density) {
                BelowAnchorPositionProvider(with(density) { 4.dp.roundToPx() })
            },
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = if (motion.animateLayout) {
                    fadeIn(tween(motion.shortDurationMillis)) + scaleIn(
                        initialScale = 0.96f,
                        animationSpec = spring(dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness),
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f),
                    )
                } else androidx.compose.animation.EnterTransition.None,
                exit = if (motion.animateLayout) {
                    fadeOut(tween(motion.shortDurationMillis)) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = spring(dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness),
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f),
                    )
                } else androidx.compose.animation.ExitTransition.None,
            ) {
                Surface(
                    modifier = modifier.widthIn(min = 192.dp, max = 280.dp)
                        .semantics { paneTitle = title }
                        .testTag(testTag),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(content = content)
                }
            }
        }
    }
}

/** Places the menu under its parent anchor while keeping it inside the window. */
private class BelowAnchorPositionProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
            LayoutDirection.Rtl -> anchorBounds.left.coerceIn(0, maxX)
        }
        val below = anchorBounds.bottom + margin
        val above = anchorBounds.top - popupContentSize.height - margin
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val y = if (below <= maxY) below else above.coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
internal fun AnchoredMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
