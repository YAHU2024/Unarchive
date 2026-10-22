package com.unarchive.android.ui.theme

import android.os.Build
import android.app.Activity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.unarchive.android.ui.components.GlassRenderer
import com.unarchive.android.ui.components.glassRendererFor
import androidx.core.view.WindowCompat

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
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF003777),
    secondary = Color(0xFF0060CF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF0060CF),
    tertiary = Color(0xFF406180),
    onTertiary = Color.White,
    background = LightSemanticColors.canvas,
    onBackground = LightSemanticColors.ink,
    surface = LightSemanticColors.surface,
    onSurface = LightSemanticColors.ink,
    surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF48484F),
    outline = LightSemanticColors.outline,
    outlineVariant = LightSemanticColors.outlineSubtle,
    error = LightSemanticColors.failure,
)

private val DarkColors = darkColorScheme(
    primary = DarkSemanticColors.brand,
    onPrimary = DarkSemanticColors.onBrand,
    primaryContainer = Color(0xFF123D70),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFF58A6FF),
    onSecondary = Color(0xFF001B3C),
    secondaryContainer = Color(0xFF303033),
    onSecondaryContainer = Color(0xFF58A6FF),
    tertiary = Color(0xFFA9C9EA),
    onTertiary = Color(0xFF12344F),
    background = DarkSemanticColors.canvas,
    onBackground = DarkSemanticColors.ink,
    surface = DarkSemanticColors.surface,
    onSurface = DarkSemanticColors.ink,
    surfaceVariant = Color(0xFF48484F),
    onSurfaceVariant = Color(0xFFAAAAB2),
    outline = DarkSemanticColors.outline,
    outlineVariant = DarkSemanticColors.outlineSubtle,
    error = DarkSemanticColors.failure,
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    )
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(UnarchiveShapes.small),
    small = RoundedCornerShape(UnarchiveShapes.medium),
    medium = RoundedCornerShape(UnarchiveShapes.medium),
    large = RoundedCornerShape(UnarchiveShapes.extraLarge),
    extraLarge = RoundedCornerShape(UnarchiveShapes.extraLarge),
)

@Composable
internal fun UnarchiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    liquidGlassEnabled: Boolean = true,
    motionPolicy: MotionPolicy = rememberSystemMotionPolicy(),
    glassInputs: LiquidGlassInputs = rememberSystemGlassInputs(liquidGlassEnabled),
    content: @Composable () -> Unit,
) {
    val policy = LiquidGlassPolicy.resolve(glassInputs.copy(userEnabled = liquidGlassEnabled))
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    SideEffect {
        if (window != null) {
            // Match the canvas color below the status bar and adapt icon contrast
            // whenever the app switches between light and dark themes.
            window.statusBarColor = if (Build.VERSION.SDK_INT >= 35) {
                android.graphics.Color.TRANSPARENT
            } else {
                (if (darkTheme) DarkSemanticColors.canvas else LightSemanticColors.canvas).toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(
        LocalLiquidGlassPolicy provides policy,
        LocalMotionPolicy provides motionPolicy,
        LocalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
        LocalLiquidGlassTokens provides LiquidGlassTokens(),
        LocalGlassRenderer provides glassRendererFor(policy.mode, policy.usesTransparency),
    ) {
        UnarchiveCupertinoTheme(darkTheme, if (darkTheme) DarkColors else LightColors) {
            MaterialTheme(
                colorScheme = if (darkTheme) DarkColors else LightColors,
                typography = AppTypography,
                shapes = AppShapes,
                content = content,
            )
        }
    }
}
