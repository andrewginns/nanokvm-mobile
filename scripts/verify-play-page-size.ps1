[CmdletBinding(DefaultParameterSetName = "Evidence")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Evidence")]
    [string]$EvidenceManifest,

    [Parameter(Mandatory = $true, ParameterSetName = "Evidence")]
    [string]$ExpectedEvidenceSha256,

    [Parameter(Mandatory = $true, ParameterSetName = "Preflight")]
    [string]$PlayBundle,

    [Parameter(Mandatory = $true, ParameterSetName = "Preflight")]
    [string]$ExpectedPlayBundleSha256,

    [Parameter(Mandatory = $true, ParameterSetName = "Preflight")]
    [string]$ExpectedJavaSha256,

    [Parameter(Mandatory = $true, ParameterSetName = "Preflight")]
    [string]$BuildToolsVersion,

    [Parameter(Mandatory = $true, ParameterSetName = "Preflight")]
    [string]$ExpectedZipalignSha256,

    [Parameter(Mandatory = $true)]
    [string]$BundletoolJar,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedBundletoolSha256,

    [Parameter(Mandatory = $true)]
    [string]$JdkPath,

    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkPath,

    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-NormalizedSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $normalized = $Value.Replace(":", "").Trim().ToLowerInvariant()
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw "$Name must be a complete SHA-256 digest."
    }
    return $normalized
}

function Get-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-BytesSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes
    )

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha256.ComputeHash($Bytes) | ForEach-Object {
            $_.ToString("x2")
        }) -join '')
    }
    finally {
        $sha256.Dispose()
    }
}

