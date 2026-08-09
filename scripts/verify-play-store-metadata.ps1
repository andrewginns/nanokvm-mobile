[CmdletBinding()]
param(
    [string]$ListingRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ListingRoot)) {
    $ListingRoot = Join-Path $PSScriptRoot '..\store\google-play\listings'
}
$resolvedRoot = (Resolve-Path -LiteralPath $ListingRoot).Path
$requirements = [ordered]@{
    'title.txt' = 30
    'short-description.txt' = 80
    'full-description.txt' = 4000
    'release-notes.txt' = 500
}
$placeholderPattern = '(?i)\{\{[^}]+\}\}|<[^>]*required[^>]*>|\b(?:todo|tbd)\b'
$locales = @(Get-ChildItem -LiteralPath $resolvedRoot -Directory | Sort-Object Name)

if ($locales.Count -eq 0) {
    throw "No locale directories were found under '$resolvedRoot'."
}

foreach ($locale in $locales) {
    foreach ($entry in $requirements.GetEnumerator()) {
        $path = Join-Path $locale.FullName $entry.Key
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Missing required Play listing file '$path'."
        }

        $content = (Get-Content -LiteralPath $path -Raw -Encoding UTF8).Trim()
        if ([string]::IsNullOrWhiteSpace($content)) {
            throw "Play listing file '$path' is empty."
        }
        if ($content.Length -gt $entry.Value) {
            throw "Play listing file '$path' has $($content.Length) characters; the limit is $($entry.Value)."
        }
        if ($content -match $placeholderPattern) {
            throw "Play listing file '$path' still contains a placeholder or drafting marker: '$($Matches[0])'."
        }

        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host ("PASS {0}/{1}: {2}/{3} characters; sha256={4}" -f `
            $locale.Name, $entry.Key, $content.Length, $entry.Value, $hash)
    }
}

Write-Host "Verified Play Store metadata for $($locales.Count) locale(s)."
