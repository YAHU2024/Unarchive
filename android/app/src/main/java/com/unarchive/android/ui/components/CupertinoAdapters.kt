package com.unarchive.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.text.font.FontWeight
import com.unarchive.android.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import com.unarchive.android.ui.theme.LocalSemanticColors
import io.github.alexzhirkevich.cupertino.CupertinoButton as LibraryCupertinoButton
import io.github.alexzhirkevich.cupertino.CupertinoButtonDefaults
import io.github.alexzhirkevich.cupertino.CupertinoIconButton
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
            containerColor = LocalSemanticColors.current.actionFill,
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        content = { ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() } },
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
    var feedbackSequence by remember { mutableStateOf(0) }
    var activated by remember { mutableStateOf(false) }
    val motion = LocalMotionPolicy.current
    LaunchedEffect(feedbackSequence) {
        if (feedbackSequence > 0) {
            activated = true
            delay(if (motion.animateLayout) 140 else 80)
            activated = false
        }
    }
    var pressed by remember { mutableStateOf(false) }
    val emphasized = enabled && (pressed || activated)
    val scale by animateFloatAsState(
        if (emphasized && motion.animateLayout) 0.9f else 1f,
        animationSpec = if (motion.animateLayout) spring(stiffness = 900f) else snap(), label = "icon-press",
    )
    CupertinoIconButton(
        onClick = { activated = true; feedbackSequence++; onClick() },
        modifier = modifier.size(48.dp)
            .pointerInput(enabled) {
                if (enabled) awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { pressed = false }
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (emphasized) 0.55f else 1f }
            .semantics {
                actionEmphasized = emphasized
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
    if (items.isEmpty()) return
    val motion = LocalMotionPolicy.current
    val animatedPosition by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(items.indices).toFloat(),
        animationSpec = if (motion.animateLayout) spring(
            dampingRatio = motion.springDampingRatio, stiffness = motion.springStiffness,
        ) else snap(),
        label = "segmented-thumb",
    )
    val position = if (motion.animateLayout) animatedPosition else selectedIndex.coerceIn(items.indices).toFloat()
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
        .padding(3.dp).selectableGroup().semantics { segmentPosition = position }) {
        val tabWidth = maxWidth / items.size
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = tabWidth * position).width(tabWidth).fillMaxSize()
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface))
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, label ->
            val interactionSource = remember(index) { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = if (pressed && motion.animateLayout) 0.97f else 1f,
                animationSpec = if (motion.animateLayout) spring(stiffness = 900f) else snap(),
                label = "segment-press-$index",
            )
            Box(itemModifier(index).weight(1f).heightIn(min = 48.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                    ) { if (selectedIndex != index) onSelected(index) }
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .semantics {
                        role = Role.Tab
                        selected = selectedIndex == index
                    },
                    contentAlignment = Alignment.Center) {
                    Text(label, modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
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

/** Observable presentation state also used by adapter regression tests. */
internal val ActionEmphasizedKey = SemanticsPropertyKey<Boolean>("ActionEmphasized")
internal var SemanticsPropertyReceiver.actionEmphasized by ActionEmphasizedKey
internal val SegmentPositionKey = SemanticsPropertyKey<Float>("SegmentPosition")
internal var SemanticsPropertyReceiver.segmentPosition by SegmentPositionKey
