param(
    [Parameter(Mandatory = $true)]
    [string]$ModelDirectory,
    [string]$AudioFile = ""
)

$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $androidRoot ".android-sdk\platform-tools\adb.exe"
$apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"
$packageName = "com.unarchive.android"
$deviceStagingDirectory = "/data/local/tmp/unarchive-sensevoice"
$deviceModelDirectory = "files/models/sensevoice-2024-07-17-int8"

foreach ($path in @($adb, $apk, (Join-Path $ModelDirectory "model.int8.onnx"), (Join-Path $ModelDirectory "tokens.txt"))) {
    if (-not (Test-Path $path)) { throw "Required file not found: $path" }
}

$devices = & $adb devices | Select-String "\tdevice$"
if ($devices.Count -ne 1) {
    throw "Connect exactly one authorized Android device; found $($devices.Count)."
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb shell rm -rf $deviceStagingDirectory
& $adb shell mkdir -p $deviceStagingDirectory
if ($LASTEXITCODE -ne 0) { throw "Could not create the temporary model directory." }
& $adb push (Join-Path $ModelDirectory "model.int8.onnx") "$deviceStagingDirectory/model.int8.onnx"
if ($LASTEXITCODE -ne 0) { throw "Model deployment failed." }
& $adb push (Join-Path $ModelDirectory "tokens.txt") "$deviceStagingDirectory/tokens.txt"
if ($LASTEXITCODE -ne 0) { throw "Token deployment failed." }
& $adb shell run-as $packageName mkdir -p $deviceModelDirectory
if ($LASTEXITCODE -ne 0) { throw "Could not create the private app model directory." }
& $adb shell run-as $packageName cp "$deviceStagingDirectory/model.int8.onnx" "$deviceModelDirectory/model.int8.onnx"
if ($LASTEXITCODE -ne 0) { throw "Could not import the model into private app storage." }
& $adb shell run-as $packageName cp "$deviceStagingDirectory/tokens.txt" "$deviceModelDirectory/tokens.txt"
if ($LASTEXITCODE -ne 0) { throw "Could not import tokens into private app storage." }
& $adb shell rm -rf $deviceStagingDirectory

if ($AudioFile -ne "") {
    if (-not (Test-Path $AudioFile)) { throw "Audio file not found: $AudioFile" }
    & $adb push $AudioFile "/sdcard/Download/unarchive-asr-sample.wav"
    if ($LASTEXITCODE -ne 0) { throw "Audio deployment failed." }
}

& $adb shell am start -n "$packageName/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
Write-Host "App installed and model deployed. Select a 16 kHz mono PCM WAV, run SenseVoice, then record RTF, memory, temperature, battery delta, accuracy, and cancellation behavior."
