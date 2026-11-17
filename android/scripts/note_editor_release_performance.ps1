<#
.SYNOPSIS
Measures the minified release MarkdownNoteRoute with synthetic fixtures.
.DESCRIPTION
Builds the opt-in release instrumentation target, verifies target/test/installed
signatures, installs with -r, and keeps user data. Only this run's synthetic JSON,
gfxinfo and meminfo are exported. No logcat, private repositories, or credentials
are collected. A completed measurement is separate from performance acceptance.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = '',
    [ValidateSet('Both', 'Long', 'Image')][string]$Scenario = 'Both',
    [ValidateSet('VendorTouch', 'Protocol')][string]$InputMode = 'VendorTouch',
    [ValidateRange(1, 10)][int]$Rounds = 3,
    [ValidateRange(1, 10)][int]$SamplesPerPosition = 3,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string]$AppApk = '',
    [string]$TestApk = '',
    [string]$CompareResult = ''
)

$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $androidRoot '.android-sdk\platform-tools\adb.exe'
$packageName = 'com.unarchive.android'
$testRunner = "$packageName.test/com.unarchive.android.editor.performance.NoteReleasePerformanceInstrumentation"
$testClass = 'com.unarchive.android.editor.NoteReleasePerformanceTest'
$inputArgument = if ($InputMode -eq 'VendorTouch') { 'vendorTouch' } else { 'protocol' }
$scenarioNames = switch ($Scenario) { 'Long' { @('long') }; 'Image' { @('image') }; default { @('long', 'image') } }
if (-not (Test-Path -LiteralPath $adb)) { throw "Missing adb: $adb" }
$buildTools = Join-Path $androidRoot '.android-sdk\build-tools'
$apksigner = Get-ChildItem -LiteralPath $buildTools -Directory |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $apksigner) { throw 'Android build-tools apksigner is required.' }

function Invoke-ReleaseAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = & $adb -s $DeviceSerial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($lines -join [Environment]::NewLine)" }
    return ($lines -join [Environment]::NewLine)
}

function Get-ApkCertificate {
    param([Parameter(Mandatory = $true)][string]$Path)
    $lines = & $apksigner verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $Path" }
    $signers = [regex]::Matches(($lines -join "`n"), '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]+)\s*$')
    if ($signers.Count -ne 1) { throw 'This runner requires one unambiguous APK signing certificate.' }
    return $signers[0].Groups[1].Value.ToLowerInvariant()
}

function Get-InstalledBasePath {
    $paths = (Invoke-ReleaseAdb -Arguments @('shell', 'pm', 'path', $packageName)) -split '\r?\n' |
        Where-Object { $_ -match '^package:/data/app/[A-Za-z0-9_./=+~-]+\.apk$' }
    if (@($paths).Count -eq 0) { return $null }
    $base = @($paths | Where-Object { $_ -match '/base\.apk$' })
    if ($base.Count -ne 1) { throw 'Installed target has no unique base APK path.' }
    return $base[0].Substring('package:'.Length)
}

function Assert-MeasurementReport {
    param([Parameter(Mandatory = $true)]$Report, [Parameter(Mandatory = $true)][string]$Name)
    if ($Report.runId -ne $runId -or $Report.scenario -ne $Name -or $Report.productionRoute -ne 'MarkdownNoteRoute' -or
        $Report.buildType -ne 'release' -or $Report.inputMode -ne $inputArgument -or $Report.passed -ne $true) {
        throw "Missing or invalid completed production report: $Name"
    }
    if (@($Report.roundMeasurements).Count -ne $Rounds) { throw "Wrong round count: $Name" }
    foreach ($round in $Report.roundMeasurements) {
        if ($round.passed -ne $true -or $round.fixtureCodePoints -ne 110447 -or
            @($round.inputs).Count -ne (3 * $SamplesPerPosition) -or $round.frames.validDeadlineFrames -le 0 -or
            $round.memory.sampleCount -le 0 -or $round.sameNativeView -ne $true -or $round.sameEditable -ne $true) {
            throw "Incomplete source/native/frame/memory evidence: $Name round $($round.round)"
        }
        foreach ($sample in $round.inputs) {
            if ($sample.position -notin @('head', 'middle', 'tail') -or $sample.inputToAcceptedMs -lt 0 -or
                $sample.inputToObservedDrawMs -lt $sample.inputToAcceptedMs) { throw "Invalid input timing: $Name" }
        }
    }
    foreach ($position in @('head', 'middle', 'tail')) {
        if ($Report.summary.$position.inputToAcceptedMs.n -ne ($Rounds * $SamplesPerPosition) -or
            $Report.summary.$position.inputToObservedDrawMs.n -ne ($Rounds * $SamplesPerPosition)) { throw "Wrong timing sample count: $Name/$position" }
    }
}

