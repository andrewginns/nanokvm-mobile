[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [Parameter(Mandatory = $true)]
    [string]$JavaPath,

    [string]$KeyAlias = "nanokvm-play-upload",
    [string]$DistinguishedName = "CN=NanoKVM Mobile Play Upload, O=NanoKVM Mobile",
    [string]$CertificateOutputPath,
    [string]$AppSigningLineagePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @()
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $rawOutput = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($rawOutput | ForEach-Object { $_.ToString() }) -join "`n"
    }
}

function Invoke-NativeInteractive {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [Parameter(Mandatory = $true)]
        [ref]$ExitCode
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $FilePath @Arguments
        $ExitCode.Value = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Get-NormalizedSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $normalized = $Value.Replace(":", "").Trim().ToLowerInvariant()
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw "$Name must contain one SHA-256 digest."
    }
    return $normalized
}

function Resolve-RepositoryInputPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $candidate = if ([IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $Repository $Path
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Required input does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Assert-NoReparsePointInPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($fullPath)
    $current = $root
    $components = $fullPath.Substring($root.Length).Split(
        [char[]]@('\', '/'),
        [StringSplitOptions]::RemoveEmptyEntries
    )
    foreach ($component in $components) {
        $current = Join-Path $current $component
        $item = Get-Item -LiteralPath $current -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Play upload key paths must not traverse a junction or symbolic link: $current"
        }
    }
}

function Assert-SingleHardLink {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fsutil = (Get-Command "fsutil.exe" -ErrorAction Stop).Source
    $hardLinkResult = Invoke-NativeCapture `
        -FilePath $fsutil `
        -Arguments @("hardlink", "list", $Path)
    $hardLinks = @($hardLinkResult.Output -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    })
    if ($hardLinkResult.ExitCode -ne 0 -or $hardLinks.Count -ne 1) {
        throw "The Play upload keystore must have exactly one filesystem link."
    }
}

function Assert-ProtectedAcl {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [switch]$RequireProtectedInheritance
    )

    $acl = Get-Acl -LiteralPath $Path
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $systemSid = "S-1-5-18"
    $administratorsSid = "S-1-5-32-544"
    $allowedSids = @($currentSid, $systemSid, $administratorsSid)
    $ownerSid = ([Security.Principal.NTAccount]$acl.Owner).Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
    if ($ownerSid -ne $currentSid) {
        throw "The protected key path must be owned by the current account: $Path"
    }
    if ($RequireProtectedInheritance -and -not $acl.AreAccessRulesProtected) {
        throw "The protected keystore directory must disable inherited NTFS permissions."
    }

    $currentAccountHasFullControl = $false
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        $ruleSid = $rule.IdentityReference.Translate(
            [Security.Principal.SecurityIdentifier]
        ).Value
        if ($ruleSid -notin $allowedSids) {
            throw "The protected key path grants access to an unexpected identity: $($rule.IdentityReference)"
        }
        if (
            $ruleSid -eq $currentSid -and
            (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
                [Security.AccessControl.FileSystemRights]::FullControl)
        ) {
            $currentAccountHasFullControl = $true
        }
    }
    if (-not $currentAccountHasFullControl) {
        throw "The current account requires FullControl on the protected key path: $Path"
    }
}

function Get-CertificateIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CertificateReport
    )

    $fingerprintMatch = [regex]::Match(
        $CertificateReport,
        '(?im)^\s*SHA256:\s*([0-9a-f:]{64,})\s*$'
    )
    if (-not $fingerprintMatch.Success) {
        throw "Could not read the new Play upload certificate SHA-256 fingerprint."
    }
    $subjectMatch = [regex]::Match($CertificateReport, '(?im)^\s*Owner:\s*(.+)$')
    return [pscustomobject]@{
        sha256 = Get-NormalizedSha256 `
            -Value $fingerprintMatch.Groups[1].Value `
            -Name "Play upload certificate fingerprint"
        subject = $(if ($subjectMatch.Success) { $subjectMatch.Groups[1].Value.Trim() } else { "" })
    }
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not $AppSigningLineagePath) {
    $AppSigningLineagePath = Join-Path $repository "release\production-signing-lineage.json"
}
$resolvedLineage = Resolve-RepositoryInputPath `
    -Path $AppSigningLineagePath `
    -Repository $repository
