package com.unarchive.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.CupertinoButton as LibraryCupertinoButton
import io.github.alexzhirkevich.cupertino.CupertinoButtonDefaults
import io.github.alexzhirkevich.cupertino.CupertinoIconButton
import io.github.alexzhirkevich.cupertino.CupertinoSegmentedControl as LibraryCupertinoSegmentedControl
import io.github.alexzhirkevich.cupertino.CupertinoSegmentedControlTab
import io.github.alexzhirkevich.cupertino.CupertinoSwitch
import io.github.alexzhirkevich.cupertino.CupertinoSlider as LibraryCupertinoSlider
import io.github.alexzhirkevich.cupertino.CupertinoTextFieldDefaults
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi

/** Product-owned button boundary backed by compose-cupertino. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun CupertinoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    LibraryCupertinoButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = CupertinoButtonDefaults.filledButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = content,
    )
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun CupertinoSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    LibraryCupertinoButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = CupertinoButtonDefaults.tintedButtonColors(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun CupertinoPlainButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    LibraryCupertinoButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        colors = CupertinoButtonDefaults.plainButtonColors(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun CupertinoIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    icon: @Composable () -> Unit,
) {
    CupertinoIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
        enabled = enabled,
        content = icon,
    )
}

@Composable
internal fun CupertinoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerModifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = containerModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        // alpha04 puts its modifier on the decoration child, hiding testTag and labels
        // behind the editable node. Keep the actual field semantics while reusing its decoration.
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(colors.primary, colors.primary.copy(alpha = 0.3f)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    if (label != null) contentDescription = label
                },
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                ),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interactionSource,
                onTextLayout = { layoutResult = it },
                decorationBox = { innerTextField ->
                    CupertinoTextFieldDefaults.DecorationBox(
                        valueIsEmpty = value.isEmpty(),
                        innerTextField = innerTextField,
                        enabled = enabled,
                        contentAlignment = Alignment.CenterVertically,
                        interactionSource = interactionSource,
                        textLayoutResult = layoutResult,
                        placeholder = placeholder?.let { { Text(it, color = colors.onSurfaceVariant) } },
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun CupertinoSegmentedControl(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    items: List<String>,
    onSelected: (Int) -> Unit,
    itemModifier: (Int) -> Modifier = { Modifier },
) {
    LibraryCupertinoSegmentedControl(selectedTabIndex = selectedIndex, modifier = modifier) {
        items.forEachIndexed { index, label ->
            CupertinoSegmentedControlTab(
                onClick = { onSelected(index) },
                isSelected = selectedIndex == index,
                modifier = itemModifier(index).heightIn(min = 48.dp).semantics {
                    selected = selectedIndex == index
                    role = Role.Tab
                },
            ) { Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
internal fun CupertinoSettingsRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    SettingsRow(
        title = title,
        description = description,
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}

@Composable
internal fun CupertinoSwitchControl(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        CupertinoSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
        )
    }
}

@Composable
internal fun CupertinoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    LibraryCupertinoSlider(value, onValueChange, modifier.heightIn(min = 48.dp), enabled, valueRange, steps)
}

/** Project-owned icon boundary; icon packs never leak into page APIs. */
@Composable
internal fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
) {
    androidx.compose.material3.Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
