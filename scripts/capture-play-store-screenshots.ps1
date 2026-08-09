[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-[0-9]+$')]
    [string]$Serial,

    [string]$ExpectedAvdName = "NanoKVM_Play_Screenshots_API_37_16K",

    [string]$AndroidSdkPath = $(
        if ($env:ANDROID_SDK_ROOT) {
            $env:ANDROID_SDK_ROOT
        } elseif ($env:LOCALAPPDATA) {
            Join-Path $env:LOCALAPPDATA "Android\Sdk"
        } else {
            ""
        }
    ),

    [string]$JavaHome = "C:\Program Files\Android\Android Studio\jbr",

    [string]$DevelopmentKeystore = $(
        if ($env:NANOKVM_DEVELOPMENT_KEYSTORE) {
            $env:NANOKVM_DEVELOPMENT_KEYSTORE
        } elseif ($env:USERPROFILE) {
            Join-Path $env:USERPROFILE ".android\debug.keystore"
        } else {
            ""
        }
    ),

    [ValidateRange(320, 640)]
    [int]$Density = 360,

    [string]$OutputDirectory = "store\google-play\assets"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = & $script:AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine).Trim()
}

function Export-PrivateAppFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RemotePath,

        [Parameter(Mandatory = $true)]
        [string]$Destination
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:AdbPath
    $startInfo.Arguments = "-s `"$Serial`" exec-out run-as org.nanokvm.mobile cat `"$RemotePath`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    try {
        $stream = [IO.File]::Create($Destination)
        try {
            $process.StandardOutput.BaseStream.CopyTo($stream)
        } finally {
            $stream.Dispose()
        }
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "Unable to export '$RemotePath' from the test app: $errorText"
        }
    } finally {
        $process.Dispose()
    }
}

function Convert-ToPlayPng {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Source,

        [Parameter(Mandatory = $true)]
        [string]$Destination
    )

    $sourceImage = [Drawing.Bitmap]::FromFile($Source)
    try {
        if ($sourceImage.Width -ne 1080 -or $sourceImage.Height -ne 1920) {
            throw "Raw screenshot '$Source' is $($sourceImage.Width)x$($sourceImage.Height); expected 1080x1920 without resizing."
        }
        $converted = [Drawing.Bitmap]::new(
            1080,
            1920,
            [Drawing.Imaging.PixelFormat]::Format24bppRgb
        )
        try {
            $graphics = [Drawing.Graphics]::FromImage($converted)
            try {
                $graphics.DrawImageUnscaled($sourceImage, 0, 0)
            } finally {
                $graphics.Dispose()
            }
            $converted.Save($Destination, [Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $converted.Dispose()
        }
    } finally {
        $sourceImage.Dispose()
    }
}

$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$script:AdbPath = Join-Path $AndroidSdkPath "platform-tools\adb.exe"
$gradle = Join-Path $repository "gradlew.bat"
$keystore = (Resolve-Path -LiteralPath $DevelopmentKeystore).Path
$repositoryPrefix = $repository + [IO.Path]::DirectorySeparatorChar
if ($keystore.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The development keystore must remain outside the repository: $keystore"
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkPath "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "apksigner.bat") -PathType Leaf } |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw "No Android build-tools installation containing apksigner.bat was found."
}
$apkSigner = Join-Path $buildTools.FullName "apksigner.bat"
$resolvedOutput = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $repository $OutputDirectory))
}

foreach ($requiredFile in @(
    $script:AdbPath,
    $gradle,
    (Join-Path $JavaHome "bin\java.exe"),
    $keystore,
    $apkSigner
)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required capture dependency does not exist: $requiredFile"
    }
}

$state = Invoke-AdbText -Arguments @("get-state")
if ($state -ne "device") {
    throw "Android target '$Serial' is not ready: $state"
}
$avdName = Invoke-AdbText -Arguments @("shell", "getprop", "ro.boot.qemu.avd_name")
if ($avdName -ne $ExpectedAvdName) {
    throw "Capture is restricted to dedicated AVD '$ExpectedAvdName'; target reported '$avdName'."
}

$screenSize = Invoke-AdbText -Arguments @("shell", "wm", "size")
$sizeMatches = [regex]::Matches($screenSize, '(\d+)x(\d+)')
if ($sizeMatches.Count -eq 0) {
    throw "Unable to determine the Android target size from: $screenSize"
}
$effectiveSize = $sizeMatches[$sizeMatches.Count - 1]
if ($effectiveSize.Groups[1].Value -ne "1080" -or $effectiveSize.Groups[2].Value -ne "1920") {
    throw "Android target '$Serial' must use an exact 1080x1920 display; reported: $screenSize"
}

