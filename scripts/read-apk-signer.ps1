[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$VerificationOutput)

# apksigner 36 prints "Signer #1", while 37 identifies the scheme, e.g. "V3.0 Signer:".
if ($VerificationOutput -notmatch '(?m)^Number of signers: 1\r?$') {
    throw "Expected exactly one verified APK signer."
}
$prefix = '(?m)^(?:Signer #1|V[0-9.]+ Signer:) certificate '
$digest = [regex]::Matches($VerificationOutput, $prefix + 'SHA-256 digest: ([0-9a-fA-F]{64})\r?$')
$subject = [regex]::Matches($VerificationOutput, $prefix + 'DN: ([^\r\n]+)\r?$')
if ($digest.Count -ne 1 -or $subject.Count -ne 1) {
    throw "Could not identify the verified signing certificate."
}
$dn = $subject[0].Groups[1].Value.Trim()
if ($dn -match '(?:^|,\s*)CN\s*=\s*Android Debug(?:\s*,|\s*$)') {
    throw "Ambient Android debug certificates cannot sign Comet handoff artifacts."
}
[pscustomobject]@{ sha256 = $digest[0].Groups[1].Value.ToLowerInvariant(); subject = $dn }
