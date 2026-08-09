function Get-NanoKvmReleaseLintAssessment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$Repository
    )

    $allowedAdvisoryDefinitions =
        [System.Collections.Generic.Dictionary[string,string]]::new(
            [StringComparer]::Ordinal
        )
    $allowedAdvisoryDefinitions.Add(
        "A newer version of com.android.application than 9.3.0 is available: 9.3.1",
        "AndroidGradlePluginVersion"
    )
    $allowedAdvisoryDefinitions.Add(
        "A newer version of com.android.library than 9.3.0 is available: 9.3.1",
        "AndroidGradlePluginVersion"
    )
    $allowedAdvisoryDefinitions.Add(
        "A newer version of com.android.test than 9.3.0 is available: 9.3.1",
        "AndroidGradlePluginVersion"
    )
    $allowedAdvisoryDefinitions.Add(
        "A newer version of androidx.baselineprofile than 1.5.0-alpha07 is available: 1.5.0-beta01",
        "GradleDependency"
    )
    $allowedAdvisoryDefinitions.Add(
        "A newer version of androidx.baselineprofile.producer than 1.5.0-alpha07 is available: 1.5.0-beta01",
        "GradleDependency"
    )

    $resolvedReport = (Resolve-Path -LiteralPath $Path).Path
    if (-not (Test-Path -LiteralPath $Repository -PathType Container)) {
        throw "The lint assessment repository root does not exist: $Repository"
    }
    $canonicalCatalogLocation = "gradle/libs.versions.toml"
    [xml]$lintDocument = Get-Content -LiteralPath $resolvedReport
    $issueNodes = @($lintDocument.SelectNodes("/issues/issue"))
    $allowedAdvisories = [System.Collections.Generic.List[object]]::new()
    $seenAllowedMessages = New-Object 'System.Collections.Generic.HashSet[string]' (
        [StringComparer]::Ordinal
    )
    $blockingIssues = 0

    foreach ($issue in $issueNodes) {
        $id = [string]$issue.id
        $severity = [string]$issue.severity
        $message = [string]$issue.message
        $expectedId = $allowedAdvisoryDefinitions[$message]
        $locations = @($issue.location)
        $locationsAreExact = $locations.Count -gt 0
        $locationRecords = @()
        foreach ($location in $locations) {
            $locationFile = [string]$location.file
            $normalizedLocation = $locationFile.Replace('\', '/').Trim()
            $isAbsoluteLocation = (
                [IO.Path]::IsPathRooted($locationFile) -or
                $normalizedLocation -match '^(?:[a-zA-Z]:/|/)'
            )
            $locationMatches = if ($isAbsoluteLocation) {
                $normalizedLocation -match '(?i)/gradle/libs\.versions\.toml$'
            } else {
                $normalizedLocation.Equals(
                    $canonicalCatalogLocation,
                    [StringComparison]::OrdinalIgnoreCase
                )
            }
            if (-not $locationMatches) {
                $locationsAreExact = $false
            }
            $line = if ([string]$location.line -match '^[0-9]+$') {
                [int]$location.line
            } else {
                0
            }
            $column = if ([string]$location.column -match '^[0-9]+$') {
                [int]$location.column
            } else {
                0
            }
            $locationRecords += [pscustomobject]@{
                path = $canonicalCatalogLocation
                line = $line
                column = $column
            }
        }

        $isExactAdvisory = (
            $severity -ceq "Warning" -and
            -not [string]::IsNullOrWhiteSpace($expectedId) -and
            $id -ceq $expectedId -and
            $locationsAreExact
        )
        if ($isExactAdvisory -and $seenAllowedMessages.Add($message)) {
            $allowedAdvisories.Add([pscustomobject]@{
                id = $id
                severity = $severity
                message = $message
                locations = $locationRecords
            })
        } else {
            $blockingIssues++
        }
    }

    return [pscustomobject]@{
        issues = $issueNodes.Count
        blockingIssues = $blockingIssues
        allowedAdvisories = @($allowedAdvisories)
    }
}

function Test-NanoKvmLintEvidenceMatches {
    param(
        [Parameter(Mandatory = $true)]
        [object]$EvidenceRecord,

        [Parameter(Mandatory = $true)]
        [object]$Assessment
    )

    foreach ($propertyName in @("issues", "blockingIssues", "allowedAdvisories")) {
        if ($null -eq $EvidenceRecord.PSObject.Properties[$propertyName]) {
            return $false
        }
    }
    if (
        [int]$EvidenceRecord.issues -ne [int]$Assessment.issues -or
        [int]$EvidenceRecord.blockingIssues -ne [int]$Assessment.blockingIssues
    ) {
        return $false
    }
    $recordedAdvisories = @($EvidenceRecord.allowedAdvisories) |
        ConvertTo-Json -Depth 6 -Compress
    $actualAdvisories = @($Assessment.allowedAdvisories) |
        ConvertTo-Json -Depth 6 -Compress
    return $recordedAdvisories -ceq $actualAdvisories
}
