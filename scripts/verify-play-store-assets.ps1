[CmdletBinding()]
param(
    [string]$AssetDirectory = "store\google-play\assets"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-PngRecord {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $file = Get-Item -LiteralPath $resolved
    $image = [Drawing.Image]::FromFile($resolved)
    try {
        return [pscustomobject]@{
            Name = $file.Name
            Path = $resolved
            Width = $image.Width
            Height = $image.Height
            PixelFormat = $image.PixelFormat.ToString()
            HasAlpha = [Drawing.Image]::IsAlphaPixelFormat($image.PixelFormat)
            Bytes = $file.Length
            Sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } finally {
        $image.Dispose()
    }
}

function Assert-Dimensions {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Record,

        [Parameter(Mandatory = $true)]
        [int]$Width,

        [Parameter(Mandatory = $true)]
        [int]$Height
    )

    if ($Record.Width -ne $Width -or $Record.Height -ne $Height) {
        throw "$($Record.Name) is $($Record.Width)x$($Record.Height); expected ${Width}x${Height}."
    }
}

$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$candidateDirectory = if ([IO.Path]::IsPathRooted($AssetDirectory)) {
    $AssetDirectory
} else {
    Join-Path $repository $AssetDirectory
}
$resolvedDirectory = (Resolve-Path -LiteralPath $candidateDirectory).Path

Add-Type -AssemblyName System.Drawing

$icon = Get-PngRecord -Path (Join-Path $resolvedDirectory "play-icon-512.png")
Assert-Dimensions -Record $icon -Width 512 -Height 512
if (-not $icon.HasAlpha) {
    throw "$($icon.Name) must be a 32-bit PNG with an alpha channel."
}
if ($icon.Bytes -gt 1MB) {
    throw "$($icon.Name) exceeds Google Play's 1,024 KB icon limit."
}

$featureGraphic = Get-PngRecord -Path (Join-Path $resolvedDirectory "feature-graphic-1024x500.png")
Assert-Dimensions -Record $featureGraphic -Width 1024 -Height 500
if ($featureGraphic.HasAlpha) {
    throw "$($featureGraphic.Name) must be a 24-bit PNG without alpha."
}
if ($featureGraphic.PixelFormat -ne "Format24bppRgb") {
    throw "$($featureGraphic.Name) uses $($featureGraphic.PixelFormat); expected a 24-bit RGB PNG."
}
if ($featureGraphic.Bytes -gt 15MB) {
    throw "$($featureGraphic.Name) exceeds Google Play's 15 MB feature-graphic limit."
}

$expectedScreenshotNames = @(
    "screenshot-phone-01-connections.png",
    "screenshot-phone-02-profile-editor.png",
    "screenshot-phone-03-console.png",
    "screenshot-phone-04-video-settings.png"
)
$actualScreenshotNames = @(
    Get-ChildItem -LiteralPath $resolvedDirectory -File |
        Where-Object { $_.BaseName -like "screenshot-phone-*" } |
        Sort-Object Name |
        Select-Object -ExpandProperty Name
)
$unexpectedScreenshots = @($actualScreenshotNames | Where-Object { $_ -notin $expectedScreenshotNames })
$missingScreenshots = @($expectedScreenshotNames | Where-Object { $_ -notin $actualScreenshotNames })
if ($unexpectedScreenshots.Count -gt 0 -or $missingScreenshots.Count -gt 0) {
    throw "Phone screenshot inventory mismatch. Missing: [$($missingScreenshots -join ', ')]. Unexpected: [$($unexpectedScreenshots -join ', ')]."
}
$screenshots = $expectedScreenshotNames |
    ForEach-Object { Get-PngRecord -Path (Join-Path $resolvedDirectory $_) }
foreach ($screenshot in $screenshots) {
    Assert-Dimensions -Record $screenshot -Width 1080 -Height 1920
    if ($screenshot.HasAlpha) {
        throw "$($screenshot.Name) must be a 24-bit PNG without alpha."
    }
    if ($screenshot.PixelFormat -ne "Format24bppRgb") {
        throw "$($screenshot.Name) uses $($screenshot.PixelFormat); expected a 24-bit RGB PNG."
    }
    $shortEdge = [Math]::Min($screenshot.Width, $screenshot.Height)
    $longEdge = [Math]::Max($screenshot.Width, $screenshot.Height)
    if ($shortEdge -lt 320 -or $longEdge -gt 3840) {
        throw "$($screenshot.Name) dimensions must stay within 320-3,840 px."
    }
    if ($longEdge -gt (2 * $shortEdge)) {
        throw "$($screenshot.Name) has an unsupported aspect ratio greater than 2:1."
    }
    if ($screenshot.Bytes -gt 8MB) {
        throw "$($screenshot.Name) exceeds Google Play's 8 MB screenshot limit."
    }
}

$records = @($icon, $featureGraphic) + @($screenshots)
$records | Format-Table Name, Width, Height, PixelFormat, Bytes, Sha256 -AutoSize
Write-Output "Verified Google Play icon, feature graphic, and four exact 1080x1920 phone screenshots."
