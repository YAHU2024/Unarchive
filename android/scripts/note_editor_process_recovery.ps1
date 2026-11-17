<#
.SYNOPSIS
Verifies persisted production note recovery across an actual target-process force-stop.
.DESCRIPTION
Two separate instrumentation invocations share only a uniquely named synthetic
app-private fixture. No app-data clear, uninstall, private media or remote APIs.
The seed is intentionally retained until the verify phase succeeds.
#>
[CmdletBinding()]
param(
    [string]$DeviceSerial = '',
    [switch]$SkipBuild,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $androidRoot '.android-sdk\platform-tools\adb.exe'
$packageName = 'com.unarchive.android'
$runner = "$packageName.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'com.unarchive.android.editor.NoteProcessRecoveryTest'
if (-not (Test-Path -LiteralPath $adb)) { throw "Missing adb: $adb" }

function Invoke-ProcessAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & $adb -s $DeviceSerial @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "adb failed ($code): $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)" }
    return ($output -join [Environment]::NewLine)
}

$devices = @(& $adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($DeviceSerial) {
    if (-not ($devices | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+device\s*$" })) {
        throw "Device is not connected and authorized: $DeviceSerial"
    }
} elseif ($devices.Count -eq 1) {
    $DeviceSerial = ($devices[0] -split '\s+')[0]
} else { throw "Connect one authorized device or pass -DeviceSerial; found $($devices.Count)." }

$runId = 'process-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$reportRoot = Join-Path $androidRoot "build\reports\note-editor-process-recovery\$runId"
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$remoteReport = "/sdcard/Android/data/$packageName/files/note-editor-process-recovery/$runId"
$originalIme = (Invoke-ProcessAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
$metadata = [ordered]@{
    runId = $runId
    deviceSerial = $DeviceSerial
    packageName = $packageName
    testClass = $testClass
    buildType = 'debug'
    startedAt = (Get-Date).ToString('o')
    model = (Invoke-ProcessAdb -Arguments @('shell', 'getprop', 'ro.product.model')).Trim()
    sdk = (Invoke-ProcessAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
    abi = (Invoke-ProcessAdb -Arguments @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
    skipBuild = [bool]$SkipBuild
    skipInstall = [bool]$SkipInstall
    seedPassed = $false
    verifyPassed = $false
    forceStopExecuted = $false
    targetAbsentAfterForceStop = $false
    processIdsDiffer = $false
    automatedPassed = $false
    phases = @()
    actualSystemPickerUi = 'not exercised; synthetic controlled input only'
    realExternalServices = 'not exercised'
    manualVisualTalkBackWriting = 'not exercised'
}

function Invoke-ProcessPhase {
    param([string]$Name, [string]$Method)
    $startedAt = Get-Date
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $output = & $adb -s $DeviceSerial shell am instrument -w -r -e class "$testClass#$Method" -e noteProcessRun $runId $runner 2>&1
    $code = $LASTEXITCODE
    $timer.Stop()
    $text = $output -join [Environment]::NewLine
    $output | Set-Content -LiteralPath (Join-Path $reportRoot "$Name-instrumentation.txt") -Encoding utf8
    $success = [regex]::Match($text, '(?m)^OK \((\d+) tests?\)\s*$')
    $passed = $code -eq 0 -and $success.Success -and [int]$success.Groups[1].Value -eq 1 -and
        $text -match '(?m)^INSTRUMENTATION_CODE: -1\s*$' -and
        $text -notmatch '(?m)^(FAILURES!!!|INSTRUMENTATION_FAILED:|INSTRUMENTATION_RESULT: shortMsg=)' -and
        $text -notmatch '(?m)^INSTRUMENTATION_STATUS_CODE: -[12]\s*$'
    $metadata.phases += [ordered]@{
        name = $Name; method = $Method; startedAt = $startedAt.ToString('o'); elapsedMs = $timer.ElapsedMilliseconds
        instrumentationExitCode = $code; testCount = if ($success.Success) { [int]$success.Groups[1].Value } else { 0 }; passed = $passed
    }
    if (-not $passed) { throw "Process recovery $Name instrumentation failed or did not complete; inspect $Name-instrumentation.txt." }
    $phaseFile = Join-Path $reportRoot "$Name.json"
    $null = Invoke-ProcessAdb -Arguments @('pull', "$remoteReport/$Name.json", $phaseFile)
    $phase = Get-Content -Raw -LiteralPath $phaseFile | ConvertFrom-Json
    if ($phase.runId -ne $runId -or $phase.passed -ne $true) { throw "The $Name report does not confirm this exact run." }
    return $phase
}

try {
    if (-not $SkipBuild) {
        Push-Location $androidRoot
        try {
            & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain 2>&1 |
                Tee-Object -FilePath (Join-Path $reportRoot 'build.txt') | Write-Output
            if ($LASTEXITCODE -ne 0) { throw "Gradle process-recovery build failed ($LASTEXITCODE)." }
        } finally { Pop-Location }
    }
    if (-not $SkipInstall) {
        $apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-$($metadata.abi)-debug.apk"
        if (-not (Test-Path -LiteralPath $apk)) { $apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-universal-debug.apk' }
        $testApk = Join-Path $androidRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
        foreach ($path in @($apk, $testApk)) {
            if (-not (Test-Path -LiteralPath $path)) { throw "Missing APK: $path" }
            $installed = Invoke-ProcessAdb -Arguments @('install', '-r', $path)
            $installed | Write-Output
            if ($installed -notmatch '(?m)^Success\s*$') { throw "Installation did not report success: $path" }
        }
    }
    $seed = Invoke-ProcessPhase -Name 'seed' -Method 'seedNoteDraftAndSourceScratch'
    $metadata.seedPassed = $true
    $metadata.seedPid = [int]$seed.pid
    $metadata.forceStopStartedAt = (Get-Date).ToString('o')
    Invoke-ProcessAdb -Arguments @('shell', 'am', 'force-stop', $packageName) | Write-Output
    $metadata.forceStopExecuted = $true
    $pidOutput = & $adb -s $DeviceSerial shell pidof $packageName 2>$null
    $pidExit = $LASTEXITCODE
    if ($pidExit -notin @(0, 1)) { throw "Could not verify target-process absence ($pidExit)." }
    if (($pidOutput -join '').Trim()) { throw 'Target process remains present after force-stop; recovery evidence is invalid.' }
    $metadata.targetAbsentAfterForceStop = $true
    $verify = Invoke-ProcessPhase -Name 'verify' -Method 'verifyDraftAfterActualTargetProcessRestart'
    $metadata.verifyPassed = $true
    $metadata.verifyPid = [int]$verify.verifyPid
    $metadata.processIdsDiffer = [int]$verify.seedPid -eq [int]$seed.pid -and [int]$verify.verifyPid -ne [int]$seed.pid
    if (-not $metadata.processIdsDiffer -or -not $verify.syntheticFixtureRemoved) { throw 'Verify did not confirm a different process and checked synthetic-fixture cleanup.' }
    $metadata.automatedPassed = $true
    Write-Output "Production persisted-draft recovery passed across target-process force-stop. Evidence: $reportRoot"
} finally {
    $restored = $false
    try {
        $currentIme = (Invoke-ProcessAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim()
        if ($currentIme -ne $originalIme -and $originalIme -notin @('', 'null')) {
            Invoke-ProcessAdb -Arguments @('shell', 'ime', 'set', $originalIme) | Write-Output
        }
        $restored = (Invoke-ProcessAdb -Arguments @('shell', 'settings', 'get', 'secure', 'default_input_method')).Trim() -eq $originalIme
    } finally {
        $metadata.originalImeRestored = $restored
        $metadata.finishedAt = (Get-Date).ToString('o')
        $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $reportRoot 'result.json') -Encoding utf8
    }
    if (-not $restored) { throw 'The original input method could not be restored.' }
}
