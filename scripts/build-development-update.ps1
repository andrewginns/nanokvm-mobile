[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PreviousApk,
    [string]$KeystorePath,
    [string]$OutputPath,
    [switch]$Compact,
    [string]$Gradle = ".\gradlew.bat"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $KeystorePath) {
    $KeystorePath = $env:COMET_DEVELOPMENT_KEYSTORE
}
if (-not $KeystorePath -or -not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "A development keystore is required. Pass -KeystorePath or set COMET_DEVELOPMENT_KEYSTORE."
}
if (-not (Test-Path -LiteralPath $PreviousApk -PathType Leaf)) {
    throw "The preceding APK does not exist: $PreviousApk"
}

$resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
$resolvedPrevious = (Resolve-Path -LiteralPath $PreviousApk).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($resolvedKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The development keystore must remain outside the repository: $resolvedKeystore"
}
$rootBuild = Get-Content -Raw (Join-Path $repository "build.gradle.kts")
$appBuild = Get-Content -Raw (Join-Path $repository "app\build.gradle.kts")
$versionNameMatch = [regex]::Match($rootBuild, '(?m)^\s*version\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($appBuild, '(?m)^\s*versionCode\s*=\s*([0-9]+)')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    throw "Could not determine the source version name/code from the Gradle build files."
}
$sourceVersionName = $versionNameMatch.Groups[1].Value
$sourceVersionCode = [long]$versionCodeMatch.Groups[1].Value
$variant = if ($Compact) { "benchmark" } else { "debug" }
$assembleTask = if ($Compact) { ":app:assembleBenchmark" } else { ":app:assembleDebug" }
$artifactLabel = if ($Compact) { "compact" } else { "debug" }
$builtApk = Join-Path $repository "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not $OutputPath) {
    $OutputPath = Join-Path $repository (
        "dist\Comet-Mobile-$sourceVersionName-v$sourceVersionCode-update-compatible-$artifactLabel.apk"
    )
}
$absoluteOutput = [IO.Path]::GetFullPath($OutputPath)

Push-Location $repository
try {
    & $Gradle --no-daemon --no-parallel --no-configuration-cache `
        --no-problems-report --max-workers=1 `
        "-Pkotlin.compiler.execution.strategy=in-process" `
        --dependency-verification=strict `
        "-Pcomet.developmentKeystore=$resolvedKeystore" `
        "-Pcomet.compactDevelopmentApk=$($Compact.IsPresent.ToString().ToLowerInvariant())" `
        $assembleTask
    if ($LASTEXITCODE -ne 0) {
        throw "Development update build failed with exit code $LASTEXITCODE."
    }

    $outputDirectory = Split-Path -Parent $absoluteOutput
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    $stagedApk = Join-Path $outputDirectory (".comet-update-" + [guid]::NewGuid().ToString("N") + ".apk")
    try {
        [IO.File]::Copy($builtApk, $stagedApk, $false)
        & (Join-Path $PSScriptRoot "verify-apk-upgrade.ps1") `
            -PreviousApk $resolvedPrevious `
            -CandidateApk $stagedApk
        if ($LASTEXITCODE -ne 0) {
            throw "Development update verification failed with exit code $LASTEXITCODE."
        }
        if ($Compact -and (Get-Item -LiteralPath $stagedApk).Length -ge 20MB) {
            throw "The compact development APK must be smaller than 20 MiB."
        }

        $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedApk).Hash
        try {
            # Atomic same-directory publish. The two-argument Move never replaces another file.
            [IO.File]::Move($stagedApk, $absoluteOutput)
        } catch [IO.IOException] {
            if (-not [IO.File]::Exists($absoluteOutput)) { throw }
        }
        $outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absoluteOutput).Hash
        if ($builtHash -ne $outputHash) {
            throw "The output already contains different bytes. Increase versionCode and choose a new output path."
        }
    } finally {
        if ([IO.File]::Exists($stagedApk)) { [IO.File]::Delete($stagedApk) }
    }
    Write-Output "Prepared update-compatible development APK: $absoluteOutput"
    Write-Output "SHA-256: $($outputHash.ToLowerInvariant())"
} finally {
    Pop-Location
}
