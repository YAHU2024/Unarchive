<#
.SYNOPSIS
Runs the small D-C4 motion/navigation regression slices, without full UI acceptance.
.EXAMPLE
.\android\scripts\d_c4_focus_regression.ps1 -Slice Navigation
.EXAMPLE
.\android\scripts\d_c4_focus_regression.ps1 -Slice State -SkipBuild
#>
[CmdletBinding()]
param(
    [ValidateSet("Navigation", "State", "All")]
    [string]$Slice = "All",
    [string]$DeviceSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest `
            --tests com.unarchive.android.ui.theme.UiPoliciesTest `
            --tests com.unarchive.android.ui.components.GlassRendererTest `
            :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "D-C4 focused build/JVM regression failed ($LASTEXITCODE)." }
    } finally {
        Pop-Location
    }
}

$navigationTests = @(
    "com.unarchive.android.MotionNavigationUiTest",
    "com.unarchive.android.D1NavigationTest",
    "com.unarchive.android.LiquidGlassSettingsUiTest",
    "com.unarchive.android.NotesLibraryUiTest"
)
$stateTests = @(
    "com.unarchive.android.MotionStateUiTest",
    "com.unarchive.android.IosLikeComponentsUiTest",
    "com.unarchive.android.D1NavigationTest",
    "com.unarchive.android.NotesLibraryUiTest"
)
$testClasses = switch ($Slice) {
    "Navigation" { $navigationTests }
    "State" { $stateTests }
    "All" { @($navigationTests + $stateTests | Select-Object -Unique) }
}

& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") `
    -DeviceSerial $DeviceSerial -SkipBuild `
    -ReportGroup "d-c4-$($Slice.ToLowerInvariant())" -TestClass $testClasses `
    -SuccessMessage "D-C4 $Slice focused regression passed. Full UI/manual/business acceptance remains deferred."
