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
$inProgressArtifactDirectory = $null

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

function Copy-RetainedArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Source,

        [Parameter(Mandatory = $true)]
        [string]$RelativeDestination,

        [Parameter(Mandatory = $true)]
        [string]$StagingDirectory,

        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $sourceRecord = Get-ArtifactRecord -Path $Source -Repository $Repository
    $resolvedSource = (Resolve-Path -LiteralPath (
        Join-Path $Repository $sourceRecord.path.Replace('/', [IO.Path]::DirectorySeparatorChar)
    )).Path
    $stagingPrefix = [IO.Path]::GetFullPath($StagingDirectory).TrimEnd('\', '/') +
        [IO.Path]::DirectorySeparatorChar
    $destination = [IO.Path]::GetFullPath((Join-Path $StagingDirectory $RelativeDestination))
    if (-not $destination.StartsWith($stagingPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "A retained artifact destination escapes its staging directory: $RelativeDestination"
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to overwrite a staged release evidence artifact: $destination"
    }
    $destinationDirectory = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $destinationDirectory)) {
        New-Item -ItemType Directory -Path $destinationDirectory | Out-Null
    }
    Copy-Item -LiteralPath $resolvedSource -Destination $destination
    $destinationItem = Get-Item -LiteralPath $destination
    $destinationHash = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $destination
    ).Hash.ToLowerInvariant()
    if ($destinationItem.Length -ne $sourceRecord.length -or $destinationHash -ne $sourceRecord.sha256) {
        throw "A retained release evidence copy does not match its build output: $RelativeDestination"
    }
}