function Get-StringSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    return Get-BytesSha256 -Bytes ([Text.Encoding]::UTF8.GetBytes($Value))
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell wraps native stderr as non-terminating ErrorRecord
        # objects. Capture those records without allowing a successful tool that
        # writes diagnostics to stderr (notably keytool) to abort the audit.
        $ErrorActionPreference = "Continue"
        $captured = @(& $FilePath @Arguments 2>&1 | ForEach-Object {
            $_.ToString()
        })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output = $captured -join [Environment]::NewLine
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode.`n$output"
    }
    return [pscustomobject]@{
        exitCode = $exitCode
        lines = $captured
        output = $output
    }
}

function Resolve-ExistingLeaf {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Name does not exist: $resolved"
    }
    return $resolved
}

function Assert-NoReparsePoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$StopAt
    )

    $stop = [IO.Path]::GetFullPath($StopAt).TrimEnd('\')
    $current = [IO.Path]::GetFullPath($Path)
    while ($true) {
        $item = Get-Item -Force -LiteralPath $current
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Release evidence must not traverse a reparse point: $current"
        }
        if ($current.TrimEnd('\').Equals($stop, [StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $parent = Split-Path -Parent $current
        if (-not $parent -or $parent -eq $current) {
            throw "Path escaped its expected root while checking reparse points: $Path"
        }
        $current = $parent
    }
}

function Resolve-EvidenceArtifact {
    param(
        [Parameter(Mandatory = $true)]
        $Record,

        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $relativePath = [string]$Record.path
    if (
        -not $relativePath -or
        [IO.Path]::IsPathRooted($relativePath) -or
        $relativePath -match '(^|[\\/])\.\.([\\/]|$)'
    ) {
        throw "The playBundle evidence path is missing, rooted, or contains traversal."
    }

    $resolved = [IO.Path]::GetFullPath((Join-Path $Repository $relativePath))
    $repositoryPrefix = $Repository.TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The playBundle evidence path escapes the repository."
    }
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "The retained playBundle does not exist: $resolved"
    }
    Assert-NoReparsePoint -Path $resolved -StopAt $Repository

    $item = Get-Item -LiteralPath $resolved
    if ([long]$Record.length -ne [long]$item.Length) {
        throw "The retained playBundle byte length differs from its evidence record."
    }
    $expectedHash = Get-NormalizedSha256 -Value ([string]$Record.sha256) -Name "playBundle.sha256"
    $actualHash = Get-Sha256 -Path $resolved
    if ($actualHash -ne $expectedHash) {
        throw "The retained playBundle bytes differ from the reviewed evidence record."
    }
    return $resolved
}

function Get-UInt16LittleEndian {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes,

        [Parameter(Mandatory = $true)]
        [int]$Offset
    )

    if ($Offset -lt 0 -or $Offset + 2 -gt $Bytes.Length) {
        throw "A 16-bit ELF field falls outside the file."
    }
    return [uint16]([uint16]$Bytes[$Offset] -bor ([uint16]$Bytes[$Offset + 1] -shl 8))
}

function Get-UInt32LittleEndian {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes,

        [Parameter(Mandatory = $true)]
        [int]$Offset
    )

    if ($Offset -lt 0 -or $Offset + 4 -gt $Bytes.Length) {
        throw "A 32-bit ELF field falls outside the file."
    }
    $value = [uint32]0
    for ($index = 0; $index -lt 4; $index++) {
        $value = $value -bor ([uint32]$Bytes[$Offset + $index] -shl (8 * $index))
    }
    return $value
}

function Get-UInt64LittleEndian {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes,

        [Parameter(Mandatory = $true)]
        [int]$Offset
    )

    if ($Offset -lt 0 -or $Offset + 8 -gt $Bytes.Length) {
        throw "A 64-bit ELF field falls outside the file."
    }
    $value = [uint64]0
    for ($index = 0; $index -lt 8; $index++) {
        $value = $value -bor ([uint64]$Bytes[$Offset + $index] -shl (8 * $index))
    }
    return $value
}

function Read-ZipEntryBytes {
    param(
        [Parameter(Mandatory = $true)]
        [IO.Compression.ZipArchiveEntry]$Entry
    )

    if ($Entry.Length -gt [int]::MaxValue) {
        throw "Native library is too large for the bounded ELF parser: $($Entry.FullName)"
    }
    $entryStream = $Entry.Open()
    $memory = New-Object IO.MemoryStream
    try {
        $entryStream.CopyTo($memory)
        return $memory.ToArray()
    }
    finally {
        $memory.Dispose()
        $entryStream.Dispose()
    }
}

function Get-ElfLoadAlignmentRecord {
    param(
        [Parameter(Mandatory = $true)]
        [byte[]]$Bytes,

        [Parameter(Mandatory = $true)]
        [string]$EntryPath,

        [Parameter(Mandatory = $true)]
        [string]$Abi
    )

    if ($Bytes.Length -lt 64) {
        throw "Packaged native library is too short to be ELF64: $EntryPath"
    }
    if (
        $Bytes[0] -ne 0x7f -or
        $Bytes[1] -ne 0x45 -or
        $Bytes[2] -ne 0x4c -or
        $Bytes[3] -ne 0x46
    ) {
        throw "Packaged native library has no ELF magic: $EntryPath"
    }
    if ($Bytes[4] -ne 2 -or $Bytes[5] -ne 1 -or $Bytes[6] -ne 1) {
        throw "Only little-endian ELF64 version 1 is supported for Play page-size evidence: $EntryPath"
    }

    $machine = Get-UInt16LittleEndian -Bytes $Bytes -Offset 18
    $expectedMachine = if ($Abi -eq "arm64-v8a") { 183 } else { 62 }
    if ($machine -ne $expectedMachine) {
        throw "ELF machine $machine does not match ABI $Abi in $EntryPath."
    }

    $programHeaderOffset = Get-UInt64LittleEndian -Bytes $Bytes -Offset 32
    $programHeaderSize = Get-UInt16LittleEndian -Bytes $Bytes -Offset 54
    $programHeaderCount = Get-UInt16LittleEndian -Bytes $Bytes -Offset 56
    if (
        $programHeaderOffset -gt [int]::MaxValue -or
        $programHeaderSize -lt 56 -or
        $programHeaderCount -le 0
    ) {
        throw "ELF program-header metadata is unsupported or empty: $EntryPath"
    }
    $programTableEnd = $programHeaderOffset + ([uint64]$programHeaderSize * $programHeaderCount)
    if ($programTableEnd -gt [uint64]$Bytes.LongLength) {
        throw "ELF program-header table falls outside the file: $EntryPath"
    }

    $loadAlignments = New-Object 'System.Collections.Generic.List[uint64]'
    for ($index = 0; $index -lt $programHeaderCount; $index++) {
        $headerOffset = [int]($programHeaderOffset + ([uint64]$programHeaderSize * $index))
        $programType = Get-UInt32LittleEndian -Bytes $Bytes -Offset $headerOffset
        if ($programType -eq 1) {
            $alignment = Get-UInt64LittleEndian -Bytes $Bytes -Offset ($headerOffset + 48)
            if ($alignment -lt [uint64]0x4000) {
                throw ("ELF PT_LOAD alignment 0x{0:x} is below 0x4000: {1}" -f $alignment, $EntryPath)
            }
            $loadAlignments.Add($alignment)
        }
    }
    if ($loadAlignments.Count -le 0) {
        throw "ELF file has no PT_LOAD segments: $EntryPath"
    }

    $minimumAlignment = ($loadAlignments | Measure-Object -Minimum).Minimum
    return [pscustomobject]@{
        path = $EntryPath
        abi = $Abi
        elfMachine = $machine
        byteLength = $Bytes.LongLength
        sha256 = Get-BytesSha256 -Bytes $Bytes
        loadSegmentCount = $loadAlignments.Count
        minimumLoadAlignment = [uint64]$minimumAlignment
        minimumLoadAlignmentHex = ("0x{0:x}" -f [uint64]$minimumAlignment)
    }
}

function Get-PackagedElfRecords {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ArchivePath,

        [Parameter(Mandatory = $true)]
        [string]$ArchiveKind
    )

    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $records = New-Object 'System.Collections.Generic.List[object]'
        $counts = @{
            "arm64-v8a" = 0
            "x86_64" = 0
        }
        foreach ($entry in $archive.Entries) {
            $match = [regex]::Match(
                $entry.FullName,
                '^(?:[^/]+/)?lib/(arm64-v8a|x86_64)/[^/]+\.so$',
                [Text.RegularExpressions.RegexOptions]::CultureInvariant
            )
            if (-not $match.Success) {
                continue
            }
            $abi = $match.Groups[1].Value
            $bytes = Read-ZipEntryBytes -Entry $entry
            $records.Add((Get-ElfLoadAlignmentRecord `
                -Bytes $bytes `
                -EntryPath $entry.FullName `
                -Abi $abi))
            $counts[$abi]++
        }
        foreach ($abi in @("arm64-v8a", "x86_64")) {
            if ($counts[$abi] -le 0) {
                throw "$ArchiveKind contains no packaged $abi ELF libraries."
            }
        }
        return @($records | Sort-Object path)
    }
    finally {
        $archive.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$repositoryResult = Invoke-NativeCapture `
    -FilePath "git" `
    -Arguments @("rev-parse", "--show-toplevel") `
    -Description "Repository discovery"
$repository = [IO.Path]::GetFullPath($repositoryResult.output.Trim())

$evidence = $null
$resolvedEvidence = $null
$evidenceHash = $null
$releaseGateMode = $PSCmdlet.ParameterSetName -eq "Evidence"
if ($releaseGateMode) {
    $resolvedEvidence = Resolve-ExistingLeaf -Path $EvidenceManifest -Name "EvidenceManifest"
    $expectedEvidenceHash = Get-NormalizedSha256 `
        -Value $ExpectedEvidenceSha256 `
        -Name "ExpectedEvidenceSha256"
    $evidenceHash = Get-Sha256 -Path $resolvedEvidence
    if ($evidenceHash -ne $expectedEvidenceHash) {
        throw "The evidence manifest does not match the reviewed SHA-256 digest."
    }
    $evidence = Get-Content -Raw -LiteralPath $resolvedEvidence | ConvertFrom-Json
    $playBundleProperty = $evidence.PSObject.Properties["playBundle"]
    $playVariantProperty = $evidence.PSObject.Properties["playVariant"]
    if ($evidence.schemaVersion -ne 2 -or $null -eq $playBundleProperty) {
        throw "The reviewed schema-v2 evidence has no canonical playBundle record."
    }
    if (
        $null -eq $playVariantProperty -or
        $playVariantProperty.Value.bundleTask -cne ":app:bundlePlay"
    ) {
        throw "The evidence does not identify :app:bundlePlay as the canonical Play bundle task."
    }
    $resolvedPlayBundle = Resolve-EvidenceArtifact `
        -Record $playBundleProperty.Value `
        -Repository $repository
}
else {
    $canonicalPlayBundle = [IO.Path]::GetFullPath((Join-Path `
        $repository `
        "app\build\outputs\bundle\play\app-play.aab"))
    $resolvedPlayBundle = Resolve-ExistingLeaf -Path $PlayBundle -Name "PlayBundle"
    if (-not $resolvedPlayBundle.Equals(
        $canonicalPlayBundle,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Preflight PlayBundle must be the canonical :app:bundlePlay output: $canonicalPlayBundle"
    }
    $expectedPreflightBundleHash = Get-NormalizedSha256 `
        -Value $ExpectedPlayBundleSha256 `
        -Name "ExpectedPlayBundleSha256"
    if ((Get-Sha256 -Path $resolvedPlayBundle) -ne $expectedPreflightBundleHash) {
        throw "The canonical preflight Play AAB does not match ExpectedPlayBundleSha256."
    }
}
if ([IO.Path]::GetExtension($resolvedPlayBundle) -cne ".aab") {
    throw "The canonical Play bundle is not an AAB."
}
$playBundleHash = Get-Sha256 -Path $resolvedPlayBundle

$resolvedJdk = [IO.Path]::GetFullPath($JdkPath)
$java = Resolve-ExistingLeaf -Path (Join-Path $resolvedJdk "bin\java.exe") -Name "JDK java.exe"
$keytool = Resolve-ExistingLeaf -Path (Join-Path $resolvedJdk "bin\keytool.exe") -Name "JDK keytool.exe"
$expectedJavaHash = if ($releaseGateMode) {
    Get-NormalizedSha256 -Value ([string]$evidence.toolchain.javaSha256) -Name "toolchain.javaSha256"
}
else {
    Get-NormalizedSha256 -Value $ExpectedJavaSha256 -Name "ExpectedJavaSha256"
}
if ($expectedJavaHash -ne (Get-Sha256 -Path $java)) {
    throw "The explicit JDK does not match the canonical Play evidence toolchain."
}

$resolvedBuildToolsVersion = if ($releaseGateMode) {
    [string]$evidence.toolchain.buildToolsVersion
}
else {
    $BuildToolsVersion
}
if ($resolvedBuildToolsVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "The evidence does not record a supported Android build-tools version."
}
$resolvedSdk = [IO.Path]::GetFullPath($AndroidSdkPath)
$zipalign = Resolve-ExistingLeaf `
    -Path (Join-Path $resolvedSdk "build-tools\$resolvedBuildToolsVersion\zipalign.exe") `
    -Name "zipalign.exe"
$expectedZipalignHash = if ($releaseGateMode) {
    Get-NormalizedSha256 `
        -Value ([string]$evidence.toolchain.zipalignSha256) `
        -Name "toolchain.zipalignSha256"
}
else {
    Get-NormalizedSha256 -Value $ExpectedZipalignSha256 -Name "ExpectedZipalignSha256"
}
if ($expectedZipalignHash -ne (Get-Sha256 -Path $zipalign)) {
    throw "zipalign.exe does not match the canonical Play evidence toolchain."
}

$resolvedBundletool = Resolve-ExistingLeaf -Path $BundletoolJar -Name "BundletoolJar"
$expectedBundletoolHash = Get-NormalizedSha256 `
    -Value $ExpectedBundletoolSha256 `
    -Name "ExpectedBundletoolSha256"
$bundletoolHash = Get-Sha256 -Path $resolvedBundletool
if ($bundletoolHash -ne $expectedBundletoolHash) {
    throw "BundletoolJar does not match ExpectedBundletoolSha256."
}

$bundletoolVersionResult = Invoke-NativeCapture `
    -FilePath $java `
    -Arguments @("-jar", $resolvedBundletool, "version") `
    -Description "bundletool version"
$bundletoolVersion = $bundletoolVersionResult.output.Trim()
if ($bundletoolVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
    throw "BundletoolJar is not a supported standalone bundletool CLI."
}

$configResult = Invoke-NativeCapture `
    -FilePath $java `
    -Arguments @("-jar", $resolvedBundletool, "dump", "config", "--bundle=$resolvedPlayBundle") `
    -Description "bundletool dump config"
try {
    $bundleConfig = $configResult.output | ConvertFrom-Json
}
catch {
    throw "bundletool did not emit a supported JSON bundle configuration."
}
if (
    $bundleConfig.optimizations.uncompressNativeLibraries.enabled -ne $true -or
    $bundleConfig.optimizations.uncompressNativeLibraries.alignment -cne "PAGE_ALIGNMENT_16K"
) {
    throw "The canonical Play AAB does not report enabled PAGE_ALIGNMENT_16K native-library packaging."
}

$aabElfRecords = Get-PackagedElfRecords `
    -ArchivePath $resolvedPlayBundle `
    -ArchiveKind "Canonical Play AAB"

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$tempRoot = [IO.Path]::GetFullPath((Join-Path $tempBase ("nanokvm-page-size-" + [Guid]::NewGuid().ToString("N"))))
if (-not $tempRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary audit path escaped the system temporary directory."
}
[void](New-Item -ItemType Directory -Path $tempRoot)

try {
    $auditKeystore = Join-Path $tempRoot "audit-only.jks"
    $auditPassword = "PageSizeAudit" + [Guid]::NewGuid().ToString("N")
    [void](Invoke-NativeCapture `
        -FilePath $keytool `
        -Arguments @(
            "-genkeypair",
            "-keystore", $auditKeystore,
            "-storetype", "JKS",
            "-storepass", $auditPassword,
            "-alias", "page-size-audit",
            "-keypass", $auditPassword,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-dname", "CN=NanoKVM Page Size Audit",
            "-noprompt"
        ) `
        -Description "Ephemeral audit-key generation")

    $apkSet = Join-Path $tempRoot "page-size-audit.apks"
    [void](Invoke-NativeCapture `
        -FilePath $java `
        -Arguments @(
            "-jar", $resolvedBundletool,
            "build-apks",
            "--bundle=$resolvedPlayBundle",
            "--output=$apkSet",
            "--mode=universal",
            "--overwrite",
            "--ks=$auditKeystore",
            "--ks-key-alias=page-size-audit",
            "--ks-pass=pass:$auditPassword",
            "--key-pass=pass:$auditPassword"
        ) `
        -Description "bundletool universal APK-set generation")

    $apkSetHash = Get-Sha256 -Path $apkSet
    $apkSetArchive = [IO.Compression.ZipFile]::OpenRead($apkSet)
    try {
        $universalEntries = @($apkSetArchive.Entries | Where-Object {
            $_.FullName -ceq "universal.apk"
        })
        if ($universalEntries.Count -ne 1) {
            throw "The generated APK set has no unique universal.apk."
        }
        $universalApk = Join-Path $tempRoot "universal.apk"
        $sourceStream = $universalEntries[0].Open()
        $destinationStream = [IO.File]::Create($universalApk)
        try {
            $sourceStream.CopyTo($destinationStream)
        }
        finally {
            $destinationStream.Dispose()
            $sourceStream.Dispose()
        }
    }
    finally {
        $apkSetArchive.Dispose()
    }

    $universalApkHash = Get-Sha256 -Path $universalApk
    $apkElfRecords = Get-PackagedElfRecords -ArchivePath $universalApk -ArchiveKind "Generated universal APK"
    $zipalignResult = Invoke-NativeCapture `
        -FilePath $zipalign `
        -Arguments @("-c", "-P", "16", "-v", "4", $universalApk) `
        -Description "zipalign 16 KB verification"

    if ((Get-Sha256 -Path $resolvedPlayBundle) -ne $playBundleHash) {
        throw "The canonical Play AAB changed while page-size verification was running."
    }
    if ($releaseGateMode -and (Get-Sha256 -Path $resolvedEvidence) -ne $evidenceHash) {
        throw "The reviewed evidence manifest changed while page-size verification was running."
    }
    if (
        (Get-Sha256 -Path $java) -ne $expectedJavaHash -or
        (Get-Sha256 -Path $resolvedBundletool) -ne $bundletoolHash -or
        (Get-Sha256 -Path $zipalign) -ne $expectedZipalignHash
    ) {
        throw "A pinned page-size verification tool changed while the audit was running."
    }

    $record = [ordered]@{
        schemaVersion = 1
        createdUtc = [DateTime]::UtcNow.ToString("o")
        verificationMode = if ($releaseGateMode) { "reviewed-evidence" } else { "dirty-worktree-preflight" }
        pageSizeReleaseGateClosed = $releaseGateMode
        evidenceManifest = if ($releaseGateMode) {
            [ordered]@{
                path = $resolvedEvidence
                sha256 = $evidenceHash
            }
        }
        else {
            $null
        }
        playBundle = [ordered]@{
            path = $resolvedPlayBundle
            byteLength = (Get-Item -LiteralPath $resolvedPlayBundle).Length
            sha256 = $playBundleHash
            canonicalTask = ":app:bundlePlay"
            nativeLibraryAlignment = "PAGE_ALIGNMENT_16K"
            bundleConfigDumpSha256 = Get-StringSha256 -Value $configResult.output
        }
        aabElfLibraries = $aabElfRecords
        generatedAuditArtifacts = [ordered]@{
            distributionStatus = "ephemeral audit-only; deleted after verification"
            apkSetSha256 = $apkSetHash
            universalApkSha256 = $universalApkHash
            universalApkElfLibraries = $apkElfRecords
        }
        zipalign = [ordered]@{
            arguments = @("-c", "-P", "16", "-v", "4", "<ephemeral-universal.apk>")
            exitCode = $zipalignResult.exitCode
            outputSha256 = Get-StringSha256 -Value $zipalignResult.output
            output = $zipalignResult.lines
        }
        toolchain = [ordered]@{
            javaSha256 = Get-Sha256 -Path $java
            keytoolSha256 = Get-Sha256 -Path $keytool
            bundletoolVersion = $bundletoolVersion
            bundletoolSha256 = $bundletoolHash
            buildToolsVersion = $resolvedBuildToolsVersion
            zipalignSha256 = Get-Sha256 -Path $zipalign
        }
    }

    if ($OutputPath) {
        $resolvedOutput = if ([IO.Path]::IsPathRooted($OutputPath)) {
            [IO.Path]::GetFullPath($OutputPath)
        }
        else {
            [IO.Path]::GetFullPath((Join-Path $repository $OutputPath))
        }
        if (Test-Path -LiteralPath $resolvedOutput) {
            throw "OutputPath already exists; page-size evidence is immutable: $resolvedOutput"
        }
        $outputParent = Split-Path -Parent $resolvedOutput
        if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
            throw "OutputPath parent directory does not exist: $outputParent"
        }
        $inProgressOutput = $resolvedOutput + ".in-progress-" + [Guid]::NewGuid().ToString("N")
        try {
            [pscustomobject]$record |
                ConvertTo-Json -Depth 8 |
                Set-Content -LiteralPath $inProgressOutput -Encoding utf8
            Move-Item -LiteralPath $inProgressOutput -Destination $resolvedOutput
        }
        finally {
            if (Test-Path -LiteralPath $inProgressOutput) {
                Remove-Item -Force -LiteralPath $inProgressOutput
            }
        }
        $outputHash = Get-Sha256 -Path $resolvedOutput
        Write-Output "Page-size evidence: $resolvedOutput"
        Write-Output "Page-size evidence SHA-256: $outputHash"
    }

    if (-not $releaseGateMode) {
        Write-Output "PRE-FLIGHT ONLY: no reviewed playBundle evidence was supplied; the release gate remains open."
    }
    Write-Output "Verified canonical Play AAB: $resolvedPlayBundle"
    Write-Output "Play AAB SHA-256: $playBundleHash"
    Write-Output "Bundle config native alignment: PAGE_ALIGNMENT_16K"
    Write-Output "AAB 64-bit ELF libraries checked: $($aabElfRecords.Count)"
    Write-Output "Ephemeral APK set SHA-256: $apkSetHash"
    Write-Output "Ephemeral universal APK SHA-256: $universalApkHash"
    Write-Output "Generated APK 64-bit ELF libraries checked: $($apkElfRecords.Count)"
    Write-Output "zipalign -c -P 16 -v 4: passed"
    Write-Output "bundletool SHA-256: $bundletoolHash"
    Write-Output "zipalign SHA-256: $(Get-Sha256 -Path $zipalign)"
}
finally {
    if (
        (Test-Path -LiteralPath $tempRoot) -and
        $tempRoot.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)
    ) {
        Remove-Item -Recurse -Force -LiteralPath $tempRoot
    }
}
