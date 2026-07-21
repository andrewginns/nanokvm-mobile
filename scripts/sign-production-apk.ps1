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
    [string]$BuildToolsPath,

    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [Parameter(Mandatory = $true)]
    [string]$KeyAlias,

    [string]$LineagePath,
    [string]$OutputPath,
    [string]$PreviousProductionApk,
    [switch]$FirstProductionRelease
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Apk,

        [Parameter(Mandatory = $true)]
        [string]$Aapt2
    )

    $badging = (& $Aapt2 dump badging $Apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not inspect $Apk."
    }
    $packageMatch = [regex]::Match(
        $badging,
        "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'"
    )
    $minimumMatch = [regex]::Match($badging, "minSdkVersion:'([0-9]+)'")
    $targetMatch = [regex]::Match($badging, "targetSdkVersion:'([0-9]+)'")
    if (-not $packageMatch.Success -or -not $minimumMatch.Success -or -not $targetMatch.Success) {
        throw "Could not read package/version/SDK metadata from $Apk."
    }

    $manifest = (& $Aapt2 dump xmltree $Apk --file AndroidManifest.xml 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not inspect the manifest in $Apk."
    }

    return [pscustomobject]@{
        package = $packageMatch.Groups[1].Value
        versionCode = [long]$packageMatch.Groups[2].Value
        versionName = $packageMatch.Groups[3].Value
        minimumSdk = [int]$minimumMatch.Groups[1].Value
        targetSdk = [int]$targetMatch.Groups[1].Value
        debuggable = $manifest -match 'android:debuggable[^=]*=true'
        profileable = $manifest -match '(?m)^\s*E: profileable\b'
        testOnly = $manifest -match 'android:testOnly[^=]*=true'
    }
}