$apiLevel = Invoke-AdbText -Arguments @("shell", "getprop", "ro.build.version.sdk")
$pageSize = Invoke-AdbText -Arguments @("shell", "getconf", "PAGE_SIZE")
$systemLocales = Invoke-AdbText -Arguments @("shell", "settings", "get", "system", "system_locales")
$captureLocale = $systemLocales
if ([string]::IsNullOrWhiteSpace($captureLocale) -or $captureLocale -eq "null") {
    $captureLocale = Invoke-AdbText -Arguments @("shell", "getprop", "persist.sys.locale")
}
if ([string]::IsNullOrWhiteSpace($captureLocale)) {
    $captureLocale = Invoke-AdbText -Arguments @("shell", "getprop", "ro.product.locale")
}
if ([int]$apiLevel -ne 37) {
    throw "Screenshot QA requires API 37 exactly; target reported API $apiLevel."
}
if ($pageSize -ne "16384") {
    throw "Screenshot QA requires the installed 16 KB-page image; target reported PAGE_SIZE=$pageSize."
}
if ($captureLocale -notmatch '^en-US(?:,|$)') {
    throw "Screenshot QA requires the en-US locale; target reported '$captureLocale'."
}

$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "system", "font_scale", "1.0")
$null = Invoke-AdbText -Arguments @("shell", "wm", "density", $Density.ToString())
$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "system", "accelerometer_rotation", "0")
$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "system", "user_rotation", "0")
$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "global", "window_animation_scale", "0")
$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "global", "transition_animation_scale", "0")
$null = Invoke-AdbText -Arguments @("shell", "settings", "put", "global", "animator_duration_scale", "0")
$densityReport = Invoke-AdbText -Arguments @("shell", "wm", "density")
$densityMatches = [regex]::Matches($densityReport, '(\d+)')
if ($densityMatches.Count -eq 0 -or $densityMatches[$densityMatches.Count - 1].Value -ne $Density.ToString()) {
    throw "Android target '$Serial' did not apply density $Density; reported: $densityReport"
}

$captureRoot = Join-Path $repository ("build\play-store-screenshot-capture\" + [Guid]::NewGuid().ToString("N"))
$rawDirectory = Join-Path $captureRoot "raw"
$stagedDirectory = Join-Path $captureRoot "staged"
$null = New-Item -ItemType Directory -Path $rawDirectory -Force
$null = New-Item -ItemType Directory -Path $stagedDirectory -Force
$null = New-Item -ItemType Directory -Path $resolvedOutput -Force

$oldJavaHome = $env:JAVA_HOME
$oldKeystore = $env:NANOKVM_DEVELOPMENT_KEYSTORE
try {
    $env:JAVA_HOME = $JavaHome
    $env:NANOKVM_DEVELOPMENT_KEYSTORE = $keystore
    & $gradle :app:assembleDebug :app:assembleDebugAndroidTest `
        --no-configuration-cache `
        --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed while building the screenshot host APKs."
    }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:NANOKVM_DEVELOPMENT_KEYSTORE = $oldKeystore
}

$appApk = Join-Path $repository "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $repository "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Expected screenshot test APK was not produced: $apk"
    }
    $certificateReport = & $apkSigner verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner could not verify '$apk': $($certificateReport -join [Environment]::NewLine)"
    }
    $certificateMatch = [regex]::Match(
        ($certificateReport -join [Environment]::NewLine),
        'Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)'
    )
    if (-not $certificateMatch.Success) {
        throw "apksigner did not report a signer SHA-256 for '$apk'."
    }
    $actualSigner = $certificateMatch.Groups[1].Value.Replace(":", "").ToUpperInvariant()
    $expectedSigner = "7F2E5128EB089159536803992E381AA830D0E7A2D9601FAC0048E3821EA02746"
    if ($actualSigner -ne $expectedSigner) {
        throw "Screenshot test APK signer mismatch for '$apk'. Expected $expectedSigner; found $actualSigner."
    }
    $installResult = Invoke-AdbText -Arguments @("install", "-r", "-t", $apk)
    if ($installResult -notmatch "Success") {
        throw "Unable to install '$apk': $installResult"
    }
}

$instrumentation = Invoke-AdbText -Arguments @(
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "class",
    "org.nanokvm.mobile.StoreScreenshotInstrumentedTest",
    "org.nanokvm.mobile.test/androidx.test.runner.AndroidJUnitRunner"
)
Write-Output $instrumentation
if ($instrumentation -notmatch "OK \(4 tests\)" -or $instrumentation -match "FAILURES!!!") {
    throw "The deterministic store screenshot instrumentation did not pass all four tests."
}

Add-Type -AssemblyName System.Drawing
$expectedFiles = @(
    "screenshot-phone-01-connections.png",
    "screenshot-phone-02-profile-editor.png",
    "screenshot-phone-03-console.png",
    "screenshot-phone-04-video-settings.png"
)
foreach ($fileName in $expectedFiles) {
    $rawPath = Join-Path $rawDirectory $fileName
    $stagedPath = Join-Path $stagedDirectory $fileName
    Export-PrivateAppFile -RemotePath "files/play-store-screenshots/$fileName" -Destination $rawPath
    Convert-ToPlayPng -Source $rawPath -Destination $stagedPath
}

foreach ($fileName in $expectedFiles) {
    [IO.File]::Copy(
        (Join-Path $stagedDirectory $fileName),
        (Join-Path $resolvedOutput $fileName),
        $true
    )
}

Write-Output "Captured four real-UI Play screenshots from $Serial/$avdName (API $apiLevel, PAGE_SIZE=$pageSize, density=$Density, locale=$captureLocale)."
Write-Output "Raw and staged evidence: $captureRoot"
Write-Output "Publication assets: $resolvedOutput"
