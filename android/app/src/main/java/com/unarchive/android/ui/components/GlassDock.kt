package com.unarchive.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalMotionPolicy

/** A floating, platform-inspired dock for the four primary destinations. */
@Composable
internal fun GlassDock(
    selectedIndex: Int,
    itemCount: Int,
    content: @Composable RowScope.() -> Unit,
) {
    require(itemCount > 0 && selectedIndex in 0 until itemCount)
    val motion = LocalMotionPolicy.current
    val animatedPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (motion.animateLayout) {
            spring(dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness)
        } else { snap() },
        label = "dock-capsule",
    )
    // Reduced motion is synchronous; even a snap animation waits for a frame.
    val position = if (motion.animateLayout) animatedPosition else selectedIndex.toFloat()
    val shape = RoundedCornerShape(32.dp)
    val itemHeight = dockItemHeight()
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .testTag("app-dock"),
        emphasized = true,
        shape = shape,
        shadowElevation = 8.dp,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val itemWidth = (maxWidth - gap * (itemCount - 1)) / itemCount
            Box(
                Modifier.offset(x = (itemWidth + gap) * position)
                    .width(itemWidth).height(itemHeight)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .testTag("dock-selection-capsule"),
            )
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
internal fun RowScope.GlassDockItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionPolicy.current
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
        animationSpec = tween(durationMillis = motion.shortDurationMillis),
        label = "dock-content",
    )
    val itemShape = RoundedCornerShape(26.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && motion.animateLayout) 0.97f else 1f,
        animationSpec = if (motion.animateLayout) spring(stiffness = 900f) else snap(),
        label = "dock-item-press",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(dockItemHeight())
            .clip(itemShape)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .semantics(mergeDescendants = true) {
                contentDescription = "导航：$label${if (selected) "，当前" else ""}"
                this.selected = selected
                role = Role.Tab
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppIcon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
            )
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun dockItemHeight() = if (LocalDensity.current.fontScale >= 1.5f) 88.dp else 56.dp
