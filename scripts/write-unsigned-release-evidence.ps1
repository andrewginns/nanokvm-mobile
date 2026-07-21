[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceTag,

    [Parameter(Mandatory = $true)]
    [string]$JavaPath,

    [Parameter(Mandatory = $true)]
    [string]$BuildToolsPath,

    [string]$UnsignedApk,
    [string]$ReleaseBundle,
    [string]$Sbom,
    [string]$BuildLogPath,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$publishedEvidencePaths = [System.Collections.Generic.List[string]]::new()
$inProgressEvidence = $null
$inProgressBuildLog = $null

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

function Get-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Apk,

        [Parameter(Mandatory = $true)]
        [string]$Aapt2
    )

    $badgingResult = Invoke-NativeCapture `
        -FilePath $Aapt2 `
        -Arguments @("dump", "badging", $Apk)
    $badging = $badgingResult.Output
    if ($badgingResult.ExitCode -ne 0) {
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

    $manifestResult = Invoke-NativeCapture `
        -FilePath $Aapt2 `
        -Arguments @("dump", "xmltree", $Apk, "--file", "AndroidManifest.xml")
    $manifest = $manifestResult.Output
    if ($manifestResult.ExitCode -ne 0) {
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

function Get-ArtifactRecord {
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
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    $repositoryPrefix = $Repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Release evidence artifacts must remain under the tagged repository: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    return [pscustomobject]@{
        path = $resolved.Substring($repositoryPrefix.Length).Replace('\', '/')
        length = $item.Length
        lastWriteTimeUtc = $item.LastWriteTimeUtc.ToString("o")
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash.ToLowerInvariant()
    }
}

$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gitStatusResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "status", "--porcelain", "--untracked-files=all")
$gitStatus = $gitStatusResult.Output
if ($gitStatusResult.ExitCode -ne 0) {
    throw "Git could not inspect the release worktree."
}
if ($gitStatus) {
    throw "Unsigned release evidence requires a clean worktree at the exact source tag."
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
$tagType = $tagTypeResult.Output.Trim()
if ($tagTypeResult.ExitCode -ne 0 -or $tagType -ne "tag") {
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
    $tagCommit -ne $headCommit
) {
    throw "Source tag $SourceTag does not identify the checked-out commit $headCommit."
}

$javaCandidate = if ([IO.Path]::IsPathRooted($JavaPath)) {
    $JavaPath
} else {
    Join-Path $repository $JavaPath
}
$resolvedJava = (Resolve-Path -LiteralPath $javaCandidate).Path
if (-not (Test-Path -LiteralPath $resolvedJava -PathType Leaf)) {
    throw "The explicit JDK 21 java executable does not exist: $resolvedJava"
}
$javaResult = Invoke-NativeCapture -FilePath $resolvedJava -Arguments @("-version")
$javaVersion = $javaResult.Output
if ($javaResult.ExitCode -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw "The unsigned evidence toolchain must use JDK 21. Found: $javaVersion"
}

$buildToolsCandidate = if ([IO.Path]::IsPathRooted($BuildToolsPath)) {
    $BuildToolsPath
} else {
    Join-Path $repository $BuildToolsPath
}
$resolvedBuildTools = (Resolve-Path -LiteralPath $buildToolsCandidate).Path
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

if (-not $OutputPath) {
    $OutputPath = Join-Path $repository (
        "dist\NanoKVM-Mobile-$sourceVersionName-v$sourceVersionCode-unsigned-evidence.json"
    )
}
if ([IO.Path]::IsPathRooted($OutputPath)) {
    $absoluteOutput = [IO.Path]::GetFullPath($OutputPath)
} else {
    $absoluteOutput = [IO.Path]::GetFullPath((Join-Path $repository $OutputPath))
}
if (-not $BuildLogPath) {
    $buildTimestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
    $BuildLogPath = Join-Path $repository (
        "dist\NanoKVM-Mobile-$sourceVersionName-v$sourceVersionCode-strict-build-$buildTimestamp.log"
    )
}
if ([IO.Path]::IsPathRooted($BuildLogPath)) {
    $absoluteBuildLog = [IO.Path]::GetFullPath($BuildLogPath)
} else {
    $absoluteBuildLog = [IO.Path]::GetFullPath((Join-Path $repository $BuildLogPath))
}
if ($absoluteOutput -eq $absoluteBuildLog) {
    throw "The unsigned evidence manifest and strict build log require different paths."
}
foreach ($newEvidencePath in @($absoluteOutput, $absoluteBuildLog)) {
    if (Test-Path -LiteralPath $newEvidencePath) {
        throw "Refusing to overwrite existing release evidence: $newEvidencePath"
    }
    $newEvidenceDirectory = Split-Path -Parent $newEvidencePath
    if (-not (Test-Path -LiteralPath $newEvidenceDirectory)) {
        New-Item -ItemType Directory -Path $newEvidenceDirectory | Out-Null
    }
}
$evidenceOutputDirectory = (Resolve-Path -LiteralPath (Split-Path -Parent $absoluteOutput)).Path
$buildLogDirectory = (Resolve-Path -LiteralPath (Split-Path -Parent $absoluteBuildLog)).Path
$stagingId = [guid]::NewGuid().ToString("N")
$inProgressEvidence = Join-Path $evidenceOutputDirectory ".nanokvm-evidence-$stagingId.json"
$inProgressBuildLog = Join-Path $buildLogDirectory ".nanokvm-build-$stagingId.log"
trap {
    $failure = $_
    foreach ($publishedPath in $publishedEvidencePaths) {
        if (Test-Path -LiteralPath $publishedPath -PathType Leaf) {
            Remove-Item -LiteralPath $publishedPath -Force
        }
    }
    foreach ($temporaryPath in @($inProgressEvidence, $inProgressBuildLog)) {
        if (
            -not [string]::IsNullOrWhiteSpace($temporaryPath) -and
            (Test-Path -LiteralPath $temporaryPath -PathType Leaf)
        ) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
    throw $failure
}

$strictBuildArguments = @(
    "--no-problems-report",
    "--no-daemon",
    "--no-parallel",
    "--no-configuration-cache",
    "--refresh-dependencies",
    "--dependency-verification=strict",
    "clean",
    "test",
    "lintRelease",
    "assembleRelease",
    "bundleRelease",
    "assembleBenchmark",
    ":app:verifyReleaseProfiles",
    ":macrobenchmark:assembleBenchmark",
    ":app:reproducibleSbom"
)
$gradleWrapper = Join-Path $repository "gradlew.bat"
$jdkHome = Split-Path -Parent (Split-Path -Parent $resolvedJava)
$previousJavaHome = $env:JAVA_HOME
$strictBuildExitCode = 1
Push-Location $repository
try {
    $env:JAVA_HOME = $jdkHome
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $gradleWrapper @strictBuildArguments 2>&1 |
            Tee-Object -FilePath $inProgressBuildLog
        $strictBuildExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
} finally {
    Pop-Location
    if ($null -eq $previousJavaHome) {
        Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_HOME = $previousJavaHome
    }
}
if ($strictBuildExitCode -ne 0) {
    $failedBuildLog = Join-Path $buildLogDirectory (
        [IO.Path]::GetFileNameWithoutExtension($absoluteBuildLog) + "-failed.log"
    )
    if (Test-Path -LiteralPath $failedBuildLog) {
        $failedBuildLog = Join-Path $buildLogDirectory (
            [IO.Path]::GetFileNameWithoutExtension($absoluteBuildLog) +
            "-failed-$stagingId.log"
        )
    }
    Move-Item -LiteralPath $inProgressBuildLog -Destination $failedBuildLog
    throw (
        "The clean strict release build failed with exit code $strictBuildExitCode. " +
        "Retained failed log: $failedBuildLog"
    )
}
Move-Item -LiteralPath $inProgressBuildLog -Destination $absoluteBuildLog
$publishedEvidencePaths.Add($absoluteBuildLog)

$postBuildGitResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @("-C", $repository, "status", "--porcelain", "--untracked-files=all")
$postBuildGitStatus = $postBuildGitResult.Output
if ($postBuildGitResult.ExitCode -ne 0 -or $postBuildGitStatus) {
    throw "The strict build changed the tagged source worktree; release evidence was not created."
}

if (-not $UnsignedApk) {
    $UnsignedApk = Join-Path $repository "app\build\outputs\apk\release\app-release-unsigned.apk"
}
if (-not $ReleaseBundle) {
    $ReleaseBundle = Join-Path $repository "app\build\outputs\bundle\release\app-release.aab"
}
if (-not $Sbom) {
    $Sbom = Join-Path $repository "app\build\reports\cyclonedx\nanokvm-mobile.cdx.json"
}
$unsignedApkRecord = Get-ArtifactRecord -Path $UnsignedApk -Repository $repository
$releaseBundleRecord = Get-ArtifactRecord -Path $ReleaseBundle -Repository $repository
$sbomRecord = Get-ArtifactRecord -Path $Sbom -Repository $repository
$strictBuildLogRecord = Get-ArtifactRecord -Path $absoluteBuildLog -Repository $repository

$resolvedUnsignedApk = (Resolve-Path -LiteralPath (Join-Path $repository $unsignedApkRecord.path)).Path
$apkMetadata = Get-ApkMetadata -Apk $resolvedUnsignedApk -Aapt2 $aapt2
if (
    $apkMetadata.package -ne "org.nanokvm.mobile" -or
    $apkMetadata.versionName -ne $sourceVersionName -or
    $apkMetadata.versionCode -ne $sourceVersionCode -or
    $apkMetadata.minimumSdk -ne 26 -or
    $apkMetadata.targetSdk -ne 37
) {
    throw "The unsigned APK package/version/SDK identity does not match the tagged source."
}
if ($apkMetadata.debuggable -or $apkMetadata.profileable -or $apkMetadata.testOnly) {
    throw "The unsigned production candidate must not be debuggable, profileable, or test-only."
}

$unsignedVerificationResult = Invoke-NativeCapture `
    -FilePath $resolvedJava `
    -Arguments @("-jar", $apkSignerJar, "verify", $resolvedUnsignedApk)
$unsignedVerification = $unsignedVerificationResult.Output
if ($unsignedVerificationResult.ExitCode -eq 0) {
    throw "The unsigned evidence input is already signed."
}
if ($unsignedVerification -notmatch 'DOES NOT VERIFY|Missing META-INF') {
    throw "The unsigned APK failed signature inspection for an unexpected reason."
}

$testFiles = Get-ChildItem -LiteralPath $repository -Recurse -File -Filter "TEST-*.xml" |
    Where-Object { $_.FullName -match '\\build\\test-results\\testDebugUnitTest\\' }
$testTotals = [ordered]@{ suites = $testFiles.Count; tests = 0; failures = 0; errors = 0; skipped = 0 }
$testReportRecords = @()
foreach ($testFile in $testFiles) {
    [xml]$testDocument = Get-Content -LiteralPath $testFile.FullName
    $suite = $testDocument.testsuite
    $testTotals.tests += [int]$suite.tests
    $testTotals.failures += [int]$suite.failures
    $testTotals.errors += [int]$suite.errors
    $testTotals.skipped += [int]$suite.skipped
    $testReportRecords += Get-ArtifactRecord `
        -Path $testFile.FullName `
        -Repository $repository
}
if ($testTotals.tests -le 0 -or $testTotals.failures -ne 0 -or $testTotals.errors -ne 0) {
    throw "The retained JVM test results are missing or contain failures."
}
$testTotals["reports"] = $testReportRecords

$lintFiles = Get-ChildItem -LiteralPath $repository -Recurse -File -Filter "lint-results-release.xml"
$lintResults = foreach ($lintFile in $lintFiles) {
    [xml]$lintDocument = Get-Content -LiteralPath $lintFile.FullName
    $lintRecord = Get-ArtifactRecord -Path $lintFile.FullName -Repository $repository
    [pscustomobject]@{
        path = $lintRecord.path
        length = $lintRecord.length
        lastWriteTimeUtc = $lintRecord.lastWriteTimeUtc
        sha256 = $lintRecord.sha256
        issues = $lintDocument.SelectNodes("/issues/issue").Count
    }
}
if ($lintResults.Count -lt 3 -or ($lintResults | Where-Object { $_.issues -ne 0 })) {
    throw "The retained release lint reports are missing or contain issues."
}

$mappingRecord = Get-ArtifactRecord -Repository $repository -Path (
    Join-Path $repository "app\build\outputs\mapping\release\mapping.txt"
)
$baselineProfileRecord = Get-ArtifactRecord -Repository $repository -Path (
    Join-Path $repository "app\src\main\generated\baselineProfiles\baseline-prof.txt"
)
$startupProfileRecord = Get-ArtifactRecord -Repository $repository -Path (
    Join-Path $repository "app\src\main\generated\baselineProfiles\startup-prof.txt"
)
foreach ($record in @(
    $unsignedApkRecord,
    $releaseBundleRecord,
    $sbomRecord,
    $strictBuildLogRecord,
    $mappingRecord,
    $baselineProfileRecord,
    $startupProfileRecord
)) {
    if ($record.length -le 0) {
        throw "A required release evidence artifact is empty: $($record.path)"
    }
}

$wrapperProperties = Get-Content -Raw (
    Join-Path $repository "gradle\wrapper\gradle-wrapper.properties"
)
$gradleVersionMatch = [regex]::Match($wrapperProperties, 'gradle-([0-9]+\.[0-9]+\.[0-9]+)-bin\.zip')
$gradleDistributionHashMatch = [regex]::Match(
    $wrapperProperties,
    '(?m)^distributionSha256Sum=([0-9a-f]{64})$'
)
if (-not $gradleVersionMatch.Success -or -not $gradleDistributionHashMatch.Success) {
    throw "The Gradle wrapper version or distribution checksum is not pinned."
}

[pscustomobject]@{
    schemaVersion = 1
    createdUtc = [DateTime]::UtcNow.ToString("o")
    sourceTag = $SourceTag
    sourceCommit = $headCommit
    package = $apkMetadata.package
    versionName = $apkMetadata.versionName
    versionCode = $apkMetadata.versionCode
    minimumSdk = $apkMetadata.minimumSdk
    targetSdk = $apkMetadata.targetSdk
    debuggable = $apkMetadata.debuggable
    profileable = $apkMetadata.profileable
    testOnly = $apkMetadata.testOnly
    strictBuildCommand = ".\gradlew.bat " + ($strictBuildArguments -join " ")
    strictBuildLog = $strictBuildLogRecord
    tests = [pscustomobject]$testTotals
    lint = $lintResults
    unsignedApk = $unsignedApkRecord
    releaseBundle = $releaseBundleRecord
    sbom = $sbomRecord
    mapping = $mappingRecord
    baselineProfile = $baselineProfileRecord
    startupProfile = $startupProfileRecord
    toolchain = [pscustomobject]@{
        javaVersion = $javaVersion
        javaSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedJava).Hash.ToLowerInvariant()
        gradleVersion = $gradleVersionMatch.Groups[1].Value
        gradleDistributionSha256 = $gradleDistributionHashMatch.Groups[1].Value
        gradleWrapperJarSha256 = (
            Get-FileHash -Algorithm SHA256 -LiteralPath (
                Join-Path $repository "gradle\wrapper\gradle-wrapper.jar"
            )
        ).Hash.ToLowerInvariant()
        buildToolsVersion = $buildToolsVersion
        aapt2Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $aapt2).Hash.ToLowerInvariant()
        zipalignSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipAlign).Hash.ToLowerInvariant()
        apksignerJarSha256 = (
            Get-FileHash -Algorithm SHA256 -LiteralPath $apkSignerJar
        ).Hash.ToLowerInvariant()
    }
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $inProgressEvidence -Encoding utf8

Move-Item -LiteralPath $inProgressEvidence -Destination $absoluteOutput
$publishedEvidencePaths.Add($absoluteOutput)

$evidenceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absoluteOutput).Hash.ToLowerInvariant()
Write-Output "Unsigned release evidence: $absoluteOutput"
Write-Output "Evidence SHA-256: $evidenceHash"
Write-Output "Unsigned APK SHA-256: $($unsignedApkRecord.sha256)"
