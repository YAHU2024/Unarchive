<#
.SYNOPSIS
Builds and runs the Notes P1 Compose focus gate on one authorized device.

.DESCRIPTION
Reuses the established deterministic Android focus runner for JVM tests,
incremental APK installation, and focused instrumentation. It never uninstalls
the app or clears package data.

.EXAMPLE
.\android\scripts\notes_p1_focus_acceptance.ps1

.EXAMPLE
.\android\scripts\notes_p1_focus_acceptance.ps1 -DeviceSerial 5b14556a -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "d43_focus_acceptance.ps1"
$arguments = @{
    DeviceSerial = $DeviceSerial
    TestClass = @("com.unarchive.android.NotesLibraryUiTest")
    ReportGroup = "notes-p1-focus"
    SuccessMessage = "Notes P1 focus gate passed: filtering, metadata, and draft generation semantics are verified."
}
if ($SkipBuild) {
    $arguments.SkipBuild = $true
}

& $runner @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
