[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceTag,

    [Parameter(Mandatory = $true)]
    [string]$UnsignedEvidencePath,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedEvidenceSha256,

    [Parameter(Mandatory = $true)]
    [string]$JavaPath,

    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedUploadCertificateSha256,

    [ValidateSet("PKCS12", "JKS")]
    [string]$KeystoreType = "PKCS12",

    [string]$AppSigningLineagePath,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "release-lint-advisories.ps1")

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

function Resolve-ReleaseInputPath {
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
    if (-not (Test-Path -LiteralPath $candidate)) {
        throw "Release input does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Resolve-EvidenceArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Record,

        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $relativePath = [string]$Record.path
    if ([string]::IsNullOrWhiteSpace($relativePath) -or [IO.Path]::IsPathRooted($relativePath)) {
        throw "Evidence artifact paths must be repository-relative."
    }
    $repositoryPrefix = $Repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $candidate = [IO.Path]::GetFullPath(
        (Join-Path $Repository $relativePath.Replace('/', [IO.Path]::DirectorySeparatorChar))
    )
    if (-not $candidate.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Evidence artifact path escapes the tagged repository: $relativePath"
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Evidence artifact is missing: $relativePath"
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $resolved.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Evidence artifact resolves outside the tagged repository: $relativePath"
    }
    $item = Get-Item -LiteralPath $resolved
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash.ToLowerInvariant()
    $recordHash = Get-NormalizedSha256 -Value ([string]$Record.sha256) -Name "Evidence artifact SHA-256"
    if ($item.Length -ne [long]$Record.length -or $actualHash -ne $recordHash) {
        throw "Evidence artifact bytes changed after review: $relativePath"
    }
    return $resolved
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
            throw "Play upload signing paths must not traverse a junction or symbolic link: $current"
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

function Test-JarSignatureMetadataEntry {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    return $Name -match '(?i)^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$'
}

function Get-BundlePayloadRecords {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Bundle
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $records = New-Object 'System.Collections.Generic.Dictionary[string,string]' (
        [StringComparer]::Ordinal
    )
    $archive = [IO.Compression.ZipFile]::OpenRead($Bundle)
    try {
        $entryNames = New-Object 'System.Collections.Generic.HashSet[string]' (
            [StringComparer]::Ordinal
        )
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName.Replace('\', '/')
            if (-not $entryNames.Add($name)) {
                throw "The app bundle contains a duplicate ZIP entry: $name"
            }
            if ($name.EndsWith('/') -or (Test-JarSignatureMetadataEntry -Name $name)) {
                continue
            }
            $stream = $entry.Open()
            $sha256 = [Security.Cryptography.SHA256]::Create()
            try {
                $digest = $sha256.ComputeHash($stream)
            } finally {
                $sha256.Dispose()
                $stream.Dispose()
            }
            $hash = [BitConverter]::ToString($digest).Replace("-", "").ToLowerInvariant()
            $records.Add($name, "$($entry.Length):$hash")
        }
    } finally {
        $archive.Dispose()
    }
    return $records
}

function Assert-SameBundlePayload {
    param(
        [Parameter(Mandatory = $true)]
        [Collections.Generic.Dictionary[string,string]]$Expected,

        [Parameter(Mandatory = $true)]
        [Collections.Generic.Dictionary[string,string]]$Actual
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "JAR signing changed the number of non-signature app bundle entries."
    }
    foreach ($entry in $Expected.GetEnumerator()) {
        if (-not $Actual.ContainsKey($entry.Key) -or $Actual[$entry.Key] -ne $entry.Value) {
            throw "JAR signing changed the reviewed app bundle payload: $($entry.Key)"
        }
    }
}

function Get-CertificateIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CertificateReport,

        [Parameter(Mandatory = $true)]
        [string]$Context
    )

    $fingerprintMatch = [regex]::Match(
        $CertificateReport,
        '(?im)^\s*SHA256:\s*([0-9a-f:]{64,})\s*$'
    )
    if (-not $fingerprintMatch.Success) {
        throw "Could not read the SHA-256 certificate fingerprint from $Context."
    }
    $subjectMatch = [regex]::Match($CertificateReport, '(?im)^\s*(?:Owner|Certificate owner):\s*(.+)$')
    return [pscustomobject]@{
        sha256 = Get-NormalizedSha256 `
            -Value $fingerprintMatch.Groups[1].Value `
            -Name "$Context certificate fingerprint"
        subject = $(if ($subjectMatch.Success) { $subjectMatch.Groups[1].Value.Trim() } else { "" })
    }
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$gitStatusResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "status", "--porcelain", "--untracked-files=all")
if ($gitStatusResult.ExitCode -ne 0) {
    throw "Git could not inspect the Play upload worktree."
}
if ($gitStatusResult.Output) {
    throw "Play upload signing requires a clean worktree at the exact source tag."
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
$canonicalTag = "v$sourceVersionName"
if ($SourceTag -cne $canonicalTag) {
    throw "The canonical release tag for source version $sourceVersionName is $canonicalTag."
}
$tagReference = "refs/tags/$SourceTag"
$tagLookup = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "show-ref", "--verify", "--quiet", $tagReference)
if ($tagLookup.ExitCode -ne 0) {
    throw "The canonical source tag does not exist: $tagReference"
}
$tagTypeResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "cat-file", "-t", $tagReference)
if ($tagTypeResult.ExitCode -ne 0 -or $tagTypeResult.Output.Trim() -ne "tag") {
    throw "The source tag must be annotated or cryptographically signed: $SourceTag"
}
$headResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "rev-parse", "HEAD")
$tagCommitResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "rev-list", "-n", "1", $tagReference)
$headCommit = $headResult.Output.Trim()
$tagCommit = $tagCommitResult.Output.Trim()
if (
    $headResult.ExitCode -ne 0 -or
    $tagCommitResult.ExitCode -ne 0 -or
    $headCommit -notmatch '^[0-9a-f]{40}$' -or
    $tagCommit -ne $headCommit
) {
    throw "Source tag $SourceTag does not identify the checked-out commit $headCommit."
}