$authorized = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($DeviceSerial) {
    if (-not ($authorized | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+device\s*$" })) {
        throw "Requested device is not connected and authorized: $DeviceSerial"
    }
} elseif ($authorized.Count -eq 1) { $DeviceSerial = ($authorized[0] -split '\s+')[0] }
else { throw "Connect one authorized device or pass -DeviceSerial; found $($authorized.Count)." }

$runId = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-release-$($InputMode.ToLowerInvariant())"
$reportRoot = Join-Path $androidRoot "build\reports\note-editor-release\$runId"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$originalIme = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
$metadata = [ordered]@{
    schemaVersion = 1
    runId = $runId
    deviceSerial = $DeviceSerial
    model = (Invoke-ReleaseAdb -Arguments @('shell', 'getprop', 'ro.product.model')).Trim()
    sdk = (Invoke-ReleaseAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    abi = (Invoke-ReleaseAdb -Arguments @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
    screenSize = (Invoke-ReleaseAdb -Arguments @('shell', 'wm', 'size')).Trim()
    screenDensity = (Invoke-ReleaseAdb -Arguments @('shell', 'wm', 'density')).Trim()
    fontScale = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'system', 'font_scale')).Trim()
    peakRefreshRate = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'system', 'peak_refresh_rate')).Trim()
    minimumRefreshRate = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'system', 'min_refresh_rate')).Trim()
    buildType = 'release'
    inputMode = $inputArgument
    scenarioNames = $scenarioNames
    rounds = $Rounds
    samplesPerPosition = $SamplesPerPosition
    measurementsCompleted = $false
    testCount = 0
    skipBuild = [bool]$SkipBuild
    skipInstall = [bool]$SkipInstall
    performanceAcceptance = 'measurements only; no latency threshold or final user acceptance evaluated'
    externalServiceAcceptance = 'not run'
    manualVisualTalkBackWritingAcceptance = 'not run'
}
$scenarioReports = [ordered]@{}

