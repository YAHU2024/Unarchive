<#
.SYNOPSIS
Builds D-C6 and runs fixture-based page migration regression on one device.
.EXAMPLE
.\android\scripts\d_c6_focus_regression.ps1 -Slice All
#>
[CmdletBinding()]
param(
    [ValidateSet("Library", "Editor", "Settings", "Delivery", "All")]
    [string]$Slice = "All",
    [string]$DeviceSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "D-C6 JVM/build failed ($LASTEXITCODE)." }
    } finally { Pop-Location }
}

$library = @(
    "com.unarchive.android.CreationFlowUiTest",
    "com.unarchive.android.D1NavigationTest",
    "com.unarchive.android.NotesLibraryUiTest",
    "com.unarchive.android.IosLikeComponentsUiTest",
    "com.unarchive.android.MotionStateUiTest"
)
$editor = @(
    "com.unarchive.android.CreationEditorFlowUiTest",
    "com.unarchive.android.editor.MarkdownEditorUiTest",
    "com.unarchive.android.NoteEditorUiTest",
    "com.unarchive.android.MotionNavigationUiTest"
)
$settings = @(
    "com.unarchive.android.PageMigrationUiTest",
    "com.unarchive.android.LiquidGlassSettingsUiTest",
    "com.unarchive.android.SiliconFlowModelSettingsUiTest"
)
$delivery = @(
    "com.unarchive.android.GraphRelationUiTest",
    "com.unarchive.android.DestinationAndSecurityUiTest",
    "com.unarchive.android.AccessibilityAndSecurityUiTest",
    "com.unarchive.android.NoteCoverUiTest"
)
$testClasses = switch ($Slice) {
    "Library" { $library }
    "Editor" { $editor }
    "Settings" { $settings }
    "Delivery" { $delivery }
    "All" { @($library + $editor + $settings + $delivery | Select-Object -Unique) }
}

& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") `
    -DeviceSerial $DeviceSerial -SkipBuild -ReportGroup "d-c6-$($Slice.ToLowerInvariant())" `
    -TestClass $testClasses `
    -SuccessMessage "D-C6 $Slice automated regression passed. Final visual acceptance remains with the user."
