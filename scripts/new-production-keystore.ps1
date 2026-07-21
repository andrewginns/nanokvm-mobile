[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [string]$KeyAlias = "nanokvm-release",
    [string]$DistinguishedName = "CN=NanoKVM Mobile, O=NanoKVM Mobile",
    [string]$Keytool = "keytool.exe"
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

        [string[]]$Arguments = @()
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $FilePath @Arguments | Out-Host
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return $exitCode
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
            throw "Production signing paths must not traverse a junction or symbolic link: $current"
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
        throw "The production keystore must have exactly one filesystem link."
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
        throw "The protected signing path must be owned by the signing account: $Path"
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
            throw "The protected signing path grants access to an unexpected identity: $($rule.IdentityReference)"
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
        throw "The signing account requires FullControl on the protected signing path: $Path"
    }
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$requestedKeystore = if ([IO.Path]::IsPathRooted($KeystorePath)) {
    [IO.Path]::GetFullPath($KeystorePath)
} else {
    [IO.Path]::GetFullPath((Join-Path $repository $KeystorePath))
}
$requestedDirectory = Split-Path -Parent $requestedKeystore
if (-not (Test-Path -LiteralPath $requestedDirectory -PathType Container)) {
    throw (
        "The protected keystore directory must already exist so its access controls can be reviewed: " +
        $requestedDirectory
    )
}
$resolvedDirectoryItem = Get-Item -LiteralPath (Resolve-Path -LiteralPath $requestedDirectory).Path -Force
Assert-NoReparsePointInPath -Path $resolvedDirectoryItem.FullName
Assert-ProtectedAcl `
    -Path $resolvedDirectoryItem.FullName `
    -RequireProtectedInheritance
$absoluteKeystore = Join-Path $resolvedDirectoryItem.FullName (Split-Path -Leaf $requestedKeystore)
if ($absoluteKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The production keystore must remain outside the repository: $absoluteKeystore"
}
if (Test-Path -LiteralPath $absoluteKeystore) {
    throw "Refusing to overwrite an existing keystore: $absoluteKeystore"
}

$keytoolCommand = Get-Command $Keytool -ErrorAction Stop
$resolvedKeytool = $keytoolCommand.Source
$java = Join-Path (Split-Path -Parent $resolvedKeytool) "java.exe"
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Could not find the Java runtime beside keytool: $java"
}
$javaResult = Invoke-NativeCapture -FilePath $java -Arguments @("-version")
$javaVersion = $javaResult.Output
if ($javaResult.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw "Production keys must be generated with the recorded JDK 21 toolchain. Found: $javaVersion"
}

Write-Output "keytool will prompt for a strong password; no password is accepted as a script argument."
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
$generateExitCode = Invoke-NativeInteractive `
    -FilePath $resolvedKeytool `
    -Arguments $generateArguments
if ($generateExitCode -ne 0) {
    if (Test-Path -LiteralPath $absoluteKeystore) {
        Remove-Item -LiteralPath $absoluteKeystore -Force
    }
    throw "Production keystore generation failed with exit code $generateExitCode."
}
Assert-NoReparsePointInPath -Path $absoluteKeystore
Assert-SingleHardLink -Path $absoluteKeystore
Assert-ProtectedAcl -Path $absoluteKeystore

Write-Output "Re-enter the password once so keytool can report the public certificate identity."
$certificateResult = Invoke-NativeCapture `
    -FilePath $resolvedKeytool `
    -Arguments @("-list", "-v", "-keystore", $absoluteKeystore, "-alias", $KeyAlias)
$certificateOutput = $certificateResult.Output
if ($certificateResult.ExitCode -ne 0) {
    throw (
        "The keystore was created, but its public certificate could not be inspected. " +
        "Do not delete it; rerun keytool -list -v manually."
    )
}
$fingerprintMatch = [regex]::Match($certificateOutput, 'SHA256:\s*([0-9A-Fa-f:]{95})')
if (-not $fingerprintMatch.Success) {
    throw "The keystore was created, but the SHA-256 certificate fingerprint was not found."
}
$fingerprint = $fingerprintMatch.Groups[1].Value.Replace(":", "").ToLowerInvariant()

Write-Output "Created production keystore: $absoluteKeystore"
Write-Output "Key alias: $KeyAlias"
Write-Output "Signing certificate SHA-256: $fingerprint"
Write-Output "Back up the keystore twice in encrypted, separately controlled locations before first use."
