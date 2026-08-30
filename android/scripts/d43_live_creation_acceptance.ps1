<#
.SYNOPSIS
Runs the opt-in real Bilibili-to-edited-note acceptance on one device.

.DESCRIPTION
Builds and incrementally installs the app/test APKs, runs the real creation and
edit/save test, force-stops the app, and verifies the saved title after a fresh
process launch. It never uninstalls the package or clears app data.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [string]$VideoReference = "BV1PS42197aM",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidRoot = Join-Path $repoRoot "android"
$adb = Join-Path $androidRoot ".android-sdk\platform-tools\adb.exe"
$packageName = "com.unarchive.android"
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "$packageName.LiveCreationAcceptanceTest"

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

if ($VideoReference -notmatch "^(BV[0-9A-Za-z]{10}|av[0-9]+|https://)") {
    throw "VideoReference must be a Bilibili BV/av ID or HTTPS URL."
}

Write-Host "Using device: $DeviceSerial"
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle live acceptance gate failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$abi = ((Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "getprop", "ro.product.cpu.abi")) -join "").Trim()
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

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$titlePrefix = "D4-$stamp-"
$reportDirectory = Join-Path $androidRoot "build\reports\d43-live"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$reportPath = Join-Path $reportDirectory "phq110-$stamp.txt"

function Invoke-LiveTest {
    param([Parameter(Mandatory = $true)][string]$Method)
    $arguments = @(
        "-s", $DeviceSerial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "$testClass#$Method",
        "-e", "live_acceptance", "true",
        "-e", "video_reference", $VideoReference,
        "-e", "title_prefix", $titlePrefix,
        $runner
    )
    $output = & $adb @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | Tee-Object -FilePath $reportPath -Append
    if ($exitCode -ne 0 -or ($output -join "`n") -notmatch "OK \(1 test\)") {
        throw "Live acceptance method failed: $Method"
    }
}

Write-Host "Running real create/edit/save acceptance for: $VideoReference"
Invoke-LiveTest -Method "createEditAndSave"

Write-Host "Force-stopping app before persistence verification"
Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "force-stop", $packageName) | Out-Null

Write-Host "Verifying saved note after process restart"
Invoke-LiveTest -Method "verifySavedNoteAfterProcessRestart"

Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "start", "-n", "$packageName/.MainActivity") | Write-Host

Write-Host "Live creation acceptance passed. Report: $reportPath"
Write-Host "Saved acceptance Markdown marker: <!-- $titlePrefix -->"