function Remove-EvidencePath {
    param(
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        return
    }
    if (Test-Path -LiteralPath $Path -PathType Container) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    } else {
        Remove-Item -LiteralPath $Path -Force
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
$evidenceOutputDirectoryCandidate = Split-Path -Parent $absoluteOutput
$artifactDirectoryName = [IO.Path]::GetFileNameWithoutExtension($absoluteOutput) + "-artifacts"
$absoluteArtifactDirectory = [IO.Path]::GetFullPath((
    Join-Path $evidenceOutputDirectoryCandidate $artifactDirectoryName
))
foreach ($newEvidencePath in @($absoluteOutput, $absoluteBuildLog)) {
    if (Test-Path -LiteralPath $newEvidencePath) {
        throw "Refusing to overwrite existing release evidence: $newEvidencePath"
    }
    $newEvidenceDirectory = Split-Path -Parent $newEvidencePath
    if (-not (Test-Path -LiteralPath $newEvidenceDirectory)) {
        New-Item -ItemType Directory -Path $newEvidenceDirectory | Out-Null
    }
}
if (Test-Path -LiteralPath $absoluteArtifactDirectory) {
    throw "Refusing to overwrite existing retained release evidence: $absoluteArtifactDirectory"
}
$evidenceOutputDirectory = (Resolve-Path -LiteralPath (Split-Path -Parent $absoluteOutput)).Path
$buildLogDirectory = (Resolve-Path -LiteralPath (Split-Path -Parent $absoluteBuildLog)).Path
$repositoryPrefix = $repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
foreach ($retainedPath in @($absoluteOutput, $absoluteBuildLog, $absoluteArtifactDirectory)) {
    if (-not $retainedPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Release evidence must remain under the tagged repository: $retainedPath"
    }
}
$stagingId = [guid]::NewGuid().ToString("N")
$inProgressEvidence = Join-Path $evidenceOutputDirectory ".nanokvm-evidence-$stagingId.json"
$inProgressBuildLog = Join-Path $buildLogDirectory ".nanokvm-build-$stagingId.log"
$inProgressArtifactDirectory = Join-Path $evidenceOutputDirectory ".nanokvm-artifacts-$stagingId"
trap {
    $failure = $_
    foreach ($publishedPath in $publishedEvidencePaths) {
        Remove-EvidencePath -Path $publishedPath
    }
    foreach ($temporaryPath in @(
        $inProgressEvidence,
        $inProgressBuildLog,
        $inProgressArtifactDirectory
    )) {
        Remove-EvidencePath -Path $temporaryPath
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
    ":app:verifyReproducibleSbomMetadata"
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

$dependencyGraphArguments = @(
    "--no-problems-report",
    "--no-daemon",
    "--no-parallel",
    "--no-configuration-cache",
    "--refresh-dependencies",
    "--dependency-verification=strict",
    "--console=plain",
    ":app:dependencies",
    "--configuration",
    "releaseRuntimeClasspath"
)
New-Item -ItemType Directory -Path $inProgressArtifactDirectory | Out-Null
$inProgressDependencyDirectory = Join-Path $inProgressArtifactDirectory "dependencies"
New-Item -ItemType Directory -Path $inProgressDependencyDirectory | Out-Null
$inProgressDependencyGraph = Join-Path (
    $inProgressDependencyDirectory
) "releaseRuntimeClasspath.txt"
$dependencyGraphExitCode = 1
Push-Location $repository
try {
    $env:JAVA_HOME = $jdkHome
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $gradleWrapper @dependencyGraphArguments 2>&1 |
            Tee-Object -FilePath $inProgressDependencyGraph
        $dependencyGraphExitCode = $LASTEXITCODE
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
if ($dependencyGraphExitCode -ne 0) {
    throw "The strict release dependency graph failed with exit code $dependencyGraphExitCode."
}
if ((Get-Item -LiteralPath $inProgressDependencyGraph).Length -le 0) {
    throw "The retained release dependency graph is empty."
}
$dependencyGraphText = Get-Content -Raw -LiteralPath $inProgressDependencyGraph
if (
    $dependencyGraphText -notmatch '(?m)^releaseRuntimeClasspath\s+-\s+' -or
    $dependencyGraphText -match '(?m)\bFAILED\b'
) {
    throw "The retained release dependency graph is incomplete or unresolved."
}

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

$benchmarkApk = Join-Path $repository "app\build\outputs\apk\benchmark\app-benchmark.apk"
$mergedReleaseManifest = Join-Path $repository (
    "app\build\intermediates\merged_manifests\release\processReleaseManifest\AndroidManifest.xml"
)
$networkSecurityConfig = Join-Path $repository (
    "app\build\intermediates\packaged_res\release\packageReleaseResources\xml\network_security_config.xml"
)
$r8Outputs = [ordered]@{
    mapping = Join-Path $repository "app\build\outputs\mapping\release\mapping.txt"
    seeds = Join-Path $repository "app\build\outputs\mapping\release\seeds.txt"
    usage = Join-Path $repository "app\build\outputs\mapping\release\usage.txt"
    configuration = Join-Path $repository "app\build\outputs\mapping\release\configuration.txt"
    resources = Join-Path $repository "app\build\outputs\mapping\release\resources.txt"
}

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

$testFiles = @()
foreach ($module in @("app", "protocol", "video", "macrobenchmark")) {
    $testResultDirectory = Join-Path $repository "$module\build\test-results\testDebugUnitTest"
    if (Test-Path -LiteralPath $testResultDirectory -PathType Container) {
        $testFiles += Get-ChildItem -LiteralPath $testResultDirectory -File -Filter "TEST-*.xml"
    }
}
$testTotals = [ordered]@{ suites = $testFiles.Count; tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($testFile in $testFiles) {
    [xml]$testDocument = Get-Content -LiteralPath $testFile.FullName
    $suite = $testDocument.testsuite
    $testTotals.tests += [int]$suite.tests
    $testTotals.failures += [int]$suite.failures
    $testTotals.errors += [int]$suite.errors
    $testTotals.skipped += [int]$suite.skipped
}
if ($testTotals.tests -le 0 -or $testTotals.failures -ne 0 -or $testTotals.errors -ne 0) {
    throw "The retained JVM test results are missing or contain failures."
}

$lintFiles = @()
foreach ($module in @("app", "protocol", "video", "macrobenchmark")) {
    $lintFile = Join-Path $repository "$module\build\reports\lint-results-release.xml"
    if (Test-Path -LiteralPath $lintFile -PathType Leaf) {
        $lintFiles += Get-Item -LiteralPath $lintFile
    }
}
$lintSourceResults = foreach ($lintFile in $lintFiles) {
    [xml]$lintDocument = Get-Content -LiteralPath $lintFile.FullName
    [pscustomobject]@{
        source = $lintFile.FullName
        issues = $lintDocument.SelectNodes("/issues/issue").Count
    }
}
if ($lintSourceResults.Count -lt 3 -or ($lintSourceResults | Where-Object { $_.issues -ne 0 })) {
    throw "The retained release lint reports are missing or contain issues."
}

foreach ($requiredArtifact in @(
    $unsignedApkRecord,
    $releaseBundleRecord,
    $sbomRecord,
    $strictBuildLogRecord,
    (Get-ArtifactRecord -Repository $repository -Path $benchmarkApk),
    (Get-ArtifactRecord -Repository $repository -Path $mergedReleaseManifest),
    (Get-ArtifactRecord -Repository $repository -Path $networkSecurityConfig),
    (Get-ArtifactRecord -Repository $repository -Path $r8Outputs.mapping),
    (Get-ArtifactRecord -Repository $repository -Path $r8Outputs.seeds),
    (Get-ArtifactRecord -Repository $repository -Path $r8Outputs.usage),
    (Get-ArtifactRecord -Repository $repository -Path $r8Outputs.configuration),
    (Get-ArtifactRecord -Repository $repository -Path $r8Outputs.resources),
    (Get-ArtifactRecord -Repository $repository -Path (
        Join-Path $repository "app\src\main\generated\baselineProfiles\baseline-prof.txt"
    )),
    (Get-ArtifactRecord -Repository $repository -Path (
        Join-Path $repository "app\src\main\generated\baselineProfiles\startup-prof.txt"
    ))
)) {
    if ($requiredArtifact.length -le 0) {
        throw "A required release evidence artifact is empty: $($requiredArtifact.path)"
    }
}

$sourceArchiveRelative = "source\NanoKVM-Mobile-$sourceVersionName-source.zip"
$sourceArchiveStaging = Join-Path $inProgressArtifactDirectory $sourceArchiveRelative
$sourceArchiveDirectory = Split-Path -Parent $sourceArchiveStaging
New-Item -ItemType Directory -Path $sourceArchiveDirectory | Out-Null
$sourceArchiveResult = Invoke-NativeCapture `
    -FilePath "git.exe" `
    -Arguments @(
        "-C",
        $repository,
        "archive",
        "--format=zip",
        "--prefix=NanoKVM-Mobile-$sourceVersionName/",
        "--output=$sourceArchiveStaging",
        $tagReference
    )
if ($sourceArchiveResult.ExitCode -ne 0) {
    throw "Git could not create the exact tagged source archive: $($sourceArchiveResult.Output)"
}
if ((Get-Item -LiteralPath $sourceArchiveStaging).Length -le 0) {
    throw "The exact tagged source archive is empty."
}

$retainedSources = [ordered]@{
    unsignedApk = [pscustomobject]@{ source = $UnsignedApk; destination = "artifacts\app-release-unsigned.apk" }
    releaseBundle = [pscustomobject]@{ source = $ReleaseBundle; destination = "artifacts\app-release.aab" }
    benchmarkApk = [pscustomobject]@{ source = $benchmarkApk; destination = "artifacts\app-benchmark.apk" }
    sbom = [pscustomobject]@{ source = $Sbom; destination = "reports\nanokvm-mobile.cdx.json" }
    mergedReleaseManifest = [pscustomobject]@{ source = $mergedReleaseManifest; destination = "manifest\AndroidManifest.xml" }
    networkSecurityConfig = [pscustomobject]@{ source = $networkSecurityConfig; destination = "manifest\network_security_config.xml" }
    mapping = [pscustomobject]@{ source = $r8Outputs.mapping; destination = "r8\mapping.txt" }
    seeds = [pscustomobject]@{ source = $r8Outputs.seeds; destination = "r8\seeds.txt" }
    usage = [pscustomobject]@{ source = $r8Outputs.usage; destination = "r8\usage.txt" }
    configuration = [pscustomobject]@{ source = $r8Outputs.configuration; destination = "r8\configuration.txt" }
    resources = [pscustomobject]@{ source = $r8Outputs.resources; destination = "r8\resources.txt" }
    baselineProfile = [pscustomobject]@{
        source = Join-Path $repository "app\src\main\generated\baselineProfiles\baseline-prof.txt"
        destination = "profiles\baseline-prof.txt"
    }
    startupProfile = [pscustomobject]@{
        source = Join-Path $repository "app\src\main\generated\baselineProfiles\startup-prof.txt"
        destination = "profiles\startup-prof.txt"
    }
}
foreach ($retainedSource in $retainedSources.GetEnumerator()) {
    Copy-RetainedArtifact `
        -Source $retainedSource.Value.source `
        -RelativeDestination $retainedSource.Value.destination `
        -StagingDirectory $inProgressArtifactDirectory `
        -Repository $repository
}

foreach ($testFile in $testFiles) {
    $testRecord = Get-ArtifactRecord -Path $testFile.FullName -Repository $repository
    Copy-RetainedArtifact `
        -Source $testFile.FullName `
        -RelativeDestination (Join-Path "tests" $testRecord.path.Replace('/', '\')) `
        -StagingDirectory $inProgressArtifactDirectory `
        -Repository $repository
}
foreach ($lintSourceResult in $lintSourceResults) {
    $lintSourceRecord = Get-ArtifactRecord -Path $lintSourceResult.source -Repository $repository
    Copy-RetainedArtifact `
        -Source $lintSourceResult.source `
        -RelativeDestination (Join-Path "lint" $lintSourceRecord.path.Replace('/', '\')) `
        -StagingDirectory $inProgressArtifactDirectory `
        -Repository $repository
}

[IO.Directory]::Move($inProgressArtifactDirectory, $absoluteArtifactDirectory)
$publishedEvidencePaths.Add($absoluteArtifactDirectory)

$retainedRecords = @{}
foreach ($retainedSource in $retainedSources.GetEnumerator()) {
    $retainedRecords[$retainedSource.Key] = Get-ArtifactRecord `
        -Path (Join-Path $absoluteArtifactDirectory $retainedSource.Value.destination) `
        -Repository $repository
}
$sourceArchiveRecord = Get-ArtifactRecord `
    -Path (Join-Path $absoluteArtifactDirectory $sourceArchiveRelative) `
    -Repository $repository
$dependencyGraphRecord = Get-ArtifactRecord `
    -Path (Join-Path $absoluteArtifactDirectory "dependencies\releaseRuntimeClasspath.txt") `
    -Repository $repository

$testReportRecords = @()
foreach ($testFile in $testFiles) {
    $testSourceRecord = Get-ArtifactRecord -Path $testFile.FullName -Repository $repository
    $testReportRecords += Get-ArtifactRecord `
        -Path (Join-Path $absoluteArtifactDirectory (
            Join-Path "tests" $testSourceRecord.path.Replace('/', '\')
        )) `
        -Repository $repository
}
$testTotals["reports"] = $testReportRecords

$lintResults = @()
foreach ($lintSourceResult in $lintSourceResults) {
    $lintSourceRecord = Get-ArtifactRecord -Path $lintSourceResult.source -Repository $repository
    $lintRecord = Get-ArtifactRecord `
        -Path (Join-Path $absoluteArtifactDirectory (
            Join-Path "lint" $lintSourceRecord.path.Replace('/', '\')
        )) `
        -Repository $repository
    $lintResults += [pscustomobject]@{
        path = $lintRecord.path
        length = $lintRecord.length
        lastWriteTimeUtc = $lintRecord.lastWriteTimeUtc
        sha256 = $lintRecord.sha256
        issues = $lintSourceResult.issues
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
    schemaVersion = 2
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
    releaseDependencyGraphCommand = ".\gradlew.bat " + ($dependencyGraphArguments -join " ")
    strictBuildLog = $strictBuildLogRecord
    tests = [pscustomobject]$testTotals
    lint = $lintResults
    sourceArchive = $sourceArchiveRecord
    unsignedApk = $retainedRecords.unsignedApk
    releaseBundle = $retainedRecords.releaseBundle
    benchmarkApk = $retainedRecords.benchmarkApk
    sbom = $retainedRecords.sbom
    mergedReleaseManifest = $retainedRecords.mergedReleaseManifest
    networkSecurityConfig = $retainedRecords.networkSecurityConfig
    releaseDependencyGraph = $dependencyGraphRecord
    mapping = $retainedRecords.mapping
    seeds = $retainedRecords.seeds
    usage = $retainedRecords.usage
    configuration = $retainedRecords.configuration
    resources = $retainedRecords.resources
    baselineProfile = $retainedRecords.baselineProfile
    startupProfile = $retainedRecords.startupProfile
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
Write-Output "Retained evidence artifacts: $absoluteArtifactDirectory"
Write-Output "Source archive SHA-256: $($sourceArchiveRecord.sha256)"
Write-Output "Unsigned APK SHA-256: $($retainedRecords.unsignedApk.sha256)"