function Get-VerifiedSigner {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Apk,

        [Parameter(Mandatory = $true)]
        [string]$Java,

        [Parameter(Mandatory = $true)]
        [string]$ApkSignerJar
    )

    $signatureOutput = (& $Java -jar $ApkSignerJar verify --verbose --print-certs $Apk 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for $Apk`: $signatureOutput"
    }
    if (
        $signatureOutput -notmatch 'Verified using v2 scheme \(APK Signature Scheme v2\): true' -or
        $signatureOutput -notmatch 'Verified using v3 scheme \(APK Signature Scheme v3\): true'
    ) {
        throw "The APK must verify with both APK Signature Schemes v2 and v3: $Apk"
    }
    $signerMatches = [regex]::Matches(
        $signatureOutput,
        "Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)"
    )
    if ($signerMatches.Count -ne 1) {
        throw "Expected exactly one APK signer for $Apk; found $($signerMatches.Count)."
    }
    $subjectMatch = [regex]::Match($signatureOutput, 'Signer #1 certificate DN:\s*(.+)')
    return [pscustomobject]@{
        sha256 = $signerMatches[0].Groups[1].Value.ToLowerInvariant()
        subject = $(if ($subjectMatch.Success) { $subjectMatch.Groups[1].Value.Trim() } else { "" })
        verificationOutput = $signatureOutput
    }
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
    $item = Get-Item -LiteralPath $resolved
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash.ToLowerInvariant()
    if ($item.Length -ne [long]$Record.length -or $actualHash -ne [string]$Record.sha256) {
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
    $hardLinks = @(& $fsutil hardlink list $Path 2>&1 | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_.ToString())
    })
    if ($LASTEXITCODE -ne 0 -or $hardLinks.Count -ne 1) {
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
    return (Resolve-Path -LiteralPath $candidate).Path
}

if ($FirstProductionRelease.IsPresent -eq [bool]$PreviousProductionApk) {
    throw "Specify exactly one of -FirstProductionRelease or -PreviousProductionApk."
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$gitStatus = (& git -C $repository status --porcelain --untracked-files=all 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "Git could not inspect the release worktree."
}
if ($gitStatus) {
    throw "Production signing requires a clean worktree at the exact source tag."
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
& git -C $repository show-ref --verify --quiet $tagReference
if ($LASTEXITCODE -ne 0) {
    throw "The canonical source tag does not exist: $tagReference"
}
$tagType = ((& git -C $repository cat-file -t $tagReference 2>&1) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $tagType -ne "tag") {
    throw "The source tag must be annotated or cryptographically signed: $SourceTag"
}
$headCommit = ((& git -C $repository rev-parse HEAD 2>&1) -join "`n").Trim()
$tagCommit = ((& git -C $repository rev-list -n 1 $tagReference 2>&1) -join "`n").Trim()
if ($LASTEXITCODE -ne 0 -or $tagCommit -ne $headCommit) {
    throw "Source tag $SourceTag does not identify the checked-out commit $headCommit."
}

if (-not $LineagePath) {
    $LineagePath = Join-Path $repository "release\production-signing-lineage.json"
}
$resolvedLineage = Resolve-ReleaseInputPath -Path $LineagePath -Repository $repository
if (-not $resolvedLineage.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The production signing lineage record must be stored in the tagged repository."
}
$relativeLineage = $resolvedLineage.Substring($repositoryPrefix.Length).Replace('\', '/')
& git -C $repository ls-files --error-unmatch -- $relativeLineage 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "The production signing lineage record must be tracked by the tagged source."
}
$lineage = Get-Content -Raw -LiteralPath $resolvedLineage | ConvertFrom-Json
$previousLineageProperty = $lineage.PSObject.Properties["previousProduction"]
if (
    $lineage.schemaVersion -ne 1 -or
    $lineage.package -ne "org.nanokvm.mobile" -or
    $lineage.keyAlias -cne $KeyAlias -or
    [long]$lineage.firstProductionVersionCode -le 0 -or
    [string]::IsNullOrWhiteSpace([string]$lineage.firstProductionVersionName) -or
    $null -eq $previousLineageProperty
) {
    throw "The tagged production signing lineage record is invalid."
}
$normalizedExpectedSigner = ([string]$lineage.signingCertificateSha256).Replace(
    ":", ""
).ToLowerInvariant()
if ($normalizedExpectedSigner -notmatch '^[0-9a-f]{64}$') {
    throw "The tagged signing lineage must contain one SHA-256 certificate digest."
}
if ($FirstProductionRelease) {
    if (
        $sourceVersionName -ne [string]$lineage.firstProductionVersionName -or
        $sourceVersionCode -ne [long]$lineage.firstProductionVersionCode
    ) {
        throw "First-production bootstrap is permitted only for the version frozen in the lineage record."
    }
    if ($null -ne $previousLineageProperty.Value) {
        throw "The first production release must not declare a preceding production APK."
    }
} elseif ($sourceVersionCode -le [long]$lineage.firstProductionVersionCode) {
    throw "Later production releases must have a version code above the lineage bootstrap version."
} else {
    $expectedPrevious = $previousLineageProperty.Value
    if ($null -eq $expectedPrevious) {
        throw "Later releases require the exact preceding APK identity in the tagged lineage record."
    }
    $expectedPreviousHash = ([string]$expectedPrevious.apkSha256).ToLowerInvariant()
    if (
        [string]::IsNullOrWhiteSpace([string]$expectedPrevious.versionName) -or
        [long]$expectedPrevious.versionCode -le 0 -or
        $expectedPreviousHash -notmatch '^[0-9a-f]{64}$'
    ) {
        throw "Later releases require the exact preceding APK identity in the tagged lineage record."
    }
}

$resolvedJava = Resolve-ReleaseInputPath -Path $JavaPath -Repository $repository
if (-not (Test-Path -LiteralPath $resolvedJava -PathType Leaf)) {
    throw "The explicit JDK 21 java executable does not exist: $resolvedJava"
}
$javaVersion = (& $resolvedJava -version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw "Production signing must use JDK 21. Found: $javaVersion"
}
$javaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedJava).Hash.ToLowerInvariant()