if (-not $AppSigningLineagePath) {
    $AppSigningLineagePath = Join-Path $repository "release\production-signing-lineage.json"
}
$resolvedLineage = Resolve-ReleaseInputPath -Path $AppSigningLineagePath -Repository $repository
if (-not $resolvedLineage.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The installed-app signing lineage record must be stored in the tagged repository."
}
$relativeLineage = $resolvedLineage.Substring($repositoryPrefix.Length).Replace('\', '/')
$lineageTrackingResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "ls-files", "--error-unmatch", "--", $relativeLineage)
if ($lineageTrackingResult.ExitCode -ne 0) {
    throw "The installed-app signing lineage record must be tracked by the tagged source."
}
$lineage = Get-Content -Raw -LiteralPath $resolvedLineage | ConvertFrom-Json
if (
    $lineage.schemaVersion -ne 1 -or
    $lineage.package -ne "org.nanokvm.mobile" -or
    [string]::IsNullOrWhiteSpace([string]$lineage.keyAlias)
) {
    throw "The tagged installed-app signing lineage record is invalid."
}
$appSigningCertificate = Get-NormalizedSha256 `
    -Value ([string]$lineage.signingCertificateSha256) `
    -Name "Installed-app signing certificate"
$expectedUploadCertificate = Get-NormalizedSha256 `
    -Value $ExpectedUploadCertificateSha256 `
    -Name "ExpectedUploadCertificateSha256"
