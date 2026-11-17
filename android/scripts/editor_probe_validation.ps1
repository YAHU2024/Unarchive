<#
.SYNOPSIS
Builds and validates the isolated editor adapter probe on one authorized device.
.DESCRIPTION
Requires a passing bounded JVM source-adapter report before any installation.
Uses a separate package and synthetic text. Saves and restores the selected IME
outside instrumentation as well, including when a test process fails.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [ValidateSet('debug', 'benchmark')][string]$BuildType = 'debug',
    [ValidateSet('Ime', 'Long', 'E2Ime', 'E2Long', 'E2Selection', 'NativeLayout', 'NativeSelection', 'NativeIme', 'RealHandles', 'VendorLong')][string]$Scenario = 'Ime',
    [ValidateSet('adapted', 'plain', 'candidate', 'native')][string]$EditorMode = 'adapted',
    [switch]$SkipBuild,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path $PSScriptRoot -Parent
$probeRoot = Join-Path $androidRoot 'spikes\editor-android-probe'
$sourceReport = Join-Path $androidRoot 'spikes\editor-kernel-probe\build\reports\source-adapter\adapter-report.json'
$gate = Get-Content -LiteralPath $sourceReport -Raw | ConvertFrom-Json
$reviewReport = Join-Path (Split-Path $sourceReport) 'review-checks.json'
$review = Get-Content -LiteralPath $reviewReport -Raw | ConvertFrom-Json
if (-not $gate.allPassed -or $gate.total -lt 31 -or -not $review.allPassed) { throw 'The bounded source-adapter and independent gates have not passed.' }
if ($Scenario -match '^E2|^Native|^Real|^Vendor' -or $EditorMode -eq 'native') {
    foreach ($file in @('incremental-report.json', 'history-report.json')) {
        $e2Gate = Get-Content -LiteralPath (Join-Path $androidRoot "spikes\editor-kernel-probe\build\reports\e2\$file") -Raw | ConvertFrom-Json
        if (-not $e2Gate.allPassed) { throw "E2 model gate failed: $file" }
    }
}
$sdkRoot = Join-Path $androidRoot '.android-sdk'
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "Missing adb: $adb" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$phase = if ($Scenario -match '^Real|^Vendor') { 'editor-e2b' } elseif ($Scenario -match '^E2|^Native' -or $EditorMode -eq 'native') { 'editor-e2' } else { 'editor-e1' }
$reportRoot = Join-Path $androidRoot "build\reports\$phase\$stamp-$BuildType-$Scenario-$EditorMode"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null

