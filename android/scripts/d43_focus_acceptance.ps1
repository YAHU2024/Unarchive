<#
.SYNOPSIS
Builds and runs the repeatable D4.3 Android focus gate on one authorized device.

.DESCRIPTION
This runner covers the deterministic portion of D4.3: JVM tests, Debug APK
assembly, instrumentation APK assembly, incremental installation, app launch,
and focused Compose tests. It deliberately does not uninstall the app, clear
package data, or claim real share-receiver/ima API acceptance.

.EXAMPLE
.\android\scripts\d43_focus_acceptance.ps1

.EXAMPLE
.\android\scripts\d43_focus_acceptance.ps1 -DeviceSerial 5b14556a -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [switch]$SkipBuild,
    [string]$ReportGroup = "d43-focus",
    [string]$SuccessMessage = "D4.3 deterministic focus gate passed. WPS and real ima acceptance are recorded by their separate gates.",
    [string[]]$TestClass = @(
        "com.unarchive.android.D1NavigationTest",
        "com.unarchive.android.NoteEditorUiTest",
        "com.unarchive.android.GraphRelationUiTest",
        "com.unarchive.android.DestinationAndSecurityUiTest",
        "com.unarchive.android.AccessibilityAndSecurityUiTest"
    )
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidRoot = Join-Path $repoRoot "android"
$adb = Join-Path $androidRoot ".android-sdk\platform-tools\adb.exe"
$packageName = "com.unarchive.android"
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"

if (-not (Test-Path $adb)) {
    throw "Repository-local adb was not found: $adb"
}

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & $adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return @($output)
}

$deviceLines = @(& $adb devices | Where-Object { $_ -match "^\S+\s+device\s*$" })
if ($DeviceSerial) {
    if (-not ($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+device\s*$" })) {
        throw "Requested device is not connected and authorized: $DeviceSerial"
    }
} elseif ($deviceLines.Count -eq 1) {
    $DeviceSerial = ($deviceLines[0] -split "\s+")[0]
} else {
    throw "Connect exactly one authorized Android device or pass -DeviceSerial; found $($deviceLines.Count)."
}

Write-Host "Using device: $DeviceSerial"

if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle focus gate failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$abiOutput = Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "getprop", "ro.product.cpu.abi")
$abi = ($abiOutput -join "").Trim()
$apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-$abi-debug.apk"
if (-not (Test-Path $apk)) {
    $apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-universal-debug.apk"
}
$testApk = Join-Path $androidRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
foreach ($path in @($apk, $testApk)) {
    if (-not (Test-Path $path)) { throw "Expected APK was not found: $path" }
}

Write-Host "Installing app APK incrementally: $apk"
Invoke-Adb -Arguments @("-s", $DeviceSerial, "install", "-r", $apk) | Write-Host
Write-Host "Installing instrumentation APK incrementally: $testApk"
Invoke-Adb -Arguments @("-s", $DeviceSerial, "install", "-r", $testApk) | Write-Host

Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "start", "-n", "$packageName/.MainActivity") | Write-Host

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDirectory = Join-Path $androidRoot "build\reports\$ReportGroup"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$reportPath = Join-Path $reportDirectory "phq110-$stamp.txt"
$classArgument = $TestClass -join ","
$instrumentArguments = @(
    "-s", $DeviceSerial, "shell", "am", "instrument", "-w", "-r",
    "-e", "class", $classArgument, $runner
)

Write-Host "Running focused instrumentation: $classArgument"
$instrumentOutput = & $adb @instrumentArguments 2>&1
$instrumentExitCode = $LASTEXITCODE
$instrumentOutput | Tee-Object -FilePath $reportPath
Write-Host "Focus report: $reportPath"
$instrumentText = $instrumentOutput -join "`n"
$failureReasons = @()
if ($instrumentExitCode -ne 0) {
    $failureReasons += "adb exited with code $instrumentExitCode"
}
if ($instrumentText -notmatch "(?m)^OK \(\d+ tests?\)\s*$") {
    $failureReasons += "the AndroidJUnitRunner success summary is missing"
}
if ($instrumentText -match "(?m)^FAILURES!!!\s*$") {
    $failureReasons += "AndroidJUnitRunner reported test failures"
}
if ($instrumentText -match "(?m)^INSTRUMENTATION_STATUS_CODE: -2\s*$") {
    $failureReasons += "at least one instrumentation test failed"
}
if ($instrumentText -match "(?m)^(INSTRUMENTATION_FAILED:|INSTRUMENTATION_RESULT: shortMsg=)") {
    $failureReasons += "instrumentation failed to run to completion"
}
if ($instrumentText -notmatch "(?m)^INSTRUMENTATION_CODE: -1\s*$") {
    $failureReasons += "the instrumentation success result code is missing"
}
if ($failureReasons.Count -gt 0) {
    throw "Focused instrumentation failed: $($failureReasons -join '; '). See $reportPath"
}

Write-Host $SuccessMessage
