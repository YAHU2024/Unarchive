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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
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
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
    androidx.compose.material3.TextButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
        shape = MaterialTheme.shapes.small,
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
    androidx.compose.material3.FilledTonalIconButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.size(UnarchiveDimensions.touchTarget),
        shape = MaterialTheme.shapes.small,
        colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = content,
    )
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
    SettingsRow(
        title = title, description = description, onClick = onClick,
        modifier = modifier, enabled = enabled,
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
internal fun ChoiceSettingsRow(title: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = UnarchiveDimensions.touchTarget).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

@Composable
internal fun ToggleSettingsRow(title: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = UnarchiveDimensions.touchTarget).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
        Icon(
            if (kind == ProvenanceKind.User) Icons.Filled.Edit else Icons.Filled.Info,
            contentDescription = null, tint = color, modifier = Modifier.size(18.dp),
        )
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
    }
}
