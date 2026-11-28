[CmdletBinding(DefaultParameterSetName = "Upgrade")]
param(
    [ValidateSet("nano", "comet")][string]$Target = "nano",
    [Parameter(Mandatory = $true, ParameterSetName = "Upgrade")]
    [string]$PreviousApk,
    [Parameter(Mandatory = $true, ParameterSetName = "FirstInstall")]
    [switch]$FirstInstall,
    [ValidateSet("Debug", "Development")]
    [string]$Variant = "Debug",
    [string]$KeystorePath,
    [Parameter(ParameterSetName = "Upgrade")]
    [string]$OutputPath,
    [string]$Gradle = ".\gradlew.bat"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($PSCmdlet.ParameterSetName -eq "FirstInstall" -and (-not $FirstInstall -or $Variant -ne "Development")) {
    throw "-FirstInstall is only permitted with -Variant Development and must be enabled."
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot 'private\release-target.ps1')
$product = Get-ReleaseTarget -Repository $repository -Target $Target

if ($Target -eq 'comet') {
    if ($FirstInstall -or $Variant -eq 'Development') { throw 'Comet device-test updates require the previous Comet APK.' }
    & (Join-Path $PSScriptRoot 'build-comet-device-test.ps1') -PreviousApk $PreviousApk -KeystorePath $KeystorePath -OutputPath $OutputPath -Gradle $Gradle
    return
}
if (-not $KeystorePath) {
    $KeystorePath = $env:NANOKVM_DEVELOPMENT_KEYSTORE
}
if (-not $KeystorePath -and $env:USERPROFILE) {
    $KeystorePath = Join-Path $env:USERPROFILE ".android\debug.keystore"
}
if (-not $KeystorePath -or -not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "A development keystore is required. Pass -KeystorePath or set NANOKVM_DEVELOPMENT_KEYSTORE."
}
if (-not $FirstInstall -and -not (Test-Path -LiteralPath $PreviousApk -PathType Leaf)) {
    throw "The preceding APK does not exist: $PreviousApk"
}

$resolvedKeystore = (Resolve-Path -LiteralPath $KeystorePath).Path
if (-not $FirstInstall) {
    $resolvedPrevious = (Resolve-Path -LiteralPath $PreviousApk).Path
}
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($resolvedKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The development keystore must remain outside the repository: $resolvedKeystore"
}
$sourceVersionName = $product.VersionName
$sourceVersionCode = $product.VersionCode
if ($FirstInstall -and ($sourceVersionCode -ne 20 -or $sourceVersionName -ne "0.3.13")) {
    throw "-FirstInstall is only permitted for the initial development version 0.3.13-dev, code 20. Later versions require -PreviousApk."
}
$buildVariant = if ($Variant -eq "Development") { "Development" } else { "Debug" }
$variantDirectory = $buildVariant.ToLowerInvariant()
$expectedPackage = if ($Variant -eq "Development") { "org.nanokvm.mobile.development" } else { "org.nanokvm.mobile" }
$builtApk = Join-Path $repository "app\build\outputs\apk\nano\$variantDirectory\app-nano-$variantDirectory.apk"
if (-not $OutputPath) {
    $outputName = if ($Variant -eq "Development") {
        "NanoKVM-Mobile-$sourceVersionName-dev-v$sourceVersionCode-development.apk"
    } else {
        "NanoKVM-Mobile-$sourceVersionName-v$sourceVersionCode-update-compatible-debug.apk"
    }
    $OutputPath = Join-Path $repository "dist\$outputName"
}
$absoluteOutput = [IO.Path]::GetFullPath($OutputPath)

Push-Location $repository
try {
    & $Gradle --no-daemon --no-parallel --no-configuration-cache `
        --no-problems-report --max-workers=1 `
        "-Pkotlin.compiler.execution.strategy=in-process" `
        --dependency-verification=strict `
        "-Pnanokvm.developmentKeystore=$resolvedKeystore" `
        ":app:assembleNano$buildVariant"
    if ($LASTEXITCODE -ne 0) {
        throw "Development update build failed with exit code $LASTEXITCODE."
    }

    $verificationParameters = @{
        CandidateApk = $builtApk
        ExpectedPackage = $expectedPackage
        ExpectedVersionCode = $sourceVersionCode
        ExpectedVersionName = if ($Variant -eq "Development") { "$sourceVersionName-dev" } else { $sourceVersionName }
    }
    if ($FirstInstall) {
        $verificationParameters.FirstInstall = $true
    } else {
        $verificationParameters.PreviousApk = $resolvedPrevious
    }
    & (Join-Path $PSScriptRoot "verify-apk-upgrade.ps1") @verificationParameters
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
        [IO.File]::Copy($builtApk, $absoluteOutput, $false)
    }

    $outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absoluteOutput).Hash
    if ($builtHash -ne $outputHash) {
        throw "The copied development APK does not match the verified build output."
    }
    $artifactDescription = if ($FirstInstall) { "first-install" } else { "update-compatible" }
    Write-Output "Prepared $artifactDescription development APK: $absoluteOutput"
    Write-Output "SHA-256: $($outputHash.ToLowerInvariant())"
} finally {
    Pop-Location
}
