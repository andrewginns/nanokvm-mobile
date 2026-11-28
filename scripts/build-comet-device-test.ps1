[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PreviousApk,
    [string]$KeystorePath,
    [string]$OutputPath,
    [string]$Gradle = ".\gradlew.bat"
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'private/release-target.ps1')
. (Join-Path $PSScriptRoot 'private/production-signing.ps1')
. (Join-Path $PSScriptRoot 'private/local-signing-password.ps1')
$product = Get-ReleaseTarget -Repository $repository -Target comet
$previous = (Resolve-Path -LiteralPath $PreviousApk).Path
if (-not $KeystorePath) {
    $KeystorePath = Join-Path $env:LOCALAPPDATA 'CometMobile/DeviceTesting/comet-device-test.p12'
}
$keystore = (Resolve-Path -LiteralPath $KeystorePath).Path
if ($keystore.StartsWith($repository.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Select the enrolled Comet keystore outside the repository.'
}
$directory = Split-Path -Parent $keystore
Assert-NoReparsePointInPath -Path $keystore
Assert-SingleHardLink -Path $keystore
Assert-ProtectedAcl -Path $directory
Assert-ProtectedAcl -Path $keystore
$passwordFile = Join-Path $directory 'password.dpapi'
$java = (Resolve-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe')).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Where-Object Name -match '^\d+\.\d+\.\d+$' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Install a stable Android SDK Build Tools version.' }
$jar = (Resolve-Path -LiteralPath (Join-Path $buildTools.FullName 'lib/apksigner.jar')).Path
if (-not $OutputPath) {
    $OutputPath = Join-Path $repository "dist/$($product.ArtifactName)-$($product.VersionName)-v$($product.VersionCode)-device-test.apk"
}
$output = [IO.Path]::GetFullPath($OutputPath)
$null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($output))
$staged = "$output.$([guid]::NewGuid().ToString('N')).apk"
Push-Location $repository
try {
    & $Gradle --dependency-verification=strict '-PreleaseTarget=comet' :app:assembleCometRelease
    if ($LASTEXITCODE -ne 0) { throw 'The Comet release build failed.' }
    $unsigned = Join-Path $repository 'app/build/outputs/apk/comet/release/app-comet-release-unsigned.apk'
    $signingArguments = @('-jar', $jar, 'sign', '--ks', $keystore, '--v4-signing-enabled', 'false', '--out', $staged, $unsigned)
    $result = Invoke-ApkSignerWithLocalPassword -Java $java -SigningArguments $signingArguments -PasswordFile $passwordFile
    if ($result -ne 0) { throw 'Comet device-test signing failed.' }
    & (Join-Path $PSScriptRoot 'verify-apk-upgrade.ps1') -PreviousApk $previous -CandidateApk $staged -Target comet -ExpectedVersionName $product.VersionName -ExpectedVersionCode $product.VersionCode
    if (Test-Path -LiteralPath $output) {
        if ((Get-FileHash -LiteralPath $output).Hash -ne (Get-FileHash -LiteralPath $staged).Hash) {
            throw "Different bytes already exist at $output. Increase the Comet version."
        }
    } else { [IO.File]::Move($staged, $output) }
    Write-Output "Verified Comet device-test APK at $output"
} finally {
    Pop-Location
    if ([IO.File]::Exists($staged)) { [IO.File]::Delete($staged) }
}
