package com.unarchive.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalLiquidGlassPolicy
import com.unarchive.android.ui.theme.LocalLiquidGlassTokens
import com.unarchive.android.ui.theme.LocalMotionPolicy

/** A floating, platform-inspired dock for the four primary destinations. */
@Composable
internal fun GlassDock(
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tokens = LocalLiquidGlassTokens.current
    val enabled = LocalLiquidGlassPolicy.current.usesTransparency
    val shape = RoundedCornerShape(32.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .border(
                BorderStroke(
                    width = 1.dp,
                    color = colors.outlineVariant.copy(
                        alpha = if (enabled) tokens.dockBorderAlpha else tokens.borderAlpha,
                    ),
                ),
                shape,
            )
            .testTag("app-dock"),
        shape = shape,
        color = colors.surface.copy(
            alpha = if (enabled) tokens.dockSurfaceAlpha else 1f,
        ),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
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
    val selectedContainer by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = motion.shortDurationMillis),
        label = "dock-selection",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = tween(durationMillis = motion.shortDurationMillis),
        label = "dock-content",
    )
    val itemShape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(itemShape)
            .background(selectedContainer)
            .clickable(
                role = Role.Tab,
                onClick = onClick,
            )
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
            Icon(
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
