param(
    [string]$Version = "1.13.4",
    [string]$SdkRoot = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidRoot = Join-Path $repoRoot "android"
$appRoot = Join-Path $androidRoot "app"
$depsRoot = Join-Path $androidRoot ".deps"
$sourceZip = Join-Path $depsRoot "sherpa-onnx-v$Version-source.zip"
$nativeArchive = Join-Path $depsRoot "sherpa-onnx-v$Version-android.tar.bz2"
$sourceRoot = Join-Path $depsRoot "sherpa-onnx-$Version"
$aarOutput = Join-Path $sourceRoot "android\SherpaOnnxAar\sherpa_onnx\build\outputs\aar\sherpa_onnx-release.aar"
$aarTarget = Join-Path $appRoot "libs\sherpa_onnx-release.aar"

New-Item -ItemType Directory -Force -Path $depsRoot, (Split-Path $aarTarget) | Out-Null

if (-not (Test-Path $sourceZip)) {
    Invoke-WebRequest `
        -Uri "https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v$Version.zip" `
        -OutFile $sourceZip
}
if (-not (Test-Path $nativeArchive)) {
    Invoke-WebRequest `
        -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$Version/sherpa-onnx-v$Version-android.tar.bz2" `
        -OutFile $nativeArchive
}
if (-not (Test-Path $sourceRoot)) {
    Expand-Archive -Path $sourceZip -DestinationPath $depsRoot
}

if ($SdkRoot -ne "") {
    "sdk.dir=$($SdkRoot -replace '\\', '/')" | Set-Content (Join-Path $sourceRoot "android\SherpaOnnxAar\local.properties")
}

$nativeRoot = Join-Path $depsRoot "native-$Version"
if (-not (Test-Path (Join-Path $nativeRoot "jniLibs"))) {
    New-Item -ItemType Directory -Force -Path $nativeRoot | Out-Null
    tar -xjf $nativeArchive -C $nativeRoot
}
$jniTarget = Join-Path $sourceRoot "android\SherpaOnnxAar\sherpa_onnx\src\main\jniLibs"
New-Item -ItemType Directory -Force -Path $jniTarget | Out-Null
Copy-Item -Recurse -Force (Join-Path $nativeRoot "jniLibs\*") $jniTarget
$expectedAbis = @("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
foreach ($abi in $expectedAbis) {
    $jniLibrary = Join-Path $jniTarget "$abi\libsherpa-onnx-jni.so"
    if (-not (Test-Path $jniLibrary)) {
        throw "Missing native library: $jniLibrary"
    }
}

# GitHub's ZIP expands Kotlin API symlinks to one-line link targets on Windows.
$kotlinApi = Join-Path $sourceRoot "sherpa-onnx\kotlin-api"
$aarApi = Join-Path $sourceRoot "android\SherpaOnnxAar\sherpa_onnx\src\main\java\com\k2fsa\sherpa\onnx"
Copy-Item -Force (Join-Path $kotlinApi "*.kt") $aarApi

$gradleWrapper = Join-Path $androidRoot "gradlew.bat"
& $gradleWrapper `
    -p (Join-Path $sourceRoot "android\SherpaOnnxAar") `
    ":sherpa_onnx:assembleRelease"
if ($LASTEXITCODE -ne 0) {
    throw "sherpa-onnx AAR build failed with exit code $LASTEXITCODE"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($aarOutput)
try {
    $entries = $archive.Entries.FullName
    foreach ($abi in $expectedAbis) {
        $expectedEntry = "jni/$abi/libsherpa-onnx-jni.so"
        if ($expectedEntry -notin $entries) {
            throw "Built AAR is incomplete: $expectedEntry is missing"
        }
    }
} finally {
    $archive.Dispose()
}
Copy-Item -Force $aarOutput $aarTarget
Write-Host "Prepared $aarTarget"