$resolvedBuildTools = Resolve-ReleaseInputPath -Path $BuildToolsPath -Repository $repository
if (-not (Test-Path -LiteralPath $resolvedBuildTools -PathType Container)) {
    throw "The explicit Android Build Tools directory does not exist: $resolvedBuildTools"
}
$buildToolsVersion = Split-Path -Leaf $resolvedBuildTools
if ($buildToolsVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "BuildToolsPath must identify one stable, exact x.y.z Build Tools directory."
}
$aapt2 = Join-Path $resolvedBuildTools "aapt2.exe"
$zipAlign = Join-Path $resolvedBuildTools "zipalign.exe"
$apkSignerJar = Join-Path $resolvedBuildTools "lib\apksigner.jar"
foreach ($tool in @($aapt2, $zipAlign, $apkSignerJar)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "The explicit Android Build Tools directory lacks $tool."
    }
}
$aapt2Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $aapt2).Hash.ToLowerInvariant()
$zipAlignHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipAlign).Hash.ToLowerInvariant()
$apkSignerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkSignerJar).Hash.ToLowerInvariant()

$resolvedEvidence = Resolve-ReleaseInputPath -Path $UnsignedEvidencePath -Repository $repository
$evidenceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedEvidence).Hash.ToLowerInvariant()
$normalizedExpectedEvidence = $ExpectedEvidenceSha256.Replace(":", "").ToLowerInvariant()
if ($normalizedExpectedEvidence -notmatch '^[0-9a-f]{64}$') {
    throw "ExpectedEvidenceSha256 must contain one SHA-256 evidence digest."
}
if ($evidenceHash -ne $normalizedExpectedEvidence) {
    throw "The unsigned evidence manifest does not match the reviewed SHA-256 digest."
}
$evidence = Get-Content -Raw -LiteralPath $resolvedEvidence | ConvertFrom-Json
if (
    $evidence.schemaVersion -ne 1 -or
    $evidence.sourceTag -cne $SourceTag -or
    $evidence.sourceCommit -ne $headCommit -or
    $evidence.package -ne "org.nanokvm.mobile" -or
    $evidence.versionName -ne $sourceVersionName -or
    [long]$evidence.versionCode -ne $sourceVersionCode -or
    [int]$evidence.minimumSdk -ne 26 -or
    [int]$evidence.targetSdk -ne 37 -or
    [bool]$evidence.debuggable -or
    [bool]$evidence.profileable -or
    [bool]$evidence.testOnly
) {
    throw "The unsigned evidence manifest does not match the clean tagged source identity."
}
if (
    [int]$evidence.tests.suites -le 0 -or
    [int]$evidence.tests.tests -le 0 -or
    [int]$evidence.tests.failures -ne 0 -or
    [int]$evidence.tests.errors -ne 0
) {
    throw "The reviewed evidence does not contain a passing JVM test result."
}
$reviewedTestReports = @($evidence.tests.reports)
if ($reviewedTestReports.Count -ne [int]$evidence.tests.suites) {
    throw "The reviewed evidence does not contain every JVM test report."
}
$reviewedLint = @($evidence.lint)
if ($reviewedLint.Count -lt 3 -or ($reviewedLint | Where-Object { [int]$_.issues -ne 0 })) {
    throw "The reviewed evidence does not contain the required clean release lint results."
}
if (
    $evidence.toolchain.javaSha256 -ne $javaHash -or
    $evidence.toolchain.buildToolsVersion -ne $buildToolsVersion -or
    $evidence.toolchain.aapt2Sha256 -ne $aapt2Hash -or
    $evidence.toolchain.zipalignSha256 -ne $zipAlignHash -or
    $evidence.toolchain.apksignerJarSha256 -ne $apkSignerHash
) {
    throw "The explicit signing toolchain does not match the reviewed unsigned evidence."
}

