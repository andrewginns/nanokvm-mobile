[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PreviousApk,
    [string]$KeystorePath,
    [string]$OutputPath,
    [string]$Gradle = ".\gradlew.bat"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $KeystorePath) {
    $KeystorePath = $env:NANOKVM_DEVELOPMENT_KEYSTORE
}
if (-not $KeystorePath -and $env:USERPROFILE) {
    $KeystorePath = Join-Path $env:USERPROFILE ".android\debug.keystore"
}
if (-not $KeystorePath -or -not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "A development keystore is required. Pass -KeystorePath or set NANOKVM_DEVELOPMENT_KEYSTORE."
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
$builtApk = Join-Path $repository "app\build\outputs\apk\debug\app-debug.apk"
if (-not $OutputPath) {
    $OutputPath = Join-Path $repository "dist\NanoKVM-Mobile-0.3.5-update-compatible-debug.apk"
}
$absoluteOutput = [IO.Path]::GetFullPath($OutputPath)

Push-Location $repository
try {
    & $Gradle --no-daemon --no-parallel --no-configuration-cache `
        --no-problems-report --max-workers=1 `
        "-Pkotlin.compiler.execution.strategy=in-process" `
        --dependency-verification=strict `
        "-Pnanokvm.developmentKeystore=$resolvedKeystore" `
        :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Development update build failed with exit code $LASTEXITCODE."
    }

    & (Join-Path $PSScriptRoot "verify-apk-upgrade.ps1") `
        -PreviousApk $resolvedPrevious `
        -CandidateApk $builtApk
    if ($LASTEXITCODE -ne 0) {
        throw "Development update verification failed with exit code $LASTEXITCODE."
    }

    $outputDirectory = Split-Path -Parent $absoluteOutput
    if (-not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory | Out-Null
    }
    $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtApk).Hash
    if (Test-Path -LiteralPath $absoluteOutput) {
        if (-not (Test-Path -LiteralPath $absoluteOutput -PathType Leaf)) {
            throw "The development APK output path exists but is not a file: $absoluteOutput"
        }
        $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absoluteOutput).Hash
        if ($existingHash -ne $builtHash) {
            throw (
                "Refusing to overwrite an existing development APK with different bytes. " +
                "Advance versionName/versionCode and choose a new output path: $absoluteOutput"
            )
        }
        Write-Output "Existing development APK already matches the verified build output."
    } else {
        Copy-Item -LiteralPath $builtApk -Destination $absoluteOutput
    }

    $outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absoluteOutput).Hash
    if ($builtHash -ne $outputHash) {
        throw "The copied development APK does not match the verified build output."
    }
    Write-Output "Prepared update-compatible development APK: $absoluteOutput"
    Write-Output "SHA-256: $($outputHash.ToLowerInvariant())"
} finally {
    Pop-Location
}
