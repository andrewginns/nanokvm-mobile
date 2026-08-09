# Google Play 16 KB page-size evidence

The Play release gate is applied to the exact `playBundle` retained by the
reviewed unsigned-evidence manifest. A successful generic `releaseBundle`, a
locally selected AAB, or an APK assembled from different bytes does not close
this gate.

Run `scripts/verify-play-page-size.ps1` after the source is frozen and
`scripts/write-unsigned-release-evidence.ps1 -IncludePlayBundle` succeeds. The
verifier requires reviewed hashes rather than discovering an ambient artifact
or tool:

```powershell
$evidence = ".\dist\NanoKVM-Mobile-0.3.7-v14-unsigned-evidence.json"
$bundletool = "C:\release-tools\bundletool-all-1.18.3.jar"
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Android\Android Studio\jbr" }
$androidSdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$evidenceSha256 = (Get-FileHash -Algorithm SHA256 $evidence).Hash
$bundletoolSha256 = (Get-FileHash -Algorithm SHA256 $bundletool).Hash

.\scripts\verify-play-page-size.ps1 `
    -EvidenceManifest $evidence `
    -ExpectedEvidenceSha256 $evidenceSha256 `
    -BundletoolJar $bundletool `
    -ExpectedBundletoolSha256 $bundletoolSha256 `
    -JdkPath $jdk `
    -AndroidSdkPath $androidSdk `
    -OutputPath ".\dist\NanoKVM-Mobile-0.3.7-v14-play-page-size.json"
```

Use the standalone `bundletool-all` JAR from the official
[bundletool releases](https://github.com/google/bundletool/releases). The
library JAR in Gradle's module cache is not a standalone CLI and is rejected.
Review and retain the tool's SHA-256 before running the gate.

While the worktree is still being prepared, the same implementation can audit
only the exact canonical Gradle output path as an explicitly non-release
preflight. Pin every input tool and bundle hash:

```powershell
$playAab = ".\app\build\outputs\bundle\play\app-play.aab"
$java = Join-Path $jdk "bin\java.exe"
$zipalign = Join-Path $androidSdk "build-tools\36.0.0\zipalign.exe"

.\scripts\verify-play-page-size.ps1 `
    -PlayBundle $playAab `
    -ExpectedPlayBundleSha256 (Get-FileHash -Algorithm SHA256 $playAab).Hash `
    -ExpectedJavaSha256 (Get-FileHash -Algorithm SHA256 $java).Hash `
    -BuildToolsVersion "36.0.0" `
    -ExpectedZipalignSha256 (Get-FileHash -Algorithm SHA256 $zipalign).Hash `
    -BundletoolJar $bundletool `
    -ExpectedBundletoolSha256 $bundletoolSha256 `
    -JdkPath $jdk `
    -AndroidSdkPath $androidSdk
```

Preflight mode rejects every AAB path except
`app/build/outputs/bundle/play/app-play.aab`, labels its output
`pageSizeReleaseGateClosed: false`, and cannot replace the reviewed-evidence
command.

The script fails unless all of the following are true:

- the evidence hash is exact, uses schema 2, contains `playBundle`, and names
  `:app:bundlePlay` as its canonical task;
- the retained AAB length and SHA-256 match that evidence record;
- the explicit JDK and `zipalign` match the hashes in the same evidence;
- `bundletool dump config` reports enabled `PAGE_ALIGNMENT_16K` native-library
  packaging;
- both `arm64-v8a` and `x86_64` libraries exist, every packaged library is a
  matching little-endian ELF64 file, and every `PT_LOAD` alignment is at least
  `0x4000`;
- a universal APK generated from those exact AAB bytes contains both 64-bit
  ABIs, passes the same ELF inspection, and passes
  `zipalign -c -P 16 -v 4`.

The generated APK set and universal APK use a fresh, one-day, audit-only key in
the system temporary directory and are deleted after verification. They are
not distributable, do not use either development signing identity, and do not
prove the Play app-signing certificate or cross-store upgrade path. Only the
JSON evidence named by `-OutputPath` is retained; it records AAB, generated APK,
tool, config-output, ELF-library, and `zipalign` hashes.

## Current candidate status

As of 2 August 2026, the repository does not contain clean, tagged v0.3.7/code
14 unsigned evidence with a canonical `playBundle`. The retained v0.3.6
evidence predates the Play lane and contains only a generic `releaseBundle`, so
the release-gate mode correctly rejects it. After the final bundled-policy
rebuild in this preparation pass, the preflight implementation ran end-to-end
against the exact untagged v0.3.7 Play-variant AAB, including six AAB and six
generated-APK 64-bit libraries. That tooling result does **not** close the
v0.3.7 release gate.