$forbiddenDevelopmentSigners = @(
    "7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746",
    "149d694db3d3b0d86849d1f99a570fb78c11627739494aa8c4e04eec6e276002"
)
if ($expectedUploadCertificate -eq $appSigningCertificate) {
    throw "The Play upload key must be separate from the installed-app signing key."
}
if ($expectedUploadCertificate -in $forbiddenDevelopmentSigners) {
    throw "A development/debug certificate cannot be used as the Play upload key."
}
if ($KeyAlias.Equals([string]$lineage.keyAlias, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Use a distinct alias for the Play upload key and installed-app signing key."
}

$resolvedJava = Resolve-ReleaseInputPath -Path $JavaPath -Repository $repository
if (-not (Test-Path -LiteralPath $resolvedJava -PathType Leaf)) {
    throw "The explicit JDK 21 java executable does not exist: $resolvedJava"
}
$javaResult = Invoke-NativeCapture -FilePath $resolvedJava -Arguments @("-version")
$javaVersion = $javaResult.Output
if ($javaResult.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw "Play upload signing must use JDK 21. Found: $javaVersion"
}
$jdkBin = Split-Path -Parent $resolvedJava
$jarsigner = Join-Path $jdkBin "jarsigner.exe"
$keytool = Join-Path $jdkBin "keytool.exe"
foreach ($tool in @($jarsigner, $keytool)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "The explicit JDK 21 installation lacks $tool."
    }
}
$javaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedJava).Hash.ToLowerInvariant()
$jarsignerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarsigner).Hash.ToLowerInvariant()
$keytoolHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $keytool).Hash.ToLowerInvariant()

$resolvedEvidence = Resolve-ReleaseInputPath -Path $UnsignedEvidencePath -Repository $repository
if (-not $resolvedEvidence.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The unsigned evidence manifest must remain under the tagged repository."
}
$evidenceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedEvidence).Hash.ToLowerInvariant()
$normalizedExpectedEvidence = Get-NormalizedSha256 `
    -Value $ExpectedEvidenceSha256 `
    -Name "ExpectedEvidenceSha256"
if ($evidenceHash -ne $normalizedExpectedEvidence) {
    throw "The unsigned evidence manifest does not match the reviewed SHA-256 digest."
}
$evidence = Get-Content -Raw -LiteralPath $resolvedEvidence | ConvertFrom-Json
if (
    $evidence.schemaVersion -ne 2 -or
    $evidence.sourceTag -cne $SourceTag -or
    $evidence.sourceCommit -ne $headCommit -or
    $evidence.package -ne "org.nanokvm.mobile" -or
    $evidence.versionName -ne $sourceVersionName -or
    [long]$evidence.versionCode -ne $sourceVersionCode -or
    [int]$evidence.minimumSdk -ne 26 -or
    [int]$evidence.targetSdk -lt 36 -or
    [bool]$evidence.debuggable -or
    [bool]$evidence.profileable -or
    [bool]$evidence.testOnly
) {
    throw "The unsigned evidence manifest does not match the clean tagged Play source identity."
}
if (
    [int]$evidence.tests.suites -le 0 -or
    [int]$evidence.tests.tests -le 0 -or
    [int]$evidence.tests.failures -ne 0 -or
    [int]$evidence.tests.errors -ne 0
) {
    throw "The reviewed evidence does not contain passing JVM test results."
}
$reviewedTestReports = @($evidence.tests.reports)
if ($reviewedTestReports.Count -ne [int]$evidence.tests.suites) {
    throw "The reviewed evidence does not contain every JVM test report."
}
foreach ($testReport in $reviewedTestReports) {
    [void](Resolve-EvidenceArtifact -Record $testReport -Repository $repository)
}
$reviewedLint = @($evidence.lint)
$reviewedPlayLint = @($reviewedLint | Where-Object {
    [string]$_.path -match '/lint-results-play\.xml$'
})
if ($reviewedLint.Count -lt 3) {
    throw "The reviewed evidence does not contain the required clean lint results."
}
foreach ($lintReport in $reviewedLint) {
    $resolvedLintReport = Resolve-EvidenceArtifact `
        -Record $lintReport `
        -Repository $repository
    $lintAssessment = Get-NanoKvmReleaseLintAssessment `
        -Path $resolvedLintReport `
        -Repository $repository
    if (
        $lintAssessment.blockingIssues -ne 0 -or
        -not (Test-NanoKvmLintEvidenceMatches `
            -EvidenceRecord $lintReport `
            -Assessment $lintAssessment)
    ) {
        throw "A retained lint report has unreviewed issues or differs from its evidence record."
    }
}
if ($evidence.toolchain.javaSha256 -ne $javaHash) {
    throw "The explicit JDK does not match the reviewed unsigned evidence toolchain."
}
$bundleProperty = $evidence.PSObject.Properties["playBundle"]
if ($null -eq $bundleProperty) {
    throw "The reviewed unsigned evidence has no playBundle record."
}
$resolvedUnsignedBundle = Resolve-EvidenceArtifact `
    -Record $bundleProperty.Value `
    -Repository $repository
