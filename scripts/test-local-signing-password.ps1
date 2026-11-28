#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$BuildToolsPath,
    [Parameter(Mandatory)][string]$AndroidJar
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'private\production-signing.ps1')
. (Join-Path $PSScriptRoot 'private\local-signing-password.ps1')
$register = Join-Path $PSScriptRoot 'register-local-signing-password.ps1'
$apkSigner = Join-Path $BuildToolsPath 'lib\apksigner.jar'
$root = Join-Path ([IO.Path]::GetTempPath()) ('local-signing-' + [guid]::NewGuid().ToString('N'))
$keystore = Join-Path $root 'disposable.p12'
$credential = Join-Path $root 'password.dpapi'
$unsigned = Join-Path $root 'input.apk'
$signed = Join-Path $root 'signed.apk'
$manifest = Join-Path $root 'AndroidManifest.xml'
$null = [IO.Directory]::CreateDirectory($root)
try {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    Set-Acl -LiteralPath $root -AclObject $acl
    $password = [guid]::NewGuid().ToString('N')
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=Disposable local signing test', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $cert = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddDays(1))
    [IO.File]::WriteAllBytes($keystore, $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Pfx, $password))
    & {
        function Read-Host { param($Prompt, [switch]$AsSecureString); ConvertTo-SecureString $password -AsPlainText -Force }
        & $register -KeystorePath $keystore
    }
    try { & $register -KeystorePath $keystore; throw 'Overwrite was accepted.' }
    catch { if ($_.Exception.Message -notlike '*already has a signing credential*') { throw } }

    [IO.File]::WriteAllText($manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.signingtest"><uses-sdk android:minSdkVersion="26"/><application android:hasCode="false"/></manifest>')
    & (Join-Path $BuildToolsPath 'aapt2.exe') link --manifest $manifest -I $AndroidJar -o $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Fixture APK generation failed.' }
    $arguments = @('-jar',$apkSigner,'sign','--ks',$keystore,'--ks-type','PKCS12',
        '--v4-signing-enabled','false','--out',$signed,$unsigned)
    $code = Invoke-ApkSignerWithLocalPassword -Java $Java -SigningArguments $arguments -PasswordFile $credential
    if ($code -ne 0) { throw "Disposable signing failed with code $code." }
    $report = & $Java -jar $apkSigner verify --verbose --print-certs $signed
    $fingerprint = $cert.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256)
    if ($LASTEXITCODE -ne 0 -or ($report -join ' ') -notmatch $fingerprint -or
        -not ($report -match 'Verified using v2 scheme.*true') -or -not ($report -match 'Verified using v3 scheme.*true')) {
        throw 'Signed fixture did not match the expected certificate and signature schemes.'
    }
    [IO.File]::Delete($signed)
    [IO.File]::WriteAllText($credential, 'invalid fixture')
    try {
        Invoke-ApkSignerWithLocalPassword -Java $Java -SigningArguments $arguments -PasswordFile $credential
        throw 'Malformed credential was accepted.'
    } catch { if ($_.Exception.Message -notlike '*unexpected format*') { throw } }
    if ([IO.File]::Exists($signed)) { throw 'A rejected request produced an APK.' }
    Write-Output 'PASS: local enrollment, overwrite rejection, unattended v2/v3 signature and malformed credential rejection.'
} finally {
    if ($cert) { $cert.Dispose() }
    if ($rsa) { $rsa.Dispose() }
    foreach ($file in $manifest,$keystore,$credential,$unsigned,$signed) { [IO.File]::Delete($file) }
    [IO.Directory]::Delete($root, $false)
}
