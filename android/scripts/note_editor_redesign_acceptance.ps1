<#
.SYNOPSIS
Builds and runs the production Markdown note redesign gate on one device.
.DESCRIPTION
Uses the app module and synthetic test-owned repositories. Probe APKs and probe
pass counts are deliberately excluded. Installs with -r, never clears app data,
and records automated device evidence separately from external-service and
manual visual, TalkBack, or continuous-writing acceptance.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = '',
    [ValidateSet('R1', 'R2', 'R3', 'R4')][string]$Stage = 'R1',
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string[]]$TestClass = @(
        'com.unarchive.android.editor.MarkdownNoteUiTest',
        'com.unarchive.android.editor.NotePanelFlowTest'
    )
)

$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $androidRoot '.android-sdk\platform-tools\adb.exe'
$packageName = 'com.unarchive.android'
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
if (-not (Test-Path -LiteralPath $adb)) { throw "Missing adb: $adb" }
if ($TestClass.Count -eq 0) { throw 'At least one production test class is required.' }

function Invoke-NoteAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & $adb -s $DeviceSerial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine)
}

$authorized = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($DeviceSerial) {
    if (-not ($authorized | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+device\s*$" })) {
        throw "Requested device is not connected and authorized: $DeviceSerial"
    }
} elseif ($authorized.Count -eq 1) {
    $DeviceSerial = ($authorized[0] -split '\s+')[0]
} else {
    throw "Connect one authorized device or pass -DeviceSerial; found $($authorized.Count)."
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runId = "$stamp-$($Stage.ToLowerInvariant())"
$reportRoot = Join-Path $androidRoot "build\reports\note-editor-redesign\$runId"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$originalIme = (Invoke-NoteAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
$metadata = [ordered]@{
    runId = $runId
    stage = $Stage
    buildType = 'debug'
    productionPackage = $packageName
    model = (Invoke-NoteAdb -Arguments @('shell', 'getprop', 'ro.product.model')).Trim()
    sdk = (Invoke-NoteAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    abi = (Invoke-NoteAdb -Arguments @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
    testClasses = $TestClass
    coverage = 'selected classes only; stage completeness is recorded in the M01-M53 progress ledger'
    skipBuild = [bool]$SkipBuild
    skipInstall = [bool]$SkipInstall
    automatedPassed = $false
    testCount = 0
    passedCount = 0
    failedCount = 0
    skippedCount = 0
    externalServiceAcceptance = 'not run by this gate'
    manualVisualTalkBackWritingAcceptance = 'not run by this gate'
    releasePerformanceAcceptance = 'not run by this Debug gate'
}

try {
    if (-not $SkipBuild) {
        Push-Location $androidRoot
        try {
            & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain 2>&1 |
                Tee-Object -FilePath (Join-Path $reportRoot 'build.txt') | Write-Output
            if ($LASTEXITCODE -ne 0) { throw "Gradle production gate failed ($LASTEXITCODE)." }
        } finally { Pop-Location }
    }

    $apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-$($metadata.abi)-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) {
        $apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-universal-debug.apk'
    }
    $testApk = Join-Path $androidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if (-not $SkipInstall) {
        foreach ($path in @($apk, $testApk)) {
            if (-not (Test-Path -LiteralPath $path)) { throw "Missing APK: $path" }
            $installed = Invoke-NoteAdb -Arguments @('install', '-r', $path)
            $installed | Write-Output
            if ($installed -notmatch '(?m)^Success\s*$') { throw "Installation did not report success: $path" }
        }
    }

    & $adb -s $DeviceSerial shell am instrument -w -r `
        -e class ($TestClass -join ',') -e noteEditorReportRun $runId -e noteEditorStage $Stage $runner 2>&1 |
        Tee-Object -FilePath (Join-Path $reportRoot 'instrumentation.txt') | Write-Output
    $instrumentExit = $LASTEXITCODE
    $instrumentText = Get-Content -LiteralPath (Join-Path $reportRoot 'instrumentation.txt') -Raw
    $success = [regex]::Match($instrumentText, '(?m)^OK \((\d+) tests?\)\s*$')
    $failedSummary = [regex]::Match($instrumentText, '(?m)^Tests run:\s*(\d+),\s*Failures:\s*(\d+)')
    $startedCount = [regex]::Matches($instrumentText, '(?m)^INSTRUMENTATION_STATUS_CODE: 1\s*$').Count
    $metadata.testCount = if ($success.Success) { [int]$success.Groups[1].Value }
        elseif ($failedSummary.Success) { [int]$failedSummary.Groups[1].Value } else { $startedCount }
    $metadata.passedCount = [regex]::Matches($instrumentText, '(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$').Count
    $metadata.failedCount = [regex]::Matches($instrumentText, '(?m)^INSTRUMENTATION_STATUS_CODE: -[12]\s*$').Count
    $metadata.skippedCount = [regex]::Matches($instrumentText, '(?m)^INSTRUMENTATION_STATUS_CODE: -[34]\s*$').Count
    $failed = $instrumentExit -ne 0 -or $metadata.testCount -eq 0 -or $metadata.skippedCount -gt 0 -or
        $instrumentText -match '(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED:|INSTRUMENTATION_RESULT: shortMsg=)' -or
        $instrumentText -match '(?m)^INSTRUMENTATION_STATUS_CODE: -[12]\s*$' -or
        $instrumentText -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$'
    if ($failed) { throw 'Production instrumentation failed or did not finish; inspect instrumentation.txt.' }
    $metadata.automatedPassed = $true
    Write-Output "Selected $Stage production-route tests passed ($($metadata.testCount) tests). Evidence: $reportRoot"
} finally {
    # Every artifact belongs to this run and contains synthetic fixtures only.
    # A failed run may have no directory; never borrow a previous run's output.
    $remoteEvidence = "/sdcard/Android/data/$packageName/files/note-editor-redesign/$runId"
    $evidenceExists = & $adb -s $DeviceSerial shell test -d $remoteEvidence 2>&1
    if ($LASTEXITCODE -eq 0) {
        try {
            Invoke-NoteAdb -Arguments @('pull', $remoteEvidence, (Join-Path $reportRoot 'device-evidence')) | Write-Output
        } catch { Write-Warning 'Could not pull this run''s synthetic device artifacts; instrumentation.txt remains authoritative.' }
    }
    $restored = $false
    try {
        $currentIme = (Invoke-NoteAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
        if ($currentIme -ne $originalIme -and $originalIme -notin @('', 'null')) {
            Invoke-NoteAdb -Arguments @('shell', 'ime', 'set', $originalIme) | Write-Output
        }
        $restored = (Invoke-NoteAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim() -eq $originalIme
    } finally {
        $metadata.originalImeRestored = $restored
        $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportRoot 'result.json') -Encoding utf8
    }
    if (-not $restored) { throw 'The original input method could not be restored.' }
}
