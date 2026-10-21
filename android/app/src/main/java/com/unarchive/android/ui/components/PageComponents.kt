package com.unarchive.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalSemanticColors
import com.unarchive.android.ui.theme.UnarchiveDimensions

internal class AdaptiveActionScope(val actionModifier: Modifier)

/** Stack labels at large text or narrow widths instead of truncating an action. */
@Composable
internal fun AdaptiveActions(modifier: Modifier = Modifier, content: @Composable AdaptiveActionScope.() -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdaptiveActionScope(Modifier.fillMaxWidth()).content()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdaptiveActionScope(Modifier.weight(1f)).content()
            }
        }
    }
}

/** Project action boundary. Callers keep event ownership and arbitrary icon/text slots. */
@Composable
internal fun UnarchiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    CupertinoButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        content = content,
    )
}

@Composable
internal fun UnarchiveSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    CupertinoSecondaryButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        content = content,
    )
}

@Composable
internal fun UnarchiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    CupertinoPlainButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        content = content,
    )
}

@Composable
internal fun UnarchiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CupertinoIconAction(onClick = onClick, enabled = enabled, modifier = modifier, icon = content)
}

@Composable
internal fun PageHeader(title: String, description: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Reusable navigation row; the whole row is the sole click target. */
@Composable
internal fun NavigationSettingsRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CupertinoSettingsRow(
        title = title, description = description, onClick = onClick,
        modifier = modifier, enabled = enabled,
        trailingContent = {
            AppIcon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
internal fun ChoiceSettingsRow(title: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Row(
        modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = UnarchiveDimensions.touchTarget).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Box(Modifier.size(24.dp)) {
            if (selected) AppIcon(AppIcons.Check, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ToggleSettingsRow(title: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, description: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = UnarchiveDimensions.touchTarget).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        CupertinoSwitchControl(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

/** Only short chrome uses glass. Reading/list bodies remain opaque. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnarchiveTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassSurface(contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(0.dp)) {
        TopAppBar(
            title = title, navigationIcon = navigationIcon, actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

internal enum class ProvenanceKind { Ai, User, Source }

/** Origin is supplied explicitly by the caller; never inferred from Markdown prose. */
@Composable
internal fun ProvenanceRail(label: String, kind: ProvenanceKind, modifier: Modifier = Modifier) {
    val colors = LocalSemanticColors.current
    val color = when (kind) { ProvenanceKind.Ai -> colors.ai; ProvenanceKind.User -> colors.user; ProvenanceKind.Source -> colors.source }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(color, MaterialTheme.shapes.small))
        AppIcon(
            if (kind == ProvenanceKind.User) AppIcons.Create else AppIcons.Info,
            contentDescription = null, tint = color, modifier = Modifier.size(18.dp),
        )
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}
