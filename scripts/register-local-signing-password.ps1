#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$KeystorePath,
    [switch]$SeparateKeyPassword
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Local signing enrollment requires Windows.' }
. (Join-Path $PSScriptRoot 'private\production-signing.ps1')

$keystore = (Get-Item -LiteralPath $KeystorePath -Force).FullName
$directory = Split-Path -Parent $keystore
$repository = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..')).FullName
if ($keystore.StartsWith($repository + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The keystore and its protected credential must be outside the repository.'
}
if (-not [IO.File]::Exists($keystore)) { throw 'The keystore must be an existing file.' }
Assert-NoReparsePointInPath -Path $keystore
Assert-SingleHardLink -Path $keystore
Assert-ProtectedAcl -Path $directory -RequireProtectedInheritance
Assert-ProtectedAcl -Path $keystore

$destination = Join-Path $directory 'password.dpapi'
if (Test-Path -LiteralPath $destination) {
    throw 'This directory already has a signing credential. Enrollment will not overwrite it.'
}
$labels = @('Keystore password')
if ($SeparateKeyPassword) { $labels += 'Private key password' }
$envelopes = foreach ($label in $labels) {
    $secret = Read-Host $label -AsSecureString
    try {
        if ($secret.Length -eq 0) { throw 'A nonempty signing password is required.' }
        ConvertFrom-SecureString -SecureString $secret
    } finally {
        $secret.Dispose()
    }
}

$temporary = Join-Path $directory ([Guid]::NewGuid().ToString('N') + '.dpapi.tmp')
try {
    [IO.File]::WriteAllLines($temporary, [string[]]@($envelopes))
    Assert-ProtectedAcl -Path $temporary
    [IO.File]::Move($temporary, $destination)
} finally {
    if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
}
Write-Output 'Enrolled the signing password for this Windows account and keystore directory.'