try {
    if ($InputMode -eq 'VendorTouch' -and ($metadata.model -ne 'PHQ110' -or $originalIme -ne 'com.baidu.input_oppo/.ImeService')) {
        throw 'VendorTouch requires the observed PHQ110/Baidu full-pinyin surface. Use -InputMode Protocol explicitly for a separately labelled IME protocol baseline.'
    }
    if (-not $SkipBuild) {
        Push-Location $androidRoot
        try {
            & .\gradlew.bat :app:assembleRelease :app:assembleReleaseAndroidTest -PnotePerformanceBuildType=release -PenableNotePerformanceHarness=true --console=plain 2>&1 |
                Tee-Object -FilePath (Join-Path $reportRoot 'build.txt') | Write-Output
            if ($LASTEXITCODE -ne 0) { throw "Minified release build failed ($LASTEXITCODE)." }
        } finally { Pop-Location }
    }

    if (-not $AppApk) { $AppApk = Join-Path $androidRoot "app\build\outputs\apk\release\app-$($metadata.abi)-release.apk" }
    if (-not (Test-Path -LiteralPath $AppApk)) { throw "Missing signed ABI release APK: $AppApk" }
    if (-not $TestApk) { $TestApk = Join-Path $androidRoot 'app\build\outputs\apk\androidTest\release\app-release-androidTest.apk' }
    if (-not (Test-Path -LiteralPath $TestApk)) { throw "Missing release instrumentation APK: $TestApk" }
    $mapping = Join-Path $androidRoot 'app\build\outputs\mapping\release\mapping.txt'
    if (-not (Test-Path -LiteralPath $mapping) -or (Get-Item -LiteralPath $mapping).Length -eq 0) { throw 'R8 release mapping evidence is missing or empty.' }
    $targetCertificate = Get-ApkCertificate -Path $AppApk
    if ((Get-ApkCertificate -Path $TestApk) -ne $targetCertificate) { throw 'Release target and test APK signatures differ; do not install or clear data.' }
    $metadata.appApkSha256 = (Get-FileHash -LiteralPath $AppApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata.testApkSha256 = (Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata.mappingSha256 = (Get-FileHash -LiteralPath $mapping -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata.signerSha256 = $targetCertificate

    $beforePath = Get-InstalledBasePath
    if ($beforePath) {
        $installedCopy = Join-Path $reportRoot 'installed-signature-check.apk'
        try {
            Invoke-ReleaseAdb -Arguments @('pull', $beforePath, $installedCopy) | Write-Output
            if ((Get-ApkCertificate -Path $installedCopy) -ne $targetCertificate) {
                throw 'Installed app signature differs from this release APK. Stopped without uninstalling or clearing user data.'
            }
        } finally {
            # One generated file only; no recursive or computed-directory deletion.
            if (Test-Path -LiteralPath $installedCopy) { Remove-Item -LiteralPath $installedCopy }
        }
    }
    if (-not $SkipInstall) {
        foreach ($apk in @($AppApk, $TestApk)) {
            $installed = Invoke-ReleaseAdb -Arguments @('install', '-r', $apk)
            $installed | Write-Output
            if ($installed -notmatch '(?m)^Success\s*$') { throw "Installation did not report success: $apk" }
        }
    }
    $installedPath = Get-InstalledBasePath
    if (-not $installedPath) { throw 'Target APK is not installed.' }
    $deviceHash = (Invoke-ReleaseAdb -Arguments @('shell', 'sha256sum', $installedPath)).Trim()
    $deviceHashMatch = [regex]::Match($deviceHash, '^([0-9a-fA-F]{64})\s')
    if (-not $deviceHashMatch.Success -or $deviceHashMatch.Groups[1].Value.ToLowerInvariant() -ne $metadata.appApkSha256) {
        throw 'Installed target does not match the exact local release APK; measurement stopped.'
    }
    Invoke-ReleaseAdb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'framestats') |
        Set-Content -LiteralPath (Join-Path $reportRoot 'gfxinfo-before.txt') -Encoding utf8
    Invoke-ReleaseAdb -Arguments @('shell', 'dumpsys', 'meminfo', $packageName) |
        Set-Content -LiteralPath (Join-Path $reportRoot 'meminfo-before.txt') -Encoding utf8
    Invoke-ReleaseAdb -Arguments @('shell', 'dumpsys', 'gfxinfo', $packageName, 'reset') | Out-Null

    $selection = switch ($Scenario) {
        'Long' { "$testClass#longProductionRouteReleasePerformance" }
        'Image' { "$testClass#imageProductionRouteReleasePerformance" }
        default { $testClass }
    }
    & $adb -s $DeviceSerial shell am instrument -w -r -e class $selection `
        -e noteReleaseRun $runId -e noteReleaseBuildType release -e noteReleaseInputMode $inputArgument `
        -e noteReleaseRounds $Rounds -e noteReleaseSamples $SamplesPerPosition $testRunner 2>&1 |
        Tee-Object -FilePath (Join-Path $reportRoot 'instrumentation.txt') | Write-Output
    $instrumentExit = $LASTEXITCODE
    $instrumentText = Get-Content -LiteralPath (Join-Path $reportRoot 'instrumentation.txt') -Raw
    $success = [regex]::Match($instrumentText, '(?m)^OK \((\d+) tests?\)\s*$')
    $metadata.testCount = if ($success.Success) { [int]$success.Groups[1].Value } else { 0 }
    if ($instrumentExit -ne 0 -or $metadata.testCount -ne $scenarioNames.Count -or
        $instrumentText -match '(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED:|INSTRUMENTATION_RESULT: shortMsg=)' -or
        $instrumentText -match '(?m)^INSTRUMENTATION_STATUS_CODE: -[12]\s*$' -or
        $instrumentText -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$') { throw 'Release measurement instrumentation failed or did not complete.' }

    foreach ($name in $scenarioNames) {
        $remote = "/sdcard/Android/data/$packageName/files/note-editor-release/$runId/$name.json"
        $local = Join-Path $reportRoot "$name.json"
        Invoke-ReleaseAdb -Arguments @('pull', $remote, $local) | Write-Output
        $report = Get-Content -LiteralPath $local -Raw | ConvertFrom-Json
        Assert-MeasurementReport -Report $report -Name $name
        $scenarioReports[$name] = $report
    }
    $metadata.measurementsCompleted = $true
    Write-Output "Release measurement completed ($($metadata.testCount) scenarios); performance acceptance remains separate: $reportRoot"
} catch {
    $metadata.failure = $_.Exception.Message
    throw
} finally {
    foreach ($kind in @('gfxinfo', 'meminfo')) {
        try {
            $argumentsAfter = if ($kind -eq 'gfxinfo') { @('shell', 'dumpsys', $kind, $packageName, 'framestats') }
                else { @('shell', 'dumpsys', $kind, $packageName) }
            Invoke-ReleaseAdb -Arguments $argumentsAfter | Set-Content -LiteralPath (Join-Path $reportRoot "$kind-after.txt") -Encoding utf8
        } catch { $metadata["${kind}CaptureError"] = 'capture failed; no measurement acceptance inferred' }
    }
    # Failed tests may have a partial synthetic report. Pull only the two known files
    # for this exact run, never a broad app directory or another run's evidence.
    foreach ($name in $scenarioNames) {
        $local = Join-Path $reportRoot "$name.json"
        if (-not (Test-Path -LiteralPath $local)) {
            $remote = "/sdcard/Android/data/$packageName/files/note-editor-release/$runId/$name.json"
            & $adb -s $DeviceSerial pull $remote $local 2>&1 | Out-Null
        }
    }
    $imeRestored = $false
    try {
        $currentIme = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
        if ($currentIme -ne $originalIme -and $originalIme -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$') {
            Invoke-ReleaseAdb -Arguments @('shell', 'ime', 'set', $originalIme) | Out-Null
        }
        $imeRestored = (Invoke-ReleaseAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim() -eq $originalIme
    } finally {
        $metadata.originalImeRestored = $imeRestored
        [ordered]@{ metadata = $metadata; scenarios = $scenarioReports } | ConvertTo-Json -Depth 30 |
            Set-Content -LiteralPath (Join-Path $reportRoot 'result.json') -Encoding utf8
    }
    if (-not $imeRestored) { throw 'Original input method could not be restored.' }
}

if ($CompareResult) {
    $previous = Get-Content -LiteralPath $CompareResult -Raw | ConvertFrom-Json
    foreach ($field in @('deviceSerial', 'model', 'sdk', 'abi', 'screenSize', 'screenDensity', 'fontScale',
        'peakRefreshRate', 'minimumRefreshRate', 'buildType', 'inputMode', 'rounds', 'samplesPerPosition', 'appApkSha256', 'testApkSha256')) {
        if ([string]$previous.metadata.$field -ne [string]$metadata[$field]) { throw "Comparison requires matching device/build/fixture conditions; differs: $field" }
    }
    if ($previous.metadata.measurementsCompleted -ne $true) { throw 'Previous result did not complete.' }
    $comparison = [ordered]@{ currentRun = $runId; previousRun = $previous.metadata.runId; scenarios = [ordered]@{} }
    foreach ($name in $scenarioNames) {
        $old = $previous.scenarios.$name
        $current = $scenarioReports[$name]
        if (-not $old -or $old.roundMeasurements[0].fixtureFingerprint -ne $current.roundMeasurements[0].fixtureFingerprint) {
            throw "Comparison fixture differs or is missing: $name"
        }
        $items = [ordered]@{}
        foreach ($position in @('head', 'middle', 'tail')) {
            foreach ($metric in @('inputToAcceptedMs', 'inputToObservedDrawMs')) {
                $items["$position.$metric"] = [ordered]@{
                    n = $current.summary.$position.$metric.n
                    previousMedianMs = $old.summary.$position.$metric.median
                    currentMedianMs = $current.summary.$position.$metric.median
                    medianDeltaMs = $current.summary.$position.$metric.median - $old.summary.$position.$metric.median
                    previousP95Ms = $old.summary.$position.$metric.p95
                    currentP95Ms = $current.summary.$position.$metric.p95
                    p95DeltaMs = $current.summary.$position.$metric.p95 - $old.summary.$position.$metric.p95
                }
            }
        }
        $comparison.scenarios[$name] = $items
    }
    $comparison | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $reportRoot 'comparison.json') -Encoding utf8
}
