<#
.SYNOPSIS
Runs deterministic first-stage Cupertino acceptance; user visual and live-service gates remain separate.
#>
[CmdletBinding()]
param([string]$DeviceSerial = "", [switch]$SkipBuild)
$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "Cupertino JVM/build gate failed ($LASTEXITCODE)." }
    } finally { Pop-Location }
}
& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") `
    -DeviceSerial $DeviceSerial -SkipBuild -ReportGroup "cupertino-stage1" `
    -TestClass @(
        "com.unarchive.android.CupertinoAcceptanceUiTest",
        "com.unarchive.android.LiquidGlassLayoutUiTest",
        "com.unarchive.android.CreationFlowUiTest",
        "com.unarchive.android.D1NavigationTest",
        "com.unarchive.android.NotesLibraryUiTest",
        "com.unarchive.android.IosLikeComponentsUiTest",
        "com.unarchive.android.MotionStateUiTest",
        "com.unarchive.android.CreationEditorFlowUiTest",
        "com.unarchive.android.editor.MarkdownEditorUiTest",
        "com.unarchive.android.NoteEditorUiTest",
        "com.unarchive.android.MotionNavigationUiTest",
        "com.unarchive.android.PageMigrationUiTest",
        "com.unarchive.android.LiquidGlassSettingsUiTest",
        "com.unarchive.android.SiliconFlowModelSettingsUiTest",
        "com.unarchive.android.GraphRelationUiTest",
        "com.unarchive.android.DestinationAndSecurityUiTest",
        "com.unarchive.android.AccessibilityAndSecurityUiTest",
        "com.unarchive.android.NoteCoverUiTest"
    ) `
    -SuccessMessage "Cupertino deterministic gate passed. API 31, user visual, TalkBack, performance and live-service acceptance remain separately recorded."
