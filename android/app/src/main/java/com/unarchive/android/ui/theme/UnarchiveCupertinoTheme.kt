package com.unarchive.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import io.github.alexzhirkevich.LocalContentColorProvider
import io.github.alexzhirkevich.LocalTextStyleProvider
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import io.github.alexzhirkevich.cupertino.theme.darkColorScheme
import io.github.alexzhirkevich.cupertino.theme.lightColorScheme

/**
 * Project-owned theme boundary for Cupertino controls.
 *
 * Business pages depend on this boundary and on the project components rather
 * than on the third-party theme type. Material tokens remain available for
 * reading surfaces and existing infrastructure while interactive controls use
 * the Cupertino implementation.
 */
@Composable
internal fun UnarchiveCupertinoTheme(darkTheme: Boolean, colors: ColorScheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContentColorProvider provides LocalContentColor,
        LocalTextStyleProvider provides LocalTextStyle,
    ) {
        val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
        CupertinoTheme(colorScheme = scheme.copy(
            accent = colors.primary,
            label = colors.onSurface,
            secondaryLabel = colors.onSurfaceVariant,
            systemBackground = colors.surface,
            systemGroupedBackground = colors.background,
            secondarySystemGroupedBackground = colors.surface,
        ), content = content)
    }
}
