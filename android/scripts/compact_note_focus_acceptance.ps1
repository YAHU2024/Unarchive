<#
.SYNOPSIS
Builds and runs compact Create/Notes item regression on one authorized device.

.DESCRIPTION
Uses synthetic fixtures for compact geometry, bounded previews, cover retry,
editor/material reachability and surrounding root-page navigation. Installation
preserves package data. Real-service and user visual acceptance remain separate.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$arguments = @{
    DeviceSerial = $DeviceSerial
    ReportGroup = "compact-note-focus"
    TestClass = @(
        "com.unarchive.android.CompactNoteItemUiTest",
        "com.unarchive.android.NoteCoverUiTest",
        "com.unarchive.android.NotesLibraryUiTest",
        "com.unarchive.android.NotesLibraryScreenUiTest",
        "com.unarchive.android.CreationEditorFlowUiTest",
        "com.unarchive.android.CreationFlowUiTest",
        "com.unarchive.android.PageMigrationUiTest",
        "com.unarchive.android.RootTitleScrollUiTest",
        "com.unarchive.android.D1NavigationTest"
    )
    SuccessMessage = "Compact note focused gate passed. User visual, TalkBack speech and live-service acceptance remain separate."
}
if ($SkipBuild) { $arguments.SkipBuild = $true }
& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
