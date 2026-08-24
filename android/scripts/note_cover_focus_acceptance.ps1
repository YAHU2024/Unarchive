<#
.SYNOPSIS
Builds and runs the deterministic note-cover Compose focus gate on one device.

.DESCRIPTION
Reuses the established focus runner for JVM tests, incremental APK installation,
and NoteCoverUiTest instrumentation. It covers cover priority, chapter/placeholder
fallback, retry semantics, editor reachability, and 2x-font layout. It never
uninstalls the app or clears package data and does not claim real Bilibili,
offline, or process-restart acceptance.

.EXAMPLE
.\android\scripts\note_cover_focus_acceptance.ps1

.EXAMPLE
.\android\scripts\note_cover_focus_acceptance.ps1 -DeviceSerial 5b14556a -SkipBuild
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
    TestClass = @("com.unarchive.android.NoteCoverUiTest")
    ReportGroup = "note-cover-focus"
    SuccessMessage = "Note cover focus gate passed: priority, fallback, retry, editor access, and 2x-font reachability are verified."
}
if ($SkipBuild) {
    $arguments.SkipBuild = $true
}

& $runner @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
