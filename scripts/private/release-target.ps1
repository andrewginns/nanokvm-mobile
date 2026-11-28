function Get-ReleaseTarget {
    param([Parameter(Mandatory)][string]$Repository, [ValidateSet('nano','comet')][string]$Target = 'nano')
    $record = Get-Content -Raw -LiteralPath (Join-Path $Repository "release/targets/$Target.properties") | ConvertFrom-StringData
    $title = $Target.Substring(0,1).ToUpperInvariant() + $Target.Substring(1)
    [pscustomobject]@{
        ApplicationId = $record.applicationId
        VersionName = $record.versionName
        VersionCode = [long]$record.versionCode
        ArtifactName = $record.artifactName
        Tag = $record.tagPrefix + $record.versionName
        Title = $title
        ReleaseVariant = $Target + 'Release'
        Slug = if ($Target -eq 'nano') { 'nanokvm-mobile' } else { 'comet-kvm-mobile' }
        Lineage = if ($Target -eq 'nano') { 'release/production-signing-lineage.json' } else { 'release/comet/production-signing-lineage.json' }
    }
}
