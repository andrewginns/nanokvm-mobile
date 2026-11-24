[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PreviousApk,

    [Parameter(Mandatory = $true)]
    [string]$CandidateApk,

    [string]$ExpectedPackage = "org.cometkvm.mobile",

    [string]$ExpectedSignerSha256 =
        $env:COMET_DEVELOPMENT_SIGNER_SHA256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AndroidSdk {
    $candidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }

    $sdk = $candidates | Select-Object -First 1
    if (-not $sdk) {
        throw "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
    }
    return (Resolve-Path -LiteralPath $sdk).Path
}

function Get-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Apk,

        [Parameter(Mandatory = $true)]
        [string]$Aapt,

        [Parameter(Mandatory = $true)]
        [string]$ApkSigner
    )

    $resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
    $badging = (& $Aapt dump badging $resolvedApk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "aapt could not inspect $resolvedApk."
    }
    $packageMatch = [regex]::Match(
        $badging,
        "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'"
    )
    if (-not $packageMatch.Success) {
        throw "Could not read package/version metadata from $resolvedApk."
    }

    $signatureOutput = (& $ApkSigner verify --verbose --print-certs $resolvedApk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for $resolvedApk."
    }
    $signer = & (Join-Path $PSScriptRoot "read-apk-signer.ps1") -VerificationOutput $signatureOutput
    return [pscustomobject]@{
        Path = $resolvedApk
        Package = $packageMatch.Groups[1].Value
        VersionCode = [long]$packageMatch.Groups[2].Value
        VersionName = $packageMatch.Groups[3].Value
        SignerSha256 = $signer.sha256
    }
}

$androidSdk = Resolve-AndroidSdk
$buildToolsRoot = Join-Path $androidSdk "build-tools"
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Where-Object { $_.Name -match '^[0-9]+(\.[0-9]+){2}' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw "No Android SDK build-tools installation was found under $buildToolsRoot."
}

$aapt = Join-Path $buildTools.FullName "aapt.exe"
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
if (-not (Test-Path -LiteralPath $aapt) -or -not (Test-Path -LiteralPath $apkSigner)) {
    throw "The selected Android build-tools directory lacks aapt or apksigner."
}

$previous = Get-ApkMetadata -Apk $PreviousApk -Aapt $aapt -ApkSigner $apkSigner
$candidate = Get-ApkMetadata -Apk $CandidateApk -Aapt $aapt -ApkSigner $apkSigner
if ($ExpectedSignerSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $ExpectedSignerSha256 -in @(
    '7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746',
    '149d694db3d3b0d86849d1f99a570fb78c11627739494aa8c4e04eec6e276002',
    'b8c56ca6a229c85cd829da21cf697219e2d1a1d5f94d658719ebfa9e909075fd'
)) { throw "Configure the independent Comet development signer fingerprint before verifying updates." }
$expectedSigner = $ExpectedSignerSha256.Replace(":", "").ToLowerInvariant()

if ($previous.Package -ne $ExpectedPackage -or $candidate.Package -ne $ExpectedPackage) {
    throw "Package mismatch: expected $ExpectedPackage, previous=$($previous.Package), candidate=$($candidate.Package)."
}
if ($previous.SignerSha256 -ne $expectedSigner) {
    throw "The preceding APK is not on the expected development signing lineage."
}
if ($candidate.SignerSha256 -ne $previous.SignerSha256) {
    throw "Signing mismatch: the candidate cannot update the preceding APK."
}
if ($candidate.VersionCode -le $previous.VersionCode) {
    throw "Version code must increase: previous=$($previous.VersionCode), candidate=$($candidate.VersionCode)."
}

$candidateHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate.Path).Hash.ToLowerInvariant()
Write-Output (
    "Upgrade-compatible APK verified: {0} {1} ({2}) -> {3} ({4}); signer {5}; SHA-256 {6}" -f
    $ExpectedPackage,
    $previous.VersionName,
    $previous.VersionCode,
    $candidate.VersionName,
    $candidate.VersionCode,
    $candidate.SignerSha256,
    $candidateHash
)
