param(
    [Parameter(Mandatory = $true)]
    [string]$ModelDirectory,
    [string]$VadModel = "",
    [string]$AudioFile = ""
)

$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $androidRoot ".android-sdk\platform-tools\adb.exe"
$packageName = "com.unarchive.android"
$deviceStagingDirectory = "/data/local/tmp/unarchive-sensevoice"
$deviceModelDirectory = "files/models/sensevoice-2024-07-17-int8"
$deviceVadDirectory = "files/models/silero-vad"
if ($VadModel -eq "") {
    $VadModel = Join-Path $androidRoot "models\silero-vad\silero_vad.onnx"
}

foreach ($path in @($adb, $apk, (Join-Path $ModelDirectory "model.int8.onnx"), (Join-Path $ModelDirectory "tokens.txt"), $VadModel)) {
    if (-not (Test-Path $path)) { throw "Required file not found: $path" }
}

$devices = & $adb devices | Select-String "\tdevice$"
if ($devices.Count -ne 1) {
    throw "Connect exactly one authorized Android device; found $($devices.Count)."
}

# ABI-split builds produce one debug APK per ABI; install the one matching the
# connected device, falling back to the universal APK.
$abi = (& $adb shell getprop ro.product.cpu.abi).Trim()
$apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-$abi-debug.apk"
if (-not (Test-Path $apk)) {
    $apk = Join-Path $androidRoot "app\build\outputs\apk\debug\app-universal-debug.apk"
}
Write-Host "Installing APK for ABI $abi : $apk"

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
& $adb shell rm -rf $deviceStagingDirectory
& $adb shell mkdir -p $deviceStagingDirectory
if ($LASTEXITCODE -ne 0) { throw "Could not create the temporary model directory." }
& $adb push (Join-Path $ModelDirectory "model.int8.onnx") "$deviceStagingDirectory/model.int8.onnx"
if ($LASTEXITCODE -ne 0) { throw "Model deployment failed." }
& $adb push (Join-Path $ModelDirectory "tokens.txt") "$deviceStagingDirectory/tokens.txt"
if ($LASTEXITCODE -ne 0) { throw "Token deployment failed." }
& $adb push $VadModel "$deviceStagingDirectory/silero_vad.onnx"
if ($LASTEXITCODE -ne 0) { throw "VAD model deployment failed." }
& $adb shell run-as $packageName mkdir -p $deviceModelDirectory
if ($LASTEXITCODE -ne 0) { throw "Could not create the private app model directory." }
& $adb shell run-as $packageName cp "$deviceStagingDirectory/model.int8.onnx" "$deviceModelDirectory/model.int8.onnx"
if ($LASTEXITCODE -ne 0) { throw "Could not import the model into private app storage." }
& $adb shell run-as $packageName cp "$deviceStagingDirectory/tokens.txt" "$deviceModelDirectory/tokens.txt"
if ($LASTEXITCODE -ne 0) { throw "Could not import tokens into private app storage." }
& $adb shell run-as $packageName mkdir -p $deviceVadDirectory
if ($LASTEXITCODE -ne 0) { throw "Could not create the private VAD model directory." }
& $adb shell run-as $packageName cp "$deviceStagingDirectory/silero_vad.onnx" "$deviceVadDirectory/silero_vad.onnx"
if ($LASTEXITCODE -ne 0) { throw "Could not import the VAD model into private app storage." }
& $adb shell rm -rf $deviceStagingDirectory

if ($AudioFile -ne "") {
    if (-not (Test-Path $AudioFile)) { throw "Audio file not found: $AudioFile" }
    $audioExtension = [System.IO.Path]::GetExtension($AudioFile)
    if ([string]::IsNullOrWhiteSpace($audioExtension)) { $audioExtension = ".audio" }
    & $adb push $AudioFile "/sdcard/Download/unarchive-asr-sample$audioExtension"
    if ($LASTEXITCODE -ne 0) { throw "Audio deployment failed." }
}

& $adb shell am start -n "$packageName/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
Write-Host "App installed and model deployed. Select a supported audio file, run SenseVoice, then record RTF, memory, temperature, battery delta, accuracy, and cancellation behavior."
