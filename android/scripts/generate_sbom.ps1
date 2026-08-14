<#
.SYNOPSIS
Generates release-time dependency SBOM artifacts for the Android app.

.DESCRIPTION
Run from the repository root during the GPL-3.0 release packaging process.
Produces, under a timestamped release-artifacts directory:

  - android/bom.json / bom.xml: CycloneDX SBOM of all Android runtime and
    build dependencies including transitives (via the org.cyclonedx.bom
    Gradle plugin).
  - android/pip-freeze.txt: the currently installed Python environment
    (desktop pipeline) frozen by `pip freeze`, for reference.

The Android SBOM is generated against the local project state, so run it
from the exact revision you intend to release. The plugin requires network
access to resolve the CycloneDX toolchain on first use.

The release directory is created under .\release-artifacts\ which is
git-ignored; commit nothing from it.

.EXAMPLE
.\android\scripts\generate_sbom.ps1
#>
[CmdletBinding()]
param(
    # JDK root used by Gradle (defaults to JAVA_HOME).
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $repoRoot "release-artifacts\sbom-$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "==> Android CycloneDX SBOM (gradle :app:cyclonedxBom)"
Push-Location (Join-Path $repoRoot "android")
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
    }
    & .\gradlew.bat :app:cyclonedxBom --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "cyclonedxBom failed with exit code $LASTEXITCODE" }
    Copy-Item "app\build\reports\cyclonedx\bom.json" (Join-Path $outDir "android-bom.json") -Force
    Copy-Item "app\build\reports\cyclonedx\bom.xml" (Join-Path $outDir "android-bom.xml") -Force
}
finally {
    Pop-Location
}

Write-Host "==> Python environment freeze (reference only)"
$pipOut = Join-Path $outDir "pip-freeze.txt"
try {
    python -m pip freeze | Out-File -Encoding utf8 $pipOut
    Write-Host "  wrote $pipOut"
}
catch {
    Write-Host "  WARNING: pip freeze failed ($($_.Exception.Message)); desktop SBOM deferred."
}

Write-Host ""
Write-Host "SBOM artifacts written to: $outDir"
Write-Host "Next: review android-bom.json for unexpected licenses, then package"
Write-Host "      the APK, this SBOM, the in-APK notices, and corresponding source."
