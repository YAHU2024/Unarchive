<#
.SYNOPSIS
Runs the LG-B layout fixture gate without accessing user notes or credentials.
#>
[CmdletBinding()]
param([string]$DeviceSerial = "", [switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "LG-B build/JVM gate failed ($LASTEXITCODE)." }
    } finally { Pop-Location }
}
& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") `
    -DeviceSerial $DeviceSerial -SkipBuild -ReportGroup "lg-ab-layout" `
    -TestClass @(
        "com.unarchive.android.LiquidGlassLayoutUiTest",
        "com.unarchive.android.PageMigrationUiTest",
        "com.unarchive.android.D1NavigationTest",
        "com.unarchive.android.CreationFlowUiTest",
        "com.unarchive.android.NotesLibraryUiTest",
        "com.unarchive.android.LiquidGlassSettingsUiTest"
    ) `
    -SuccessMessage "LG-B fixture gate passed. Candidate material and user visual acceptance remain separate."
