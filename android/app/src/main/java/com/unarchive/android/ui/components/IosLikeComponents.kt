package com.unarchive.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unarchive.android.ui.theme.UnarchiveDimensions
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.CupertinoSection
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.rememberSectionState

/** A restrained grouped container with an optional section title. */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
internal fun InsetGroupedList(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        title?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CupertinoSection(
            modifier = Modifier.fillMaxWidth(),
            style = SectionStyle.InsetGrouped,
            state = rememberSectionState(canCollapse = false),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(content = content)
        }
    }
}

/** A presentation-only settings row with stable touch height and optional actions. */
@Composable
internal fun SettingsRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val interactionModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .heightIn(min = UnarchiveDimensions.touchTarget)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingContent?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent()
    }
}

/** A primary action with the project's stable touch target and shape. */
@Composable
internal fun IosLikeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    UnarchiveButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = UnarchiveDimensions.touchTarget),
    ) {
        leadingContent?.invoke()
        if (leadingContent != null) Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/** A text-first segment; caller owns selection and scrolling. */
@Composable
internal fun IosLikeFilterSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.clip(shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = UnarchiveDimensions.touchTarget),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A 48dp icon action with an explicit semantic label. */
@Composable
internal fun IosLikeIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    UnarchiveIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(UnarchiveDimensions.touchTarget)
            .semantics { this.contentDescription = contentDescription },
    ) {
        content()
    }
}

/** Text-first search input; callers own query state and search events. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = UnarchiveDimensions.touchTarget),
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
    )
}

/** Semantic status tones shared by short-lived state and recovery messages. */
internal enum class StatusBannerTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/** A presentation-only status block with text and an optional action. */
@Composable
internal fun StatusBanner(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    tone: StatusBannerTone = StatusBannerTone.NEUTRAL,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    liveRegion: LiveRegionMode? = null,
) {
    val statusColor = when (tone) {
        StatusBannerTone.ERROR -> MaterialTheme.colorScheme.error
        StatusBannerTone.SUCCESS -> MaterialTheme.colorScheme.primary
        StatusBannerTone.WARNING -> MaterialTheme.colorScheme.tertiary
        StatusBannerTone.INFO -> MaterialTheme.colorScheme.secondary
        StatusBannerTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(com.unarchive.android.ui.theme.UnarchiveShapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = statusColor,
                modifier = if (liveRegion != null) {
                    Modifier.semantics { this.liveRegion = liveRegion }
                } else {
                    Modifier
                },
            )
            description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                IosLikeButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

/** A bounded progress card that keeps task state outside the component. */
@Composable
internal fun TaskProgressCard(
    title: String,
    stageLabel: String? = null,
    statusMessage: String = "",
    progress: Float? = null,
    checkpointMessage: String? = null,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    stageModifier: Modifier = Modifier,
    progressModifier: Modifier = Modifier,
    statusModifier: Modifier = Modifier,
    checkpointModifier: Modifier = Modifier,
    cancelModifier: Modifier = Modifier,
    stageContentDescription: String? = null,
    stageLiveRegion: LiveRegionMode? = null,
    stageColor: androidx.compose.ui.graphics.Color? = null,
    progressColor: androidx.compose.ui.graphics.Color? = null,
    showCancel: Boolean = false,
    cancelEnabled: Boolean = true,
    cancelLabel: String = "取消处理",
    onCancel: (() -> Unit)? = null,
    description: String? = null,
) {
    GlassSurface(modifier = modifier, emphasized = emphasized) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            stageLabel?.takeIf(String::isNotBlank)?.let { label ->
                Text(
                    label,
                    modifier = stageModifier.then(rememberStateMotionModifier(label)).semantics {
                        stageContentDescription?.let { contentDescription = it }
                        stageLiveRegion?.let { liveRegion = it }
                    },
                    color = stageColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value.coerceIn(0f, 1f) },
                    modifier = progressModifier.fillMaxWidth(),
                    color = progressColor ?: MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                statusMessage,
                modifier = statusModifier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            checkpointMessage?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    modifier = checkpointModifier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showCancel && onCancel != null) {
                UnarchiveSecondaryButton(
                    onClick = onCancel,
                    enabled = cancelEnabled,
                    modifier = cancelModifier.heightIn(min = UnarchiveDimensions.touchTarget),
                ) {
                    Text(cancelLabel)
                }
            }
        }
    }
}

/** A presentation-only recovery card; recovery decisions remain with the caller. */
@Composable
internal fun RecoveryCard(
    title: String,
    statusLabel: String,
    description: String? = null,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
    wrapSurface: Boolean = true,
    statusTone: StatusBannerTone = StatusBannerTone.WARNING,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    primaryAction: Boolean = true,
    actionModifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val statusColor = when (statusTone) {
        StatusBannerTone.ERROR -> MaterialTheme.colorScheme.error
        StatusBannerTone.SUCCESS -> MaterialTheme.colorScheme.primary
        StatusBannerTone.WARNING -> MaterialTheme.colorScheme.tertiary
        StatusBannerTone.INFO -> MaterialTheme.colorScheme.secondary
        StatusBannerTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val motionModifier = modifier.then(rememberStateMotionModifier(statusLabel, animateOnEntry = true))
    val contentModifier = if (wrapSurface) Modifier else motionModifier.fillMaxWidth()
    val content: @Composable () -> Unit = {
        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(statusLabel, color = statusColor)
            errorMessage?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (actionLabel != null && onAction != null) {
                if (primaryAction) {
                    IosLikeButton(
                        text = actionLabel, onClick = onAction,
                        enabled = actionEnabled, modifier = actionModifier,
                    )
                } else {
                    UnarchiveSecondaryButton(onClick = onAction, enabled = actionEnabled, modifier = actionModifier) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
    if (wrapSurface) {
        ReadingSurface(modifier = motionModifier, content = content)
    } else {
        content()
    }
}

/** A compact text-first indicator for local save or sync state. */
@Composable
internal fun SaveStateIndicator(
    label: String,
    modifier: Modifier = Modifier,
    tone: StatusBannerTone = StatusBannerTone.NEUTRAL,
    detail: String? = null,
) {
    val containerColor = when (tone) {
        StatusBannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusBannerTone.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
        StatusBannerTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusBannerTone.INFO -> MaterialTheme.colorScheme.primaryContainer
        StatusBannerTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (tone) {
        StatusBannerTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusBannerTone.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusBannerTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusBannerTone.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusBannerTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth().then(rememberStateMotionModifier(label)),
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = contentColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
        }
    }
}

/** A reusable empty state with an optional caller-owned next action. */
@Composable
internal fun EmptyStateCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ReadingSurface(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                IosLikeButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}