$playApkProperty = $evidence.PSObject.Properties["playUnsignedApk"]
$playVariantProperty = $evidence.PSObject.Properties["playVariant"]
if ($null -eq $playApkProperty -or $null -eq $playVariantProperty) {
    throw "Play upload signing requires the reviewed Play APK and Play task evidence."
}
[void](Resolve-EvidenceArtifact -Record $playApkProperty.Value -Repository $repository)
$playVariant = $playVariantProperty.Value
if (
    $playVariant.unitTestTask -ne ":app:testPlayUnitTest" -or
    $playVariant.lintTask -ne ":app:lintPlay" -or
    $playVariant.assembleTask -ne ":app:assemblePlay" -or
    $playVariant.bundleTask -ne ":app:bundlePlay" -or
    $playVariant.profileVerificationTask -ne ":app:verifyPlayProfiles" -or
    $playVariant.jarsignerSha256 -ne $jarsignerHash -or
    $playVariant.keytoolSha256 -ne $keytoolHash -or
    $reviewedLint.Count -lt 4 -or
    $reviewedPlayLint.Count -ne 1
) {
    throw "The reviewed evidence does not contain the complete strict Play build gates."
}
$playTestReports = @($reviewedTestReports | Where-Object {
    [string]$_.path -match '/testPlayUnitTest/'
})
if ($playTestReports.Count -le 0) {
    throw "The reviewed evidence does not retain the Play-specific unit-test results."
}
$playR8Property = $evidence.PSObject.Properties["playR8"]
if ($null -eq $playR8Property) {
    throw "The reviewed evidence does not retain the Play-specific R8 outputs."
}
$verifiedPlayR8 = [ordered]@{}
foreach ($recordName in @("mapping", "seeds", "usage", "configuration", "resources")) {
    $recordProperty = $playR8Property.Value.PSObject.Properties[$recordName]
    if ($null -eq $recordProperty -or [long]$recordProperty.Value.length -le 0) {
        throw "The reviewed evidence is missing its nonempty Play R8 $recordName record."
    }
    $verifiedPlayR8[$recordName] = Resolve-EvidenceArtifact `
        -Record $recordProperty.Value `
        -Repository $repository
}
if ([IO.Path]::GetExtension($resolvedUnsignedBundle) -cne ".aab") {
    throw "The reviewed playBundle artifact must use the .aab extension."
}
$unsignedBundleHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedUnsignedBundle
).Hash.ToLowerInvariant()
$unsignedPayload = Get-BundlePayloadRecords -Bundle $resolvedUnsignedBundle
foreach ($requiredEntry in @("BundleConfig.pb", "base/manifest/AndroidManifest.xml")) {
    if (-not $unsignedPayload.ContainsKey($requiredEntry)) {
        throw "The reviewed playBundle is not a complete Android App Bundle; missing $requiredEntry."
    }
}
$unsignedSignatureResult = Invoke-NativeCapture `
    -FilePath $jarsigner `
    -Arguments @("-verify", "-verbose:summary", "-certs", $resolvedUnsignedBundle)
if (
    $unsignedSignatureResult.ExitCode -ne 0 -or
    $unsignedSignatureResult.Output -notmatch '(?i)jar is unsigned' -or
    $unsignedSignatureResult.Output -match '(?i)jar verified'
) {
    throw "The reviewed playBundle is already or partially JAR-signed."
}