if (-not $resolvedLineage.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The installed-app signing lineage record must remain inside the repository."
}
$lineage = Get-Content -Raw -LiteralPath $resolvedLineage | ConvertFrom-Json
if (
    $lineage.schemaVersion -ne 1 -or
    $lineage.package -ne "org.nanokvm.mobile" -or
    [string]::IsNullOrWhiteSpace([string]$lineage.keyAlias)
) {
    throw "The installed-app signing lineage record is invalid."
}
$appSigningCertificate = Get-NormalizedSha256 `
    -Value ([string]$lineage.signingCertificateSha256) `
    -Name "Installed-app signing certificate"
if ($KeyAlias.Equals([string]$lineage.keyAlias, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Use a distinct alias for the Play upload key and installed-app signing key."
}

$resolvedJava = Resolve-RepositoryInputPath -Path $JavaPath -Repository $repository
$javaResult = Invoke-NativeCapture -FilePath $resolvedJava -Arguments @("-version")
if ($javaResult.ExitCode -ne 0 -or $javaResult.Output -notmatch 'version "21\.') {
    throw "Play upload keys must be generated with JDK 21. Found: $($javaResult.Output)"
}
$keytool = Join-Path (Split-Path -Parent $resolvedJava) "keytool.exe"
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw "The explicit JDK 21 installation lacks keytool.exe."
}

$requestedKeystore = if ([IO.Path]::IsPathRooted($KeystorePath)) {
    [IO.Path]::GetFullPath($KeystorePath)
} else {
    [IO.Path]::GetFullPath((Join-Path $repository $KeystorePath))
}
$requestedDirectory = Split-Path -Parent $requestedKeystore
if ($requestedKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The Play upload keystore must remain outside the repository: $requestedKeystore"
}
if (-not (Test-Path -LiteralPath $requestedDirectory -PathType Container)) {
    throw (
        "The protected keystore directory must already exist so its access controls can be reviewed: " +
        $requestedDirectory
    )
}
$resolvedDirectory = (Resolve-Path -LiteralPath $requestedDirectory).Path
Assert-NoReparsePointInPath -Path $resolvedDirectory
Assert-ProtectedAcl -Path $resolvedDirectory -RequireProtectedInheritance
$absoluteKeystore = Join-Path $resolvedDirectory (Split-Path -Leaf $requestedKeystore)
if (Test-Path -LiteralPath $absoluteKeystore) {
    throw "Refusing to overwrite an existing Play upload keystore: $absoluteKeystore"
}
if ([IO.Path]::GetExtension($absoluteKeystore) -cne ".p12") {
    throw "The Play upload keystore path must use the .p12 extension."
}

