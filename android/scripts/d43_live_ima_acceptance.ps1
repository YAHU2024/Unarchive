<#
.SYNOPSIS
Runs opt-in real ima synchronization and recovery acceptance on one device.

.DESCRIPTION
Builds and incrementally installs the app/test APKs, synchronizes the latest
formally saved Markdown revision to the app's configured ima target, force-stops the app,
verifies durable state in a fresh process, and checks invalid-to-valid
credential connection recovery without changing stored credentials. It never
uninstalls the package or clears app data. A successful run may append the
current revision to an existing ima note or create/associate a note remotely.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = "",
    [string]$VideoReference = "BV1PS42197aM",
    [switch]$ImportConfigurationFromEnv,
    [switch]$CreateRevisionForAppend,
    [switch]$TestNetworkRecovery,
    [switch]$ExpectRevisionAppend,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidRoot = Join-Path $repoRoot "android"
$adb = Join-Path $androidRoot ".android-sdk\platform-tools\adb.exe"
$packageName = "com.unarchive.android"
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "$packageName.LiveImaAcceptanceTest"

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

if ($VideoReference -notmatch "BV[0-9A-Za-z]{10}") {
    throw "VideoReference must contain a Bilibili BV ID."
}

Write-Host "Using device: $DeviceSerial"
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        & .\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle live ima acceptance gate failed with exit code $LASTEXITCODE"
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
$reportDirectory = Join-Path $androidRoot "build\reports\d43-ima-live"
New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$reportPath = Join-Path $reportDirectory "phq110-$stamp.txt"

function Invoke-LiveImaTest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [string[]]$ExtraArguments = @()
    )
    $arguments = @(
        "-s", $DeviceSerial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "$testClass#$Method",
        "-e", "live_ima_acceptance", "true",
        "-e", "video_reference", $VideoReference,
        "-e", "run_id", $stamp
    )
    $arguments += $ExtraArguments
    if ($ExpectRevisionAppend) {
        $arguments += @("-e", "expect_revision_append", "true")
    }
    $arguments += $runner
    $output = & $adb @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | Tee-Object -FilePath $reportPath -Append
    if ($exitCode -ne 0 -or ($output -join "`n") -notmatch "OK \(1 test\)") {
        throw "Live ima acceptance method failed: $Method"
    }
}

if ($ImportConfigurationFromEnv) {
    $envPath = Join-Path $repoRoot ".env"
    if (-not (Test-Path $envPath)) {
        throw "Cannot import ima configuration because .env was not found."
    }
    function Read-DotEnvValue {
        param([Parameter(Mandatory = $true)][string]$Name)
        $line = Get-Content $envPath | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s*=" } | Select-Object -Last 1
        if (-not $line) { return "" }
        $value = ($line -split "=", 2)[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        return $value
    }
    $config = [ordered]@{
        client_id = Read-DotEnvValue "IMA_CLIENT_ID"
        api_key = Read-DotEnvValue "IMA_API_KEY"
        knowledge_base_id = Read-DotEnvValue "IMA_KNOWLEDGE_BASE_ID"
        folder_id = Read-DotEnvValue "IMA_KNOWLEDGE_BASE_FOLDER_ID"
    }
    if ([string]::IsNullOrWhiteSpace($config.client_id) -or
        [string]::IsNullOrWhiteSpace($config.api_key) -or
        [string]::IsNullOrWhiteSpace($config.knowledge_base_id)) {
        throw "The .env ima configuration is incomplete."
    }
    $configJson = $config | ConvertTo-Json -Compress
    $configBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($configJson))
    Write-Host "Importing ima credentials into Android Keystore without logging secret values"
    Invoke-LiveImaTest -Method "importConfiguredImaProfile" -ExtraArguments @(
        "-e", "ima_config_base64", $configBase64
    )
}

if ($CreateRevisionForAppend -or $TestNetworkRecovery) {
    Write-Host "Saving a new local content revision for append acceptance"
    Invoke-LiveImaTest -Method "saveNewLocalRevisionForAppendAcceptance"
    $ExpectRevisionAppend = $true
}

if ($TestNetworkRecovery) {
    $wifiStatus = (Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "cmd", "wifi", "status")) -join "`n"
    $wifiWasEnabled = $wifiStatus -match "Wifi is enabled"
    $mobileDataWasEnabled = (((Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "settings", "get", "global", "mobile_data")) -join "").Trim() -eq "1")
    try {
        Write-Host "Temporarily disabling Wi-Fi and mobile data for retryable-failure acceptance"
        Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "svc", "wifi", "disable") | Out-Null
        Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "svc", "data", "disable") | Out-Null
        Start-Sleep -Seconds 2
        Invoke-LiveImaTest -Method "offlineSyncPersistsRetryableFailure"
        Write-Host "Force-stopping app with the retryable ima state persisted"
        Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "force-stop", $packageName) | Out-Null
    } finally {
        Write-Host "Restoring the device's original network state"
        Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "svc", "wifi", $(if ($wifiWasEnabled) { "enable" } else { "disable" })) | Out-Null
        Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "svc", "data", $(if ($mobileDataWasEnabled) { "enable" } else { "disable" })) | Out-Null
    }
    Start-Sleep -Seconds 5
}

Write-Host "Synchronizing the latest local revision to the configured ima target"
Invoke-LiveImaTest -Method "syncCurrentRevisionToConfiguredTarget"

Write-Host "Force-stopping app before ima state verification"
Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "force-stop", $packageName) | Out-Null

Write-Host "Verifying configured-target state after process restart"
Invoke-LiveImaTest -Method "verifyConfiguredTargetAfterProcessRestart"

Write-Host "Verifying invalid-to-valid credential connection recovery"
Invoke-LiveImaTest -Method "invalidThenValidCredentialConnectionRecoversWithoutChangingStoredSecrets"

Invoke-Adb -Arguments @("-s", $DeviceSerial, "shell", "am", "start", "-n", "$packageName/.MainActivity") | Write-Host

Write-Host "Live ima acceptance passed. Report: $reportPath"