$resolvedKeystore = Resolve-ReleaseInputPath -Path $KeystorePath -Repository $repository
if (-not (Test-Path -LiteralPath $resolvedKeystore -PathType Leaf)) {
    throw "The Play upload keystore does not exist: $resolvedKeystore"
}
if ($resolvedKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The Play upload keystore must remain outside the repository: $resolvedKeystore"
}
Assert-NoReparsePointInPath -Path $resolvedKeystore
Assert-SingleHardLink -Path $resolvedKeystore
Assert-ProtectedAcl `
    -Path (Split-Path -Parent $resolvedKeystore) `
    -RequireProtectedInheritance
Assert-ProtectedAcl -Path $resolvedKeystore

if (-not $OutputPath) {
    $OutputPath = Join-Path $repository (
        "dist\NanoKVM-Mobile-$sourceVersionName-v$sourceVersionCode-play-upload.aab"
    )
}
$absoluteOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
    [IO.Path]::GetFullPath($OutputPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $repository $OutputPath))
}
foreach ($protectedInput in @($resolvedUnsignedBundle, $resolvedEvidence, $resolvedKeystore)) {
    if ($absoluteOutput.Equals($protectedInput, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The Play upload output must not replace an input, evidence file, or keystore."
    }
}
if ([IO.Path]::GetExtension($absoluteOutput) -cne ".aab") {
    throw "The Play upload output path must use the .aab extension."
}
$outputDirectory = Split-Path -Parent $absoluteOutput
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}
$resolvedOutputDirectory = (Resolve-Path -LiteralPath $outputDirectory).Path
$outputBaseName = [IO.Path]::GetFileNameWithoutExtension($absoluteOutput)
$checksumPath = Join-Path $resolvedOutputDirectory "$outputBaseName-SHA256SUMS.txt"
$certificateDigestPath = Join-Path $resolvedOutputDirectory "$outputBaseName-upload-certificate-sha256.txt"
$certificatePemPath = Join-Path $resolvedOutputDirectory "$outputBaseName-upload-certificate.pem"
$verificationPath = Join-Path $resolvedOutputDirectory "$outputBaseName-jarsigner-verify.txt"
$metadataPath = Join-Path $resolvedOutputDirectory "$outputBaseName-play-upload-metadata.json"
$finalPaths = @(
    $absoluteOutput,
    $checksumPath,
    $certificateDigestPath,
    $certificatePemPath,
    $verificationPath,
    $metadataPath
)
foreach ($path in $finalPaths) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite an existing Play upload release file: $path"
    }
}

$stagingDirectory = Join-Path $resolvedOutputDirectory (
    ".nanokvm-play-upload-staging-" + [guid]::NewGuid().ToString("N")
)
$stagingPrefix = $resolvedOutputDirectory.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$resolvedStaging = [IO.Path]::GetFullPath($stagingDirectory)
if (-not $resolvedStaging.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use an unexpected Play upload staging directory: $resolvedStaging"
}
$publishedPaths = [System.Collections.Generic.List[string]]::new()
$releaseCompleted = $false
New-Item -ItemType Directory -Path $resolvedStaging | Out-Null

