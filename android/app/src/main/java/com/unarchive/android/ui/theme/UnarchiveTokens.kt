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
    val actionFill: Color,
    val success: Color,
    val warning: Color,
    val failure: Color,
    val ai: Color,
    val user: Color,
    val source: Color,
)

internal val LightSemanticColors = UnarchiveSemanticColors(
    canvas = Color(0xFFF2F2F7), surface = Color(0xFFFFFFFF), surfaceRaised = Color.White,
    ink = Color(0xFF141416), inkMuted = Color(0xFF6C6C74), outline = Color(0xFF73737D),
    outlineSubtle = Color(0xFFD6D7DC), brand = Color(0xFF0060CF), onBrand = Color.White,
    actionFill = Color(0xFF0064D8),
    success = Color(0xFF2F6B4F), warning = Color(0xFF8A5B17), failure = Color(0xFFB92D28),
    ai = Color(0xFF406180), user = Color(0xFF0060CF), source = Color(0xFF8A5B17),
)

internal val DarkSemanticColors = UnarchiveSemanticColors(
    canvas = Color(0xFF000000), surface = Color(0xFF1C1C1E), surfaceRaised = Color(0xFF2C2C2E),
    ink = Color(0xFFF5F5F7), inkMuted = Color(0xFFAAAAB2), outline = Color(0xFF9898A2),
    outlineSubtle = Color(0xFF38383A), brand = Color(0xFF58A6FF), onBrand = Color(0xFF001B3C),
    actionFill = Color(0xFF0963CE),
    success = Color(0xFF91D5A8), warning = Color(0xFFE7BE72), failure = Color(0xFFFF6961),
    ai = Color(0xFFA9C9EA), user = Color(0xFF58A6FF), source = Color(0xFFE7BE72),
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
