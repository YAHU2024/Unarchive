<#
.SYNOPSIS
Builds the M4A/AAC counterpart of the long WAV benchmark sample.

.DESCRIPTION
The on-device benchmark needs both a WAV (direct decoder path) and an M4A
(MediaCodec/AAC path) version of the same audio to compare decode-path costs,
as done for the Bilibili 3-minute investigation (see
docs/internal/android/ANDROID_PERF_SIZE_ROUND_PLAN.md section 5.2).

.EXAMPLE
.\make_bench_m4a.ps1 -Wav .\android\benchmark-results\device-audio\bench-long.wav
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Wav
)

$ErrorActionPreference = "Stop"
$m4a = [System.IO.Path]::ChangeExtension($Wav, ".m4a")
& ffmpeg -y -i $Wav -c:a aac -b:a 128k $m4a 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed with exit code $LASTEXITCODE" }
Write-Host "wrote $m4a"
