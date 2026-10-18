package com.unarchive.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.unarchive.android.ui.components.GlassRenderer
import com.unarchive.android.ui.components.glassRendererFor

internal val LocalLiquidGlassPolicy = staticCompositionLocalOf {
    LiquidGlassPolicy.fromUserSetting(enabled = true)
}
internal val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }
internal val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
internal val LocalGlassRenderer = staticCompositionLocalOf<GlassRenderer> {
    glassRendererFor(LiquidGlassMode.Disabled, usesTransparency = false)
}

/** Project-owned values for the bounded liquid-glass treatment. */
internal data class LiquidGlassTokens(
    val surfaceAlpha: Float = 0.78f,
    val emphasizedSurfaceAlpha: Float = 0.88f,
    val borderAlpha: Float = 0.62f,
    val emphasizedBorderAlpha: Float = 0.90f,
    val highlightAlpha: Float = 0.10f,
    val emphasizedHighlightAlpha: Float = 0.16f,
    val tertiaryHighlightAlpha: Float = 0.08f,
    val sheenAlpha: Float = 0.08f,
    val dockSurfaceAlpha: Float = 0.84f,
    val dockBorderAlpha: Float = 0.78f,
)

internal val LocalLiquidGlassTokens = staticCompositionLocalOf { LiquidGlassTokens() }

private val LightColors = lightColorScheme(
    primary = LightSemanticColors.brand,
    onPrimary = LightSemanticColors.onBrand,
    primaryContainer = Color(0xFFB8EEE3),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF9B4F2F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF391305),
    tertiary = Color(0xFF406180),
    onTertiary = Color.White,
    background = LightSemanticColors.canvas,
    onBackground = LightSemanticColors.ink,
    surface = LightSemanticColors.surface,
    onSurface = LightSemanticColors.ink,
    surfaceVariant = Color(0xFFDCE5E1),
    onSurfaceVariant = Color(0xFF3E4945),
    outline = LightSemanticColors.outline,
    outlineVariant = LightSemanticColors.outlineSubtle,
)

private val DarkColors = darkColorScheme(
    primary = DarkSemanticColors.brand,
    onPrimary = DarkSemanticColors.onBrand,
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFB8EEE3),
    secondary = Color(0xFFFFB59A),
    onSecondary = Color(0xFF5A1B08),
    secondaryContainer = Color(0xFF7C321D),
    onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFA9C9EA),
    onTertiary = Color(0xFF12344F),
    background = DarkSemanticColors.canvas,
    onBackground = DarkSemanticColors.ink,
    surface = DarkSemanticColors.surface,
    onSurface = DarkSemanticColors.ink,
    surfaceVariant = Color(0xFF3E4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = DarkSemanticColors.outline,
    outlineVariant = DarkSemanticColors.outlineSubtle,
)

private val AppTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(UnarchiveShapes.small),
    small = RoundedCornerShape(UnarchiveShapes.medium),
    medium = RoundedCornerShape(UnarchiveShapes.large),
    large = RoundedCornerShape(UnarchiveShapes.extraLarge),
    extraLarge = RoundedCornerShape(UnarchiveShapes.extraLarge),
)

@Composable
internal fun UnarchiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    liquidGlassEnabled: Boolean = true,
    motionPolicy: MotionPolicy = rememberSystemMotionPolicy(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLiquidGlassPolicy provides LiquidGlassPolicy.fromUserSetting(liquidGlassEnabled, Build.VERSION.SDK_INT),
        LocalMotionPolicy provides motionPolicy,
        LocalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
        LocalLiquidGlassTokens provides LiquidGlassTokens(),
        LocalGlassRenderer provides run {
            val policy = LiquidGlassPolicy.fromUserSetting(liquidGlassEnabled, Build.VERSION.SDK_INT)
            glassRendererFor(policy.mode, policy.usesTransparency)
        },
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