try {
    $stagedUnsigned = Join-Path $resolvedStaging "reviewed-release-bundle.aab"
    $stagedSigned = Join-Path $resolvedStaging (Split-Path -Leaf $absoluteOutput)
    $stagedChecksum = Join-Path $resolvedStaging (Split-Path -Leaf $checksumPath)
    $stagedCertificateDigest = Join-Path $resolvedStaging (Split-Path -Leaf $certificateDigestPath)
    $stagedCertificatePem = Join-Path $resolvedStaging (Split-Path -Leaf $certificatePemPath)
    $stagedVerification = Join-Path $resolvedStaging (Split-Path -Leaf $verificationPath)
    $stagedMetadata = Join-Path $resolvedStaging (Split-Path -Leaf $metadataPath)

    Copy-Item -LiteralPath $resolvedUnsignedBundle -Destination $stagedUnsigned
    $stagedUnsignedHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $stagedUnsigned
    ).Hash.ToLowerInvariant()
    if ($stagedUnsignedHash -ne $unsignedBundleHash) {
        throw "The staged app bundle does not match the reviewed playBundle."
    }

    Write-Output (
        "jarsigner will prompt for the Play upload keystore/key password; " +
        "passwords are not accepted as script arguments."
    )
    $signingArguments = @(
        "-keystore", $resolvedKeystore,
        "-storetype", $KeystoreType,
        "-signedjar", $stagedSigned,
        "-digestalg", "SHA-256",
        "-sigalg", "SHA256withRSA",
        $stagedUnsigned,
        $KeyAlias
    )
    $signingExitCode = 1
    Invoke-NativeInteractive `
        -FilePath $jarsigner `
        -Arguments $signingArguments `
        -ExitCode ([ref]$signingExitCode)
    if ($signingExitCode -ne 0 -or -not (Test-Path -LiteralPath $stagedSigned -PathType Leaf)) {
        throw "App Bundle JAR signing failed with exit code $signingExitCode."
    }

    $verificationResult = Invoke-NativeCapture `
        -FilePath $jarsigner `
        -Arguments @("-verify", "-verbose:summary", "-certs", $stagedSigned)
    if (
        $verificationResult.ExitCode -ne 0 -or
        $verificationResult.Output -notmatch '(?i)jar verified\.' -or
        $verificationResult.Output -match '(?i)jar is unsigned|unsigned entries|\(Unsigned entries\)'
    ) {
        throw "The signed App Bundle failed complete JAR signature verification."
    }
    $signedPayload = Get-BundlePayloadRecords -Bundle $stagedSigned
    Assert-SameBundlePayload -Expected $unsignedPayload -Actual $signedPayload

    $certificateReportResult = Invoke-NativeCapture `
        -FilePath $keytool `
        -Arguments @("-printcert", "-jarfile", $stagedSigned)
    if ($certificateReportResult.ExitCode -ne 0) {
        throw "keytool could not inspect the signed App Bundle certificate."
    }
    $signerMatches = [regex]::Matches($certificateReportResult.Output, '(?im)^Signer #[0-9]+:\s*$')
    if ($signerMatches.Count -ne 1) {
        throw "Expected exactly one App Bundle signer; found $($signerMatches.Count)."
    }
    $actualCertificate = Get-CertificateIdentity `
        -CertificateReport $certificateReportResult.Output `
        -Context "signed App Bundle"
    if ($actualCertificate.sha256 -ne $expectedUploadCertificate) {
        throw (
            "Play upload certificate mismatch. Expected $expectedUploadCertificate; " +
            "got $($actualCertificate.sha256)."
        )
    }
    if (
        $actualCertificate.sha256 -eq $appSigningCertificate -or
        $actualCertificate.sha256 -in $forbiddenDevelopmentSigners
    ) {
        throw "The signed App Bundle does not use an independent Play upload certificate."
    }

    $certificatePemResult = Invoke-NativeCapture `
        -FilePath $keytool `
        -Arguments @("-printcert", "-rfc", "-jarfile", $stagedSigned)
    if ($certificatePemResult.ExitCode -ne 0) {
        throw "keytool could not export the public Play upload certificate."
    }
    $pemMatch = [regex]::Match(
        $certificatePemResult.Output,
        '(?ms)-----BEGIN CERTIFICATE-----\s+.+?\s+-----END CERTIFICATE-----'
    )
    if (-not $pemMatch.Success) {
        throw "The public Play upload certificate export is invalid."
    }
    Set-Content -LiteralPath $stagedCertificatePem -Encoding ascii -Value $pemMatch.Value
    $exportedCertificateResult = Invoke-NativeCapture `
        -FilePath $keytool `
        -Arguments @("-printcert", "-file", $stagedCertificatePem)
    if ($exportedCertificateResult.ExitCode -ne 0) {
        throw "keytool could not verify the exported public Play upload certificate."
    }
    $exportedCertificate = Get-CertificateIdentity `
        -CertificateReport $exportedCertificateResult.Output `
        -Context "exported Play upload certificate"
    if ($exportedCertificate.sha256 -ne $actualCertificate.sha256) {
        throw "The exported public certificate does not match the signed App Bundle."
    }

    $signedBundleHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $stagedSigned
    ).Hash.ToLowerInvariant()
    $certificatePemHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $stagedCertificatePem
    ).Hash.ToLowerInvariant()
    $outputFileName = Split-Path -Leaf $absoluteOutput
    Set-Content -LiteralPath $stagedChecksum -Encoding ascii -Value "$signedBundleHash  $outputFileName"
    Set-Content `
        -LiteralPath $stagedCertificateDigest `
        -Encoding ascii `
        -Value $actualCertificate.sha256
    Set-Content `
        -LiteralPath $stagedVerification `
        -Encoding utf8 `
        -Value $verificationResult.Output
    $verificationHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $stagedVerification
    ).Hash.ToLowerInvariant()
    $relativeEvidence = $resolvedEvidence.Substring($repositoryPrefix.Length).Replace('\', '/')
    [pscustomobject]@{
        schemaVersion = 1
        sourceTag = $SourceTag
        sourceCommit = $headCommit
        package = [string]$evidence.package
        versionName = [string]$evidence.versionName
        versionCode = [long]$evidence.versionCode
        minimumSdk = [int]$evidence.minimumSdk
        targetSdk = [int]$evidence.targetSdk
        unsignedEvidence = $relativeEvidence
        unsignedEvidenceSha256 = $evidenceHash
        unsignedBundleEvidenceRecord = "playBundle"
        unsignedAppBundle = [string]$bundleProperty.Value.path
        unsignedAppBundleSha256 = $unsignedBundleHash
        playUploadBundle = $outputFileName
        playUploadBundleSha256 = $signedBundleHash
        uploadKeyAlias = $KeyAlias
        uploadCertificateSha256 = $actualCertificate.sha256
        uploadCertificateSubject = $actualCertificate.subject
        uploadCertificatePem = Split-Path -Leaf $certificatePemPath
        uploadCertificatePemSha256 = $certificatePemHash
        installedAppSigningCertificateSha256 = $appSigningCertificate
        identitiesAreSeparate = $actualCertificate.sha256 -ne $appSigningCertificate
        jarSignatureVerification = Split-Path -Leaf $verificationPath
        jarSignatureVerificationSha256 = $verificationHash
        playR8 = $playR8Property.Value
        signingAlgorithms = [pscustomobject]@{
            digest = "SHA-256"
            signature = "SHA256withRSA"
        }
        toolchain = [pscustomobject]@{
            javaVersion = $javaVersion
            javaSha256 = $javaHash
            jarsignerSha256 = $jarsignerHash
            keytoolSha256 = $keytoolHash
        }
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stagedMetadata -Encoding utf8

    $stagedFiles = @(
        $stagedSigned,
        $stagedChecksum,
        $stagedCertificateDigest,
        $stagedCertificatePem,
        $stagedVerification,
        $stagedMetadata
    )
    for ($index = 0; $index -lt $stagedFiles.Count; $index++) {
        if (Test-Path -LiteralPath $finalPaths[$index]) {
            throw "Refusing to overwrite an existing Play upload release file: $($finalPaths[$index])"
        }
        Move-Item -LiteralPath $stagedFiles[$index] -Destination $finalPaths[$index]
        $publishedPaths.Add($finalPaths[$index])
    }

    $releaseCompleted = $true
    Write-Output "Prepared signed Google Play upload AAB: $absoluteOutput"
    Write-Output "SHA-256: $signedBundleHash"
    Write-Output "Upload certificate SHA-256: $($actualCertificate.sha256)"
    Write-Output "Public upload certificate: $certificatePemPath"
    Write-Output "Play upload metadata: $metadataPath"
} finally {
    if (-not $releaseCompleted) {
        foreach ($publishedPath in $publishedPaths) {
            if (Test-Path -LiteralPath $publishedPath -PathType Leaf) {
                Remove-Item -LiteralPath $publishedPath -Force
            }
        }
    }
    if (Test-Path -LiteralPath $resolvedStaging) {
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}
