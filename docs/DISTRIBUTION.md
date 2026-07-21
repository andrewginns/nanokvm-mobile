# Distribution and release artifacts

NanoKVM Mobile is GPL-3.0-or-later software and is independent of Sipeed. A
local `assembleRelease` output is an unsigned engineering artifact, not an
official project release. Distribution requires a traceable source, signing,
verification, and evidence process.

## Release inputs

Record these before building:

- annotated source tag and commit, with a clean worktree;
- version name/code and supported NanoKVM application range;
- JDK 21 vendor/version, Gradle wrapper version/hash, Android SDK/build-tools,
  host OS, and build command;
- reviewed version catalogue, strict verification metadata, resolved dependency
  graph/SBOM, dependency inventory, licence and vulnerability dispositions;
- versioned Baseline and Startup Profile sources generated from that commit; and
- release owner, security reviewer, QA owner, signing operator, and rollback
  decision maker.

The signing key must not be stored in the repository, build logs, release
bundle, or ordinary developer backup. Document key custody, access,
backup/recovery, and certificate rotation/revocation out of band. Record only
the public signing certificate digest in the release evidence.

Default Android debug signing is not a distribution identity. Its keystore is
selected from the build JVM's user-home directory, so Android Studio, an
ordinary terminal, and a sandboxed build can produce mutually incompatible
update APKs on the same computer. Verify every artifact with `apksigner`; never
publish an ambient debug build or promise it as a production update channel.
During active development, an explicitly selected and retained local identity
may be used for best-effort updates between named test APKs only when package,
signer, monotonically increasing version code, and an actual in-place update are
verified. That development lineage is neither a public release identity nor a
recoverable compatibility promise. Before the first binary release, freeze the
application ID and establish one protected, recoverable production signing
lineage.

### Practical signing setup for direct GitHub APKs

Android application signing does not require a public certificate authority.
For direct GitHub distribution, use one long-lived self-signed application key
whose private material stays outside this repository. Before the first release,
place encrypted backups in two separately controlled locations and record who
can sign, recover, rotate, or revoke the key.

Create an account-only NTFS directory once. This example retains access for the
signing account, local administrators, and Windows itself while removing
inherited access such as `Users` or `Authenticated Users`:

```powershell
$signingDirectory = Join-Path $env:LOCALAPPDATA 'NanoKVM Signing'
New-Item -ItemType Directory -Path $signingDirectory
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$acl = Get-Acl -LiteralPath $signingDirectory
$acl.SetOwner($identity.User)
$acl.SetAccessRuleProtection($true, $false)
$inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
$propagation = [Security.AccessControl.PropagationFlags]::None
$allow = [Security.AccessControl.AccessControlType]::Allow
foreach ($sid in @(
    $identity.User,
    [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
    [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
)) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $sid,
        [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance,
        $propagation,
        $allow
    ))
}
Set-Acl -LiteralPath $signingDirectory -AclObject $acl
```

Create the key interactively with Android Studio's **Generate Signed Bundle or
APK** flow, or with the guarded JDK 21 helper. The helper deliberately accepts
no password argument; `keytool` prompts instead of exposing a secret in shell
history, a process listing, Gradle state, or build output. Generate at least a
20-character random password in a password manager; do not reuse an account
password.

```powershell
.\scripts\new-production-keystore.ps1 `
    -Keytool 'C:\path\to\jdk-21\bin\keytool.exe' `
    -KeystorePath "$env:LOCALAPPDATA\NanoKVM Signing\NanoKVM-Mobile-production.keystore" `
    -KeyAlias nanokvm-release
```

Record the public certificate SHA-256 reported by the helper in the tracked
`release/production-signing-lineage.json` file. The first version name/code in
that record is a one-time bootstrap boundary; later signing requires the actual
preceding production APK as well as the same certificate.

```json
{
  "schemaVersion": 1,
  "package": "org.nanokvm.mobile",
  "keyAlias": "nanokvm-release",
  "signingCertificateSha256": "<64-hex-public-certificate-digest>",
  "firstProductionVersionName": "0.3.6",
  "firstProductionVersionCode": 13,
  "previousProduction": null
}
```

For a later release, replace `previousProduction: null` in that release's
tagged record with the exact preceding release identity:

```json
"previousProduction": {
  "versionName": "0.3.6",
  "versionCode": 13,
  "apkSha256": "<64-hex-preceding-production-apk-digest>"
}
```

The signing helper compares all three fields before it touches the private key.

Commit that public record, then freeze the release source under an annotated or
signed tag. Generate and review an unsigned evidence manifest before exposing
the private key. The helper itself runs the authoritative clean strict build
with the supplied JDK 21 runtime:

```powershell
.\scripts\write-unsigned-release-evidence.ps1 `
    -SourceTag 'v0.3.6' `
    -JavaPath 'C:\path\to\jdk-21\bin\java.exe' `
    -BuildToolsPath 'C:\path\to\Android\Sdk\build-tools\36.0.0'
```

The evidence command verifies and hashes the exact unsigned APK, release AAB,
SBOM, R8 mapping, Baseline and Startup Profiles, test/lint reports, strict-build
log, Gradle wrapper, JDK, and Android signing tools. Review the reported evidence
and unsigned APK SHA-256 values, then sign only those reviewed bytes:

```powershell
.\scripts\sign-production-apk.ps1 `
    -SourceTag 'v0.3.6' `
    -UnsignedEvidencePath '.\dist\NanoKVM-Mobile-0.3.6-v13-unsigned-evidence.json' `
    -ExpectedEvidenceSha256 '<64-hex-reviewed-evidence-digest>' `
    -JavaPath 'C:\path\to\jdk-21\bin\java.exe' `
    -BuildToolsPath 'C:\path\to\Android\Sdk\build-tools\36.0.0' `
    -KeystorePath "$env:LOCALAPPDATA\NanoKVM Signing\NanoKVM-Mobile-production.keystore" `
    -KeyAlias nanokvm-release `
    -FirstProductionRelease
```

