# Windows PowerShell's ConvertFrom-SecureString uses current-user DPAPI on Windows.
# Only pass decrypted characters to the verified apksigner child's private stdin.
function Invoke-ApkSignerWithLocalPassword {
    param(
        [Parameter(Mandatory)][string]$Java,
        [Parameter(Mandatory)][string[]]$SigningArguments,
        [Parameter(Mandatory)][string]$PasswordFile
    )

    if ($env:OS -ne 'Windows_NT' -or $PSVersionTable.PSVersion -lt [version]'7.2') {
        throw 'Local unattended signing requires PowerShell 7.2 or later on Windows.'
    }
    Assert-NoReparsePointInPath -Path $PasswordFile
    Assert-SingleHardLink -Path $PasswordFile
    Assert-ProtectedAcl -Path $PasswordFile
    $lines = [IO.File]::ReadAllLines($PasswordFile)
    if ($lines.Count -notin 1,2) {
        throw 'Expected a protected store password and an optional protected key password.'
    }
    $secrets = [Collections.Generic.List[Security.SecureString]]::new()
    $process = $null
    try {
        foreach ($line in $lines) {
            if ($line -notmatch '\A01000000d08c9ddf[0-9a-f]+\z') {
                throw 'The local signing credential has an unexpected format.'
            }
            try {
                $secrets.Add((ConvertTo-SecureString -String $line -ErrorAction Stop))
            } catch {
                throw 'This Windows account could not unlock the local signing credential.'
            }
        }
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.FileName = $Java
        $start.UseShellExecute = $false
        $start.CreateNoWindow = $true
        $start.RedirectStandardInput = $true
        $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
        # The caller constructs these arguments. Keep the input APK last.
        for ($index = 0; $index -lt $SigningArguments.Count - 1; $index++) {
            $start.ArgumentList.Add($SigningArguments[$index])
        }
        foreach ($argument in '--ks-pass','stdin','--key-pass','stdin','--pass-encoding','utf-8') {
            $start.ArgumentList.Add($argument)
        }
        $start.ArgumentList.Add($SigningArguments[-1])
        $process = [Diagnostics.Process]::Start($start)
        try {
            foreach ($secret in @($secrets[0], $secrets[$secrets.Count - 1])) {
                $pointer = [IntPtr]::Zero
                $chars = [char[]]::new($secret.Length)
                try {
                    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($secret)
                    [Runtime.InteropServices.Marshal]::Copy($pointer, $chars, 0, $chars.Length)
                    if ($chars.Length -eq 0 -or $chars -contains [char]10 -or $chars -contains [char]13) {
                        throw 'The signing credential must be a nonempty single line.'
                    }
                    $process.StandardInput.WriteLine($chars, 0, $chars.Length)
                } finally {
                    [Array]::Clear($chars, 0, $chars.Length)
                    if ($pointer -ne [IntPtr]::Zero) {
                        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
                    }
                }
            }
        } finally {
            $process.StandardInput.Close()
            $process.WaitForExit()
        }
        return $process.ExitCode
    } finally {
        if ($null -ne $process) { $process.Dispose() }
        foreach ($secret in $secrets) { $secret.Dispose() }
    }
}