$requiredEvidenceRecords = @(
    "unsignedApk",
    "releaseBundle",
    "sbom",
    "mapping",
    "baselineProfile",
    "startupProfile",
    "strictBuildLog"
)
$verifiedEvidenceArtifacts = @{}
foreach ($recordName in $requiredEvidenceRecords) {
    $recordProperty = $evidence.PSObject.Properties[$recordName]
    if ($null -eq $recordProperty) {
        throw "The reviewed evidence is missing its $recordName artifact record."
    }
    $verifiedEvidenceArtifacts[$recordName] = Resolve-EvidenceArtifact `
        -Record $recordProperty.Value `
        -Repository $repository
}
foreach ($testReport in $reviewedTestReports) {
    [void](Resolve-EvidenceArtifact -Record $testReport -Repository $repository)
}
foreach ($lintReport in $reviewedLint) {
    [void](Resolve-EvidenceArtifact -Record $lintReport -Repository $repository)
}
$resolvedUnsignedApk = $verifiedEvidenceArtifacts["unsignedApk"]
$actualUnsignedHash = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedUnsignedApk
).Hash.ToLowerInvariant()
$unsignedMetadata = Get-ApkMetadata -Apk $resolvedUnsignedApk -Aapt2 $aapt2
if (
    $unsignedMetadata.package -ne $evidence.package -or
    $unsignedMetadata.versionName -ne $evidence.versionName -or
    $unsignedMetadata.versionCode -ne [long]$evidence.versionCode -or
    $unsignedMetadata.minimumSdk -ne [int]$evidence.minimumSdk -or
    $unsignedMetadata.targetSdk -ne [int]$evidence.targetSdk -or
    $unsignedMetadata.debuggable -or
    $unsignedMetadata.profileable -or
    $unsignedMetadata.testOnly
) {
    throw "The unsigned APK no longer matches its reviewed package/manifest evidence."
}
$unsignedVerification = (& $resolvedJava -jar $apkSignerJar verify $resolvedUnsignedApk 2>&1) -join "`n"
if ($LASTEXITCODE -eq 0) {
    throw "The reviewed production signing input is already signed."
}
if ($unsignedVerification -notmatch 'DOES NOT VERIFY|Missing META-INF') {
    throw "The unsigned APK failed signature inspection for an unexpected reason."
}

$forbiddenDevelopmentSigners = @(
    "7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746",
    "149d694db3d3b0d86849d1f99a570fb78c11627739494aa8c4e04eec6e276002"
)
if ($normalizedExpectedSigner -in $forbiddenDevelopmentSigners) {
    throw "A development/debug certificate cannot establish the production signing lineage."
}