if (-not $CertificateOutputPath) {
    $CertificateOutputPath = Join-Path $resolvedDirectory (
        [IO.Path]::GetFileNameWithoutExtension($absoluteKeystore) + "-upload-certificate.pem"
    )
}
$absoluteCertificate = if ([IO.Path]::IsPathRooted($CertificateOutputPath)) {
    [IO.Path]::GetFullPath($CertificateOutputPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $repository $CertificateOutputPath))
}
if ([IO.Path]::GetExtension($absoluteCertificate) -cne ".pem") {
    throw "The public Play upload certificate path must use the .pem extension."
}
if ($absoluteCertificate.Equals($absoluteKeystore, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The public certificate and private keystore require different output paths."
}
$certificateDirectory = Split-Path -Parent $absoluteCertificate
if (-not (Test-Path -LiteralPath $certificateDirectory -PathType Container)) {
    throw "The public certificate output directory must already exist: $certificateDirectory"
}
if (Test-Path -LiteralPath $absoluteCertificate) {
    throw "Refusing to overwrite an existing public Play upload certificate: $absoluteCertificate"
}
$resolvedCertificateDirectory = (Resolve-Path -LiteralPath $certificateDirectory).Path
$stagedCertificate = Join-Path $resolvedCertificateDirectory (
    ".nanokvm-play-upload-certificate-" + [guid]::NewGuid().ToString("N") + ".pem"
)

Write-Output (
    "keytool will prompt for a strong Play upload password; passwords are not accepted " +
    "as script arguments."
)
$generateArguments = @(
    "-genkeypair",
    "-v",
    "-storetype", "PKCS12",
    "-keystore", $absoluteKeystore,
    "-alias", $KeyAlias,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-sigalg", "SHA256withRSA",
    "-validity", "10000",
    "-dname", $DistinguishedName
)
$generateExitCode = 1
Invoke-NativeInteractive `
    -FilePath $keytool `
    -Arguments $generateArguments `
    -ExitCode ([ref]$generateExitCode)
if ($generateExitCode -ne 0) {
    if (Test-Path -LiteralPath $absoluteKeystore -PathType Leaf) {
        Remove-Item -LiteralPath $absoluteKeystore -Force
    }
    throw "Play upload keystore generation failed with exit code $generateExitCode."
}
Assert-NoReparsePointInPath -Path $absoluteKeystore
Assert-SingleHardLink -Path $absoluteKeystore
Assert-ProtectedAcl -Path $absoluteKeystore

Write-Output "Re-enter the password once so keytool can export the public upload certificate."
$exportExitCode = 1
try {
    Invoke-NativeInteractive `
        -FilePath $keytool `
        -Arguments @(
            "-exportcert",
            "-rfc",
            "-storetype", "PKCS12",
            "-keystore", $absoluteKeystore,
            "-alias", $KeyAlias,
            "-file", $stagedCertificate
        ) `
        -ExitCode ([ref]$exportExitCode)
    if ($exportExitCode -ne 0 -or -not (Test-Path -LiteralPath $stagedCertificate -PathType Leaf)) {
        throw (
            "The upload keystore was created, but its public certificate could not be exported. " +
            "Preserve the keystore and inspect it manually with keytool."
        )
    }
    $certificateReportResult = Invoke-NativeCapture `
        -FilePath $keytool `
        -Arguments @("-printcert", "-file", $stagedCertificate)
    if ($certificateReportResult.ExitCode -ne 0) {
        throw "The exported public Play upload certificate could not be verified."
    }
    $certificate = Get-CertificateIdentity -CertificateReport $certificateReportResult.Output
    $forbiddenSigners = @(
        $appSigningCertificate,
        "7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746",
        "149d694db3d3b0d86849d1f99a570fb78c11627739494aa8c4e04eec6e276002"
    )
    if ($certificate.sha256 -in $forbiddenSigners) {
        throw "The new Play upload certificate is not independent of existing app/development keys."
    }
    if (Test-Path -LiteralPath $absoluteCertificate) {
        throw "Refusing to overwrite an existing public Play upload certificate: $absoluteCertificate"
    }
    Move-Item -LiteralPath $stagedCertificate -Destination $absoluteCertificate
} finally {
    if (Test-Path -LiteralPath $stagedCertificate -PathType Leaf) {
        Remove-Item -LiteralPath $stagedCertificate -Force
    }
}

Write-Output "Created independent Play upload keystore: $absoluteKeystore"
Write-Output "Key alias: $KeyAlias"
Write-Output "Upload certificate SHA-256: $($certificate.sha256)"
Write-Output "Upload certificate subject: $($certificate.subject)"
Write-Output "Public upload certificate: $absoluteCertificate"
Write-Output (
    "Back up the keystore and password in separately controlled encrypted locations; " +
    "do not use this upload key as the installed-app signing key."
)
