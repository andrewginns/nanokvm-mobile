[CmdletBinding(DefaultParameterSetName = "Upgrade")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Upgrade")]
    [string]$PreviousApk,

    [Parameter(Mandatory = $true, ParameterSetName = "FirstInstall")]
    [switch]$FirstInstall,

    [Parameter(Mandatory = $true)]
    [string]$CandidateApk,

    [string]$ExpectedVersionName,
    [long]$ExpectedVersionCode,
    [string]$ExpectedPackage = "org.nanokvm.mobile",

    [string]$ExpectedSignerSha256 =
        "7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$expectedSigner = $ExpectedSignerSha256.Replace(":", "").ToLowerInvariant()
if ($PSCmdlet.ParameterSetName -eq "FirstInstall") {
    if (-not $FirstInstall -or $ExpectedPackage -ne "org.nanokvm.mobile.development") {
        throw "-FirstInstall is only permitted for org.nanokvm.mobile.development and must be enabled."
    }
    if ($expectedSigner -ne "7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746") {
        throw "First installs require the established development signing certificate."
    }
}

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
    $signerMatches = [regex]::Matches(
        $signatureOutput,
        '(?m)^(?:Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\r?$'
    )
    if ($signatureOutput -notmatch '(?m)^Number of signers: 1\r?$' -or $signerMatches.Count -ne 1) {
        throw "Expected exactly one APK signer for $resolvedApk; found $($signerMatches.Count)."
    }

    return [pscustomobject]@{
        Path = $resolvedApk
        Package = $packageMatch.Groups[1].Value
        VersionCode = [long]$packageMatch.Groups[2].Value
        VersionName = $packageMatch.Groups[3].Value
        SignerSha256 = $signerMatches[0].Groups[1].Value.ToLowerInvariant()
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

$candidate = Get-ApkMetadata -Apk $CandidateApk -Aapt $aapt -ApkSigner $apkSigner
if (($ExpectedVersionName -and $candidate.VersionName -cne $ExpectedVersionName) -or
    ($ExpectedVersionCode -and $candidate.VersionCode -ne $ExpectedVersionCode)) {
    throw 'Candidate version disagrees with the selected target record.'
}
if ($FirstInstall) {
    if ($candidate.Package -ne $ExpectedPackage) {
        throw "Package mismatch: expected $ExpectedPackage, candidate=$($candidate.Package)."
    }
    if ($candidate.VersionCode -ne 20 -or $candidate.VersionName -ne "0.3.13-dev") {
        throw "-FirstInstall is only permitted for the initial development version 0.3.13-dev, code 20. Later versions require -PreviousApk."
    }
    if ($candidate.SignerSha256 -ne $expectedSigner) {
        throw "The candidate APK is not on the expected development signing lineage."
    }
} else {
    $previous = Get-ApkMetadata -Apk $PreviousApk -Aapt $aapt -ApkSigner $apkSigner
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
}

$candidateHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate.Path).Hash.ToLowerInvariant()
if ($FirstInstall) {
    Write-Output (
        "First-install development APK verified: {0} {1} ({2}); signer {3}; SHA-256 {4}" -f
        $ExpectedPackage,
        $candidate.VersionName,
        $candidate.VersionCode,
        $candidate.SignerSha256,
        $candidateHash
    )
} else {
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
}