For every later release, replace `-FirstProductionRelease` with
`-PreviousProductionApk` and the actual preceding production APK. The signer
rejects repository-local keys, dirty or untagged source, signed or debuggable
inputs, source/APK version mismatches, changed unsigned bytes or toolchain, an
unexpected or development certificate, missing v2/v3 signatures, failed
alignment, and existing output paths. It prompts through `apksigner`; neither
the keystore password nor key password is accepted on the command line.

The helper emits the signed APK, its checksum manifest, the public signing
certificate digest, and machine-readable release metadata under ignored
`dist/`, including the retained verbose `apksigner` verification. These files
are inputs to the wider evidence and publication process; the helper does not
close any checklist item by itself. Complete and verify two encrypted,
separately controlled backups before the first signing operation. Losing the
private key prevents in-place updates on this distribution channel.
Development APKs signed with the retained debug identity cannot update into
this first production lineage. Uninstalling one removes its profiles,
certificate pins, and protected credentials.

## Build and verify

The evidence helper invokes this clean build gate with JDK 21 and strict
dependency verification:

```powershell
.\gradlew.bat --no-problems-report --no-daemon --no-parallel --no-configuration-cache --refresh-dependencies --dependency-verification=strict clean test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
```

The repository build produces:

- `app/build/outputs/apk/release/app-release-unsigned.apk`;
- `app/build/outputs/bundle/release/app-release.aab`;
- `app/build/outputs/apk/benchmark/app-benchmark.apk` for controlled testing
  only (debug-signed and not distributable);
- `app/build/reports/cyclonedx/nanokvm-mobile.cdx.json`;
- `app/build/outputs/mapping/release/` with mapping, seeds, usage, resources,
  and effective configuration; and
- generated profile sources plus packaged Baseline Profile metadata.

GitHub-hosted build/test workflows and public artifact uploads are intentionally
disabled during active development. Maintainers run the strict gate locally and
retain its command output and artifacts privately against the exact commit.
Before a production candidate is approved, create an exact-source archive and a
reviewed unsigned evidence manifest from the frozen release commit. Generate a
separate checksum manifest after production signing; no development-output hash
can identify the final published bytes.

Retain the complete test/lint output, merged release manifest and Network
Security Config, resolved dependency graph, SBOM, vulnerability/licence review,
R8 outputs, profile verification result, and device/manual evidence. Local-only
storage is acceptable during development; production evidence needs named
custody, integrity hashes, backup, and a durable release-record location.

## Signing and candidate identity

Sign the APK/AAB only through the approved release environment. Then record:

- release version and source commit/tag;
- SHA-256 of each final signed artifact;
- SHA-256 of the signing certificate and signature verification output;
- package name, version code/name, minimum/target SDK, and merged permissions;
- whether the candidate is APK, AAB, or store-generated split APKs; and
- exact relationship between the tested signed candidate and published bytes.

All signed/minified device, accessibility, security, and real-appliance tests
must use that candidate or a byte-identical artifact. Rebuilding after QA
invalidates the sign-off and requires rerunning affected gates.

## Corresponding source and public release bundle

Every binary distribution must point to complete corresponding source for that
exact version. Publish together, or through a durable link stated alongside the
binary:

- source archive from the release tag, including all production modules,
  versioned generated profiles, Gradle wrapper/configuration, verification
  metadata, and scripts required to build;
- `LICENSE`, `NOTICE`, public privacy/security documents, and dependency licence
  inventory;
- the complete notice/licence bundle required by the exact pinned WebRTC/native
  dependency graph, retained locally with reviewed provenance and hashes rather
  than only an external URL;
- signed APK and/or store/AAB artifact as applicable;
- canonical CycloneDX SBOM;
- SHA-256 checksum file generated after signing;
- public signing-certificate digest and verification instructions;
- release notes with supported Android/NanoKVM versions, known limitations,
  migration/rollback notes, and security/privacy changes; and
- provenance/attestation when the hosting channel supports it.

R8 mapping may contain sensitive implementation metadata. Retain it privately
for crash/security diagnosis and release traceability unless the release owner
deliberately publishes it. Its hash and custody location belong in the internal
release record.

## Reproducibility

Before claiming reproducibility, build the unsigned release artifact twice in
fresh isolated environments from the same tag and recorded toolchain. Compare
the normalized dependency graph, SBOM, generated profiles, APK/AAB contents,
and SHA-256 outputs. Investigate every difference and document any signing or
archive metadata that is intentionally non-reproducible.

Do not describe the project as reproducible and do not submit to F-Droid on that
basis until the comparison passes and the recipe is retained. Store-managed
signing must document how users verify the store artifact against the published
source and build recipe.

## Publication, rollback, and field evidence

Publication requires every blocking item in `RELEASE_CHECKLIST.md` to be closed.
If a signing, trust, credential, unintended-input, data-loss, or GPL source-link
problem appears after publication, stop promotion, preserve evidence, publish a
clear advisory, and withdraw or supersede the artifact through the distribution
channel. Never reuse a version code for changed bytes, even when the earlier
artifact was shared only as a development build.

The app has no internal telemetry. After distribution, use opt-in issue reports
and channel-provided aggregate Android Vitals only where available. Field
crash/ANR/startup/slow-frame review is therefore a conditional gate for Play or
another channel that supplies those aggregate metrics, not evidence that exists
today.
