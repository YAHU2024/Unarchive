<#
.SYNOPSIS
Repacks the upstream sherpa-onnx SenseVoice tar.bz2 archive into a zip.

.DESCRIPTION
The on-device model download fallback historically used the upstream tar.bz2
archive, whose pure-Java bzip2 decompression takes minutes on a phone. Zip
extraction uses the platform's native zlib (measured ~15x faster than
commons-compress bzip2 on the same 239 MB ONNX). This script repacks the
wanted files (model.int8.onnx, tokens.txt) into a zip that can be hosted as a
GitHub Release asset of this project; point ModelSource.archiveUrl at it.

Requires the Windows 10+ bundled bsdtar (tar.exe) for extraction and .NET for
zipping. Run from the repository root.

.EXAMPLE
.\android\scripts\repack_model_archive.ps1 `
    -ArchivePath .\android\models\sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 `
    -OutPath .\release-artifacts\models\sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ArchivePath,
    [Parameter(Mandatory = $true)]
    [string]$OutPath
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $ArchivePath)) { throw "Archive not found: $ArchivePath" }
$wanted = @("model.int8.onnx", "tokens.txt")

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("unarchive-repack-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$extractDir = Join-Path $work "extract"
New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
$outDir = Split-Path -Parent $OutPath
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

try {
    Write-Host "==> Extracting $ArchivePath (bsdtar)"
    & tar.exe -xf $ArchivePath -C $extractDir
    if ($LASTEXITCODE -ne 0) { throw "tar extraction failed with exit code $LASTEXITCODE" }

    $found = Get-ChildItem -Path $extractDir -Recurse -File | Where-Object { $_.Name -in $wanted }
    foreach ($name in $wanted) {
        $file = $found | Where-Object { $_.Name -eq $name } | Select-Object -First 1
        if ($null -eq $file) { throw "Wanted file missing from archive: $name" }
    }

    Write-Host "==> Creating $OutPath (deflate)"
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $OutPath) { Remove-Item $OutPath -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($OutPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in ($found | Sort-Object Name)) {
            $entry = $zip.CreateEntry($file.Name, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            try {
                $fileStream = [System.IO.File]::OpenRead($file.FullName)
                try { $fileStream.CopyTo($entryStream) }
                finally { $fileStream.Dispose() }
            }
            finally { $entryStream.Dispose() }
        }
    }
    finally { $zip.Dispose() }

    $zipSize = (Get-Item $OutPath).Length
    $zipHash = (Get-FileHash $OutPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host ""
    Write-Host "Repacked: $OutPath"
    Write-Host ("zip size:    {0:N1} MB" -f ($zipSize / 1MB))
    Write-Host "zip sha256:  $zipHash"
    Write-Host ""
    foreach ($file in ($found | Sort-Object Name)) {
        $hash = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host ("{0,-16} sha256: {1}" -f $file.Name, $hash)
    }
    Write-Host ""
    Write-Host "Next: upload $OutPath to a GitHub Release of this project, then set"
    Write-Host "ModelSource.archiveUrl in android/app/src/main/java/com/unarchive/android/model/ModelSource.kt"
    Write-Host "to the release asset URL (https://github.com/YAHU2024/Unarchive/releases/download/<tag>/<file>)."
    Write-Host "Keep the per-file sha256 values in sync with ModelSource.files[].expectedSha256."
}
finally {
    Remove-Item -Path $work -Recurse -Force -ErrorAction SilentlyContinue
}
