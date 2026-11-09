package com.unarchive.android.ui.theme

/** The implementation selected for bounded decorative surfaces. */
internal enum class LiquidGlassMode {
    Disabled,
    Simulated,
    Blur,
    LiquidGlass,
}

internal enum class PerformanceTier { Normal, Constrained }

internal data class LiquidGlassInputs(
    val userEnabled: Boolean,
    val apiLevel: Int,
    val performanceTier: PerformanceTier = PerformanceTier.Normal,
    val reducedTransparency: Boolean = false,
    val highContrast: Boolean = false,
)

internal data class LiquidGlassPolicy(
    val mode: LiquidGlassMode,
    val usesTransparency: Boolean,
) {
    val isDecorativeEnabled: Boolean get() = mode != LiquidGlassMode.Disabled

    companion object {
        fun resolve(inputs: LiquidGlassInputs): LiquidGlassPolicy {
            if (!inputs.userEnabled || inputs.reducedTransparency || inputs.highContrast) {
                return LiquidGlassPolicy(LiquidGlassMode.Disabled, usesTransparency = false)
            }
            if (inputs.performanceTier == PerformanceTier.Constrained) {
                return LiquidGlassPolicy(LiquidGlassMode.Simulated, usesTransparency = false)
            }
            if (inputs.apiLevel < 31) {
                return LiquidGlassPolicy(LiquidGlassMode.Simulated, usesTransparency = false)
            }
            return if (inputs.apiLevel >= 33) {
                LiquidGlassPolicy(LiquidGlassMode.LiquidGlass, usesTransparency = true)
            } else {
                LiquidGlassPolicy(LiquidGlassMode.Blur, usesTransparency = true)
            }
        }

        fun fromUserSetting(enabled: Boolean, apiLevel: Int = 35): LiquidGlassPolicy = resolve(
            LiquidGlassInputs(userEnabled = enabled, apiLevel = apiLevel),
        )
    }
}

internal enum class MotionMode { Full, Reduced }

internal data class MotionPolicy(
    val mode: MotionMode = MotionMode.Full,
    val shortDurationMillis: Int = 180,
    val standardDurationMillis: Int = 260,
    val springStiffness: Float = 500f,
    val springDampingRatio: Float = 0.82f,
    val pageTravelFraction: Float = 0.18f,
    val secondaryPageEnterDurationMillis: Int = 380,
    val secondaryPageExitDurationMillis: Int = 200,
    val secondaryPageTravelFraction: Float = 0.22f,
    val stateTravelDp: Float = 8f,
    val stateInitialAlpha: Float = 0.92f,
    val sheetTravelDp: Float = 32f,
) {
    val animateLayout: Boolean get() = mode == MotionMode.Full

    companion object {
        val Reduced = MotionPolicy(
            mode = MotionMode.Reduced,
            shortDurationMillis = 0,
            standardDurationMillis = 0,
            springStiffness = 1000f,
            springDampingRatio = 1f,
            pageTravelFraction = 0f,
            secondaryPageEnterDurationMillis = 0,
            secondaryPageExitDurationMillis = 0,
            secondaryPageTravelFraction = 0f,
            stateTravelDp = 0f,
            stateInitialAlpha = 1f,
            sheetTravelDp = 0f,
        )

        fun fromAnimatorDurationScale(scale: Float): MotionPolicy =
            if (scale <= 0f) Reduced else MotionPolicy()
    }
}