function Invoke-ProbeAdb {
    param([string[]]$AdbArguments)
    $output = & $adb -s $DeviceSerial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

if (-not $SkipBuild) {
    $suffix = if ($BuildType -eq 'benchmark') { 'Benchmark' } else { 'Debug' }
    $buildArguments = @('-p', $probeRoot, "-PprobeTestBuildType=$BuildType", "assemble$suffix", "assemble${suffix}AndroidTest", '--console=plain')
    $previousAndroidHome = $env:ANDROID_HOME
    try {
        $env:ANDROID_HOME = $sdkRoot
        & (Join-Path $androidRoot 'gradlew.bat') @buildArguments 2>&1 | Tee-Object -FilePath (Join-Path $reportRoot 'build.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Probe build failed.' }
    } finally { $env:ANDROID_HOME = $previousAndroidHome }
}

if ((Invoke-ProbeAdb @('get-state')).Trim() -ne 'device') { throw 'Device is not authorized.' }
$originalIme = (Invoke-ProbeAdb @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
$originalEnabled = (Invoke-ProbeAdb @('shell', 'ime', 'list', '-s')).Split("`n") | ForEach-Object { $_.Trim() }
$probeIme = 'com.unarchive.editorprobe/.ProbeImeService'
$appApk = Join-Path $probeRoot "build\outputs\apk\$BuildType\editor-android-probe-$BuildType.apk"
$testApk = Join-Path $probeRoot "build\outputs\apk\androidTest\$BuildType\editor-android-probe-$BuildType-androidTest.apk"
$cases = @{
    Ime = @('EditorProbeInstrumentedTest', 'actualInputConnectionComposesAndCommitsChinese', 'input-connection-composition.json')
    Long = @('EditorProbeInstrumentedTest', 'longDocumentHasCompleteTailAndRealFrameMetrics', "long-document-frames-$EditorMode.json")
    E2Ime = @('E2IntegratedInstrumentedTest', 'nativeIntegratedServedImeCommitsWithoutSourceComposition', 'e2-integrated-ime.json')
    E2Long = @('E2IntegratedInstrumentedTest', 'incrementalNativeLongHeadMiddleTailAndFrames', 'e2-integrated-long.json')
    E2Selection = @('E2IntegratedInstrumentedTest', 'continuousSelectionCutPasteAndBoundedHistoryStaySourceExact', 'e2-integrated-selection-history.json')
    NativeLayout = @('E2LayoutInstrumentedTest', 'nativeLongLayoutHeadMiddleTailInputAndRealFrames', 'e2-native-layout.json')
    NativeSelection = @('E2LayoutInstrumentedTest', 'nativeCrossViewportSelectionCopyCutPasteUsesOneBuffer', 'e2-native-selection.json')
    NativeIme = @('E2LayoutInstrumentedTest', 'nativeServedInputConnectionPreservesCompositionAndCaret', 'e2-native-ime.json')
    RealHandles = @('E2RealInteractionInstrumentedTest', 'longPressDragHandleAcrossParagraphsAndViewportKeepsSourceExact', 'e2-real-handles.json')
    VendorLong = @('E2RealInteractionInstrumentedTest', 'baiduTouchPinyinCandidatesAtHeadMiddleTailKeepLongSourceExact', 'e2-vendor-long.json')
}
$case = $cases[$Scenario]
$testClass = "com.unarchive.editorprobe.$($case[0])#$($case[1])"
$reportName = $case[2]
$screenshots = if ($Scenario -eq 'RealHandles') { @('handles-01-longpress.png', 'handles-02-cross-paragraph.png', 'handles-03-auto-scroll.png') } elseif ($Scenario -eq 'VendorLong') { @('vendor-head-preedit.png', 'vendor-head-committed.png', 'vendor-middle-preedit.png', 'vendor-middle-committed.png', 'vendor-tail-preedit.png', 'vendor-tail-committed.png', 'vendor-tail-after-enter-preedit.png', 'vendor-final.png') } else { @() }
try {
    foreach ($apk in $(if ($SkipInstall) { @() } else { @($appApk, $testApk) })) {
        if (-not (Test-Path -LiteralPath $apk)) { throw "Missing APK: $apk" }
        $result = Invoke-ProbeAdb @('install', '-r', $apk)
        Write-Output $result
        if ($result -notmatch '(?m)^Success\s*$') { throw 'Probe installation did not report success.' }
    }
    # Clear only this scenario's reusable synthetic outputs, after previous runs
    # were pulled locally. A failed run must not borrow stale success evidence.
    foreach ($artifact in (@($reportName) + $screenshots)) {
        [void](Invoke-ProbeAdb @('shell', 'rm', '-f', "/sdcard/Android/data/com.unarchive.editorprobe/files/probe-reports/$artifact"))
    }
    $result = Invoke-ProbeAdb @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass, '-e', 'editorMode', $EditorMode, 'com.unarchive.editorprobe.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Tee-Object -FilePath (Join-Path $reportRoot 'instrumentation.txt') | Write-Output
    $remote = "/sdcard/Android/data/com.unarchive.editorprobe/files/probe-reports/$reportName"
    try { Write-Output (Invoke-ProbeAdb @('pull', $remote, (Join-Path $reportRoot $reportName))) }
    catch { Write-Warning 'The failed test did not leave a readable evidence report; inspect instrumentation.txt.' }
    if ($Scenario -in @('RealHandles', 'VendorLong')) {
        foreach ($screenshot in $screenshots) {
            try { Write-Output (Invoke-ProbeAdb @('pull', "/sdcard/Android/data/com.unarchive.editorprobe/files/probe-reports/$screenshot", (Join-Path $reportRoot $screenshot))) }
            catch { Write-Warning "Screenshot not available: $screenshot" }
        }
    }
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw 'Probe instrumentation did not pass.' }
    $evidence = Get-Content -LiteralPath (Join-Path $reportRoot $reportName) -Raw | ConvertFrom-Json
    if (-not $evidence.passed) { throw 'Probe evidence does not report a pass.' }
} finally {
    if ($originalIme -and $originalIme -ne 'null') {
        Write-Output (Invoke-ProbeAdb @('shell', 'ime', 'set', $originalIme))
    }
    if ($probeIme -notin $originalEnabled) {
        Write-Output (Invoke-ProbeAdb @('shell', 'ime', 'disable', $probeIme))
    }
    $restored = (Invoke-ProbeAdb @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim() -eq $originalIme
    [pscustomobject]@{ originalImeRestored = $restored; buildType = $BuildType; scenario = $Scenario; editorMode = $EditorMode } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reportRoot 'cleanup.json') -Encoding utf8
    Write-Output "Probe evidence: $reportRoot; original IME restored: $restored"
    if (-not $restored) { throw 'The original input method was not restored.' }
}
