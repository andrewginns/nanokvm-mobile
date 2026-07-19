param(
    [string]$Gradle = ".\gradlew.bat"
)

$ErrorActionPreference = "Stop"
$repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$comparisonDirectory = Join-Path $temporaryRoot ("nanokvm-repro-" + [Guid]::NewGuid().ToString("N"))
$comparisonDirectory = [IO.Path]::GetFullPath($comparisonDirectory)
if (-not $comparisonDirectory.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a comparison directory outside the system temporary directory."
}
New-Item -ItemType Directory -Path $comparisonDirectory | Out-Null

$artifacts = @(
    "app\build\outputs\apk\release\app-release-unsigned.apk",
    "app\build\outputs\bundle\release\app-release.aab",
    "app\build\reports\cyclonedx\nanokvm-mobile.cdx.json",
    "app\build\outputs\mapping\release\configuration.txt",
    "app\build\outputs\mapping\release\mapping.txt"
)

function Invoke-ReleaseBuild {
    & $Gradle --no-daemon --no-parallel --no-build-cache --no-configuration-cache --no-problems-report `
        "-Dkotlin.compiler.execution.strategy=in-process" --dependency-verification=strict `
        clean :app:verifyReleaseProfiles bundleRelease :app:reproducibleSbom
    if ($LASTEXITCODE -ne 0) {
        throw "Release build failed with exit code $LASTEXITCODE."
    }
}

try {
    Push-Location $repository
    try {
        Invoke-ReleaseBuild
        foreach ($artifact in $artifacts) {
            $source = Join-Path $repository $artifact
            Copy-Item -LiteralPath $source -Destination (Join-Path $comparisonDirectory ([IO.Path]::GetFileName($artifact)))
        }

        Invoke-ReleaseBuild
        foreach ($artifact in $artifacts) {
            $first = Join-Path $comparisonDirectory ([IO.Path]::GetFileName($artifact))
            $second = Join-Path $repository $artifact
            $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $first).Hash
            $secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $second).Hash
            if ($firstHash -ne $secondHash) {
                throw "Reproducibility mismatch for $artifact ($firstHash != $secondHash)."
            }
            Write-Output "$secondHash  $artifact"
        }
    } finally {
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath $comparisonDirectory) {
        $verified = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $comparisonDirectory).Path)
        if (-not $verified.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove a comparison directory outside the system temporary directory."
        }
        Remove-Item -LiteralPath $verified -Recurse -Force
    }
}
