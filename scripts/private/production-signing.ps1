function Assert-NoReparsePointInPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $root = [IO.Path]::GetPathRoot($fullPath)
    $current = $root
    $components = $fullPath.Substring($root.Length).Split(
        [char[]]@('\', '/'),
        [StringSplitOptions]::RemoveEmptyEntries
    )
    foreach ($component in $components) {
        $current = Join-Path $current $component
        $item = Get-Item -LiteralPath $current -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Production signing paths must not traverse a junction or symbolic link: $current"
        }
    }
}

function Assert-SingleHardLink {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not ("NanoKvmRelease.NativeMethods" -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace NanoKvmRelease {
    [StructLayout(LayoutKind.Sequential)]
    public struct ByHandleFileInformation {
        public uint FileAttributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    public static class NativeMethods {
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        public static extern bool GetFileInformationByHandle(
            SafeFileHandle file,
            out ByHandleFileInformation information
        );
    }
}
'@
    }

    $stream = [IO.File]::Open(
        $Path,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::Read
    )
    try {
        $information = New-Object NanoKvmRelease.ByHandleFileInformation
        $inspected = [NanoKvmRelease.NativeMethods]::GetFileInformationByHandle(
            $stream.SafeFileHandle,
            [ref]$information
        )
        if (-not $inspected) {
            $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            $errorMessage = (New-Object ComponentModel.Win32Exception($errorCode)).Message
            throw "Could not verify the production-keystore link count: $errorMessage ($errorCode)."
        }
        if ($information.NumberOfLinks -ne 1) {
            throw (
                "The production keystore must have exactly one filesystem link; " +
                "found $($information.NumberOfLinks)."
            )
        }
    } finally {
        $stream.Dispose()
    }
}

function Assert-ProtectedAcl {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [switch]$RequireProtectedInheritance
    )

    $acl = Get-Acl -LiteralPath $Path
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $systemSid = "S-1-5-18"
    $administratorsSid = "S-1-5-32-544"
    $allowedSids = @($currentSid, $systemSid, $administratorsSid)
    $ownerSid = ([Security.Principal.NTAccount]$acl.Owner).Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
    if ($ownerSid -ne $currentSid) {
        throw "The protected signing path must be owned by the signing account: $Path"
    }
    if ($RequireProtectedInheritance -and -not $acl.AreAccessRulesProtected) {
        throw "The protected keystore directory must disable inherited NTFS permissions."
    }

    $currentAccountHasFullControl = $false
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            continue
        }
        $ruleSid = $rule.IdentityReference.Translate(
            [Security.Principal.SecurityIdentifier]
        ).Value
        if ($ruleSid -notin $allowedSids) {
            throw "The protected signing path grants access to an unexpected identity: $($rule.IdentityReference)"
        }
        if (
            $ruleSid -eq $currentSid -and
            (($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
                [Security.AccessControl.FileSystemRights]::FullControl)
        ) {
            $currentAccountHasFullControl = $true
        }
    }
    if (-not $currentAccountHasFullControl) {
        throw "The signing account requires FullControl on the protected signing path: $Path"
    }
}
