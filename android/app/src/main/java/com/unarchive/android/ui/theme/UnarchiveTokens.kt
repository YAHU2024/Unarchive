package com.unarchive.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Semantic project tokens. Pages should use MaterialTheme roles and these values, not raw colors. */
internal data class UnarchiveSemanticColors(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val outline: Color,
    val outlineSubtle: Color,
    val brand: Color,
    val onBrand: Color,
    val success: Color,
    val warning: Color,
    val failure: Color,
    val ai: Color,
    val user: Color,
    val source: Color,
)

internal val LightSemanticColors = UnarchiveSemanticColors(
    canvas = Color(0xFFF4F5F2), surface = Color(0xFFFCFCF8), surfaceRaised = Color.White,
    ink = Color(0xFF1B201D), inkMuted = Color(0xFF56615B), outline = Color(0xFF6F7B74),
    outlineSubtle = Color(0xFFC3CDC7), brand = Color(0xFF0B6257), onBrand = Color.White,
    success = Color(0xFF2F6B4F), warning = Color(0xFF8A5B17), failure = Color(0xFFBA1A1A),
    ai = Color(0xFF406180), user = Color(0xFF0B6257), source = Color(0xFF8A5B17),
)

internal val DarkSemanticColors = UnarchiveSemanticColors(
    canvas = Color(0xFF101614), surface = Color(0xFF171F1C), surfaceRaised = Color(0xFF1C2622),
    ink = Color(0xFFE6ECE8), inkMuted = Color(0xFFB6C1BB), outline = Color(0xFF89958F),
    outlineSubtle = Color(0xFF3E4945), brand = Color(0xFF8BD8CB), onBrand = Color(0xFF003731),
    success = Color(0xFF91D5A8), warning = Color(0xFFE7BE72), failure = Color(0xFFFFB4AB),
    ai = Color(0xFFA9C9EA), user = Color(0xFF8BD8CB), source = Color(0xFFE7BE72),
)

internal object UnarchiveDimensions {
    val spacing4 = 4.dp
    val spacing8 = 8.dp
    val spacing12 = 12.dp
    val spacing16 = 16.dp
    val spacing24 = 24.dp
    val touchTarget = 48.dp
    val dockHeight = 56.dp
}

internal object UnarchiveShapes {
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 24.dp
}