$previousProductionHash = $null
$previousProductionVersionName = $null
$previousProductionVersionCode = $null
if ($PreviousProductionApk) {
    $resolvedPrevious = Resolve-ReleaseInputPath `
        -Path $PreviousProductionApk `
        -Repository $repository
    $previousSigner = Get-VerifiedSigner `
        -Apk $resolvedPrevious `
        -Java $resolvedJava `
        -ApkSignerJar $apkSignerJar
    $previousMetadata = Get-ApkMetadata -Apk $resolvedPrevious -Aapt2 $aapt2
    $previousHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedPrevious
    ).Hash.ToLowerInvariant()
    if ($previousSigner.sha256 -ne $normalizedExpectedSigner) {
        throw "The preceding production APK is not on the expected signing lineage."
    }
    if ($previousMetadata.package -ne $unsignedMetadata.package) {
        throw "The preceding production APK uses a different application ID."
    }
    if (
        $previousMetadata.versionName -ne [string]$expectedPrevious.versionName -or
        $previousMetadata.versionCode -ne [long]$expectedPrevious.versionCode -or
        $previousHash -ne $expectedPreviousHash -or
        $previousMetadata.debuggable -or
        $previousMetadata.profileable -or
        $previousMetadata.testOnly
    ) {
        throw "The supplied preceding APK does not match the exact tagged lineage record."
    }
    if ($sourceVersionCode -le $previousMetadata.versionCode) {
        throw (
            "Production version code must increase: previous=$($previousMetadata.versionCode), " +
            "candidate=$sourceVersionCode."
        )
    }
    $previousProductionHash = $previousHash
    $previousProductionVersionName = $previousMetadata.versionName
    $previousProductionVersionCode = $previousMetadata.versionCode
}

$resolvedKeystorePath = Resolve-ReleaseInputPath -Path $KeystorePath -Repository $repository
$resolvedKeystoreItem = Get-Item -LiteralPath $resolvedKeystorePath -Force
$resolvedKeystore = $resolvedKeystoreItem.FullName
if (-not (Test-Path -LiteralPath $resolvedKeystore -PathType Leaf)) {
    throw "The production keystore does not exist: $resolvedKeystore"
}
if ($resolvedKeystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The production keystore must remain outside the repository: $resolvedKeystore"
}
Assert-NoReparsePointInPath -Path $resolvedKeystore
Assert-SingleHardLink -Path $resolvedKeystore
Assert-ProtectedAcl `
    -Path (Split-Path -Parent $resolvedKeystore) `
    -RequireProtectedInheritance
Assert-ProtectedAcl -Path $resolvedKeystore

if (-not $OutputPath) {
    $OutputPath = Join-Path $repository (
        "dist\NanoKVM-Mobile-$sourceVersionName-v$sourceVersionCode-release.apk"
    )
}
if ([IO.Path]::IsPathRooted($OutputPath)) {
    $absoluteOutput = [IO.Path]::GetFullPath($OutputPath)
} else {
    $absoluteOutput = [IO.Path]::GetFullPath((Join-Path $repository $OutputPath))
}
if ($absoluteOutput -eq $resolvedUnsignedApk -or $absoluteOutput -eq $resolvedEvidence) {
    throw "The production output must not replace an input or evidence file."
}
if ([IO.Path]::GetExtension($absoluteOutput) -cne ".apk") {
    throw "The production output path must use the .apk extension."
}
$outputDirectory = Split-Path -Parent $absoluteOutput
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}
$resolvedOutputDirectory = (Resolve-Path -LiteralPath $outputDirectory).Path
$outputBaseName = [IO.Path]::GetFileNameWithoutExtension($absoluteOutput)
$checksumPath = Join-Path $resolvedOutputDirectory "$outputBaseName-SHA256SUMS.txt"
$signerPath = Join-Path $resolvedOutputDirectory "$outputBaseName-signing-certificate-sha256.txt"
$verificationPath = Join-Path $resolvedOutputDirectory "$outputBaseName-apksigner-verify.txt"
$metadataPath = Join-Path $resolvedOutputDirectory "$outputBaseName-release-metadata.json"
$finalPaths = @($absoluteOutput, $checksumPath, $signerPath, $verificationPath, $metadataPath)
foreach ($path in $finalPaths) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite an existing production release file: $path"
    }
}

$stagingDirectory = Join-Path $resolvedOutputDirectory (
    ".nanokvm-release-staging-" + [guid]::NewGuid().ToString("N")
)
$stagingPrefix = $resolvedOutputDirectory.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$resolvedStaging = [IO.Path]::GetFullPath($stagingDirectory)
if (-not $resolvedStaging.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use an unexpected release staging directory: $resolvedStaging"
}
$publishedPaths = [System.Collections.Generic.List[string]]::new()
$releaseCompleted = $false
New-Item -ItemType Directory -Path $resolvedStaging | Out-Null

try {
    $stagedAligned = Join-Path $resolvedStaging "app-release-aligned.apk"
    $stagedSigned = Join-Path $resolvedStaging (Split-Path -Leaf $absoluteOutput)
    $stagedChecksum = Join-Path $resolvedStaging (Split-Path -Leaf $checksumPath)
    $stagedSigner = Join-Path $resolvedStaging (Split-Path -Leaf $signerPath)
    $stagedVerification = Join-Path $resolvedStaging (Split-Path -Leaf $verificationPath)
    $stagedMetadata = Join-Path $resolvedStaging (Split-Path -Leaf $metadataPath)

    & $zipAlign -f -P 16 4 $resolvedUnsignedApk $stagedAligned
    if ($LASTEXITCODE -ne 0) {
        throw "zipalign failed with exit code $LASTEXITCODE."
    }

    Write-Output "apksigner will prompt for the keystore/key password; it is not accepted as a script argument."
    & $resolvedJava -jar $apkSignerJar sign `
        --debuggable-apk-permitted false `
        --ks $resolvedKeystore `
        --ks-key-alias $KeyAlias `
        --v4-signing-enabled false `
        --out $stagedSigned `
        $stagedAligned
    if ($LASTEXITCODE -ne 0) {
        throw "APK signing failed with exit code $LASTEXITCODE."
    }

    $actualSigner = Get-VerifiedSigner `
        -Apk $stagedSigned `
        -Java $resolvedJava `
        -ApkSignerJar $apkSignerJar
    if ($actualSigner.sha256 -ne $normalizedExpectedSigner) {
        throw (
            "Production signing certificate mismatch. Expected $normalizedExpectedSigner; " +
            "got $($actualSigner.sha256)."
        )
    }
    if ($actualSigner.sha256 -in $forbiddenDevelopmentSigners) {
        throw "The signed APK uses a forbidden development/debug certificate."
    }

    & $zipAlign -c -P 16 4 $stagedSigned
    if ($LASTEXITCODE -ne 0) {
        throw "The signed APK failed final zipalign verification."
    }

    $signedMetadata = Get-ApkMetadata -Apk $stagedSigned -Aapt2 $aapt2
    if (
        $signedMetadata.package -ne $unsignedMetadata.package -or
        $signedMetadata.versionName -ne $unsignedMetadata.versionName -or
        $signedMetadata.versionCode -ne $unsignedMetadata.versionCode -or
        $signedMetadata.minimumSdk -ne $unsignedMetadata.minimumSdk -or
        $signedMetadata.targetSdk -ne $unsignedMetadata.targetSdk -or
        $signedMetadata.debuggable -or
        $signedMetadata.profileable -or
        $signedMetadata.testOnly
    ) {
        throw "The signed APK does not preserve the reviewed package/version/SDK/release identity."
    }

    $outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedSigned).Hash.ToLowerInvariant()
    $outputFileName = Split-Path -Leaf $absoluteOutput
    Set-Content -LiteralPath $stagedChecksum -Encoding ascii -Value "$outputHash  $outputFileName"
    Set-Content -LiteralPath $stagedSigner -Encoding ascii -Value $actualSigner.sha256
    Set-Content `
        -LiteralPath $stagedVerification `
        -Encoding utf8 `
        -Value $actualSigner.verificationOutput
    $verificationFileName = Split-Path -Leaf $verificationPath
    $verificationHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $stagedVerification
    ).Hash.ToLowerInvariant()
    [pscustomobject]@{
        schemaVersion = 1
        sourceTag = $SourceTag
        sourceCommit = $headCommit
        unsignedEvidence = Split-Path -Leaf $resolvedEvidence
        unsignedEvidenceSha256 = $evidenceHash
        unsignedApkSha256 = $actualUnsignedHash
        package = $signedMetadata.package
        versionName = $signedMetadata.versionName
        versionCode = $signedMetadata.versionCode
        minimumSdk = $signedMetadata.minimumSdk
        targetSdk = $signedMetadata.targetSdk
        debuggable = $false
        profileable = $false
        testOnly = $false
        apk = $outputFileName
        apkSha256 = $outputHash
        signingCertificateSha256 = $actualSigner.sha256
        signingCertificateSubject = $actualSigner.subject
        signatureVerification = $verificationFileName
        signatureVerificationSha256 = $verificationHash
        previousProductionApkSha256 = $previousProductionHash
        previousProductionVersionName = $previousProductionVersionName
        previousProductionVersionCode = $previousProductionVersionCode
        toolchain = $evidence.toolchain
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $stagedMetadata -Encoding utf8

    $stagedFiles = @(
        $stagedSigned,
        $stagedChecksum,
        $stagedSigner,
        $stagedVerification,
        $stagedMetadata
    )
    for ($index = 0; $index -lt $stagedFiles.Count; $index++) {
        Move-Item -LiteralPath $stagedFiles[$index] -Destination $finalPaths[$index]
        $publishedPaths.Add($finalPaths[$index])
    }

    $releaseCompleted = $true
    Write-Output "Prepared signed production APK: $absoluteOutput"
    Write-Output "SHA-256: $outputHash"
    Write-Output "Signing certificate SHA-256: $($actualSigner.sha256)"
    Write-Output "Release metadata: $metadataPath"
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
