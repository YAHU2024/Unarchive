package com.unarchive.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6257),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EEE3),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF9B4F2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF391305),
    tertiary = Color(0xFF406180),
    onTertiary = Color.White,
    background = Color(0xFFF4F7F5),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFAFCFA),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDCE5E1),
    onSurfaceVariant = Color(0xFF3E4945),
    outline = Color(0xFF6E7974),
    outlineVariant = Color(0xFFBEC9C4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD8CB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFB8EEE3),
    secondary = Color(0xFFFFB59A),
    onSecondary = Color(0xFF5A1B08),
    secondaryContainer = Color(0xFF7C321D),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFA9C9EA),
    onTertiary = Color(0xFF12344F),
    background = Color(0xFF101614),
    onBackground = Color(0xFFE0E8E4),
    surface = Color(0xFF151C19),
    onSurface = Color(0xFFE0E8E4),
    surfaceVariant = Color(0xFF3E4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89958F),
    outlineVariant = Color(0xFF3E4945),
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
internal fun UnarchiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
