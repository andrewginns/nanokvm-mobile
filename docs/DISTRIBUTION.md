# Publishing a signed GitHub APK

NanoKVM Mobile distributes public APKs through GitHub Releases. A local debug,
benchmark, or unsigned release build is not a publishable artifact.

## Signing model

Public updates use the long-lived production certificate recorded in
`release/production-signing-lineage.json`. Keep the private keystore and its
password outside the repository, logs, release bundle, and normal developer
backups. Maintain two encrypted recovery copies in separately controlled
locations.

Do not generate a new key for an ordinary update. Losing or replacing the key
prevents an in-place update of existing installations. Never recommend
uninstalling to hide a mismatch: uninstalling removes profiles, certificate
pins, and protected credentials.

The default Android debug certificate is also unsuitable for publication. It
is selected from the build process's user home and may differ between tools or
accounts.

`scripts/new-production-keystore.ps1` exists only for deliberately establishing
a completely new public signing lineage. It must never be used for an ordinary
update or as a recovery shortcut when the existing production key is missing.

## Prepare a release

1. Increase `versionName` and `versionCode`; never reuse a published code for
   different bytes.
2. Update the changelog, dependency inventory, privacy/security documents, and
   third-party notices when affected.
3. Run the strict build and applicable device/appliance checks in
   [Testing](TESTING.md) and [the release checklist](RELEASE_CHECKLIST.md).
4. Commit the public signing-lineage record, freeze a clean source commit, and
   create an annotated or signed release tag.

For every release after v0.3.6, the lineage record's `previousProduction` entry
must identify the exact preceding public version name/code and APK SHA-256. The
signing helper compares that record with the supplied preceding APK before it
uses the private key.

## Build evidence and sign

Run the guarded PowerShell helpers from the frozen tag with JDK 21 and Android
Build Tools 36.0.0. Substitute the actual paths and tag:

```powershell
.\scripts\write-unsigned-release-evidence.ps1 `
    -SourceTag 'vX.Y.Z' `
    -JavaPath 'C:\path\to\jdk-21\bin\java.exe' `
    -BuildToolsPath 'C:\path\to\Android\Sdk\build-tools\36.0.0'
```

Review the generated evidence manifest, source archive, unsigned APK, test and
lint results, manifest, dependency graph, canonical SBOM, profiles, and R8
outputs. Then sign only those reviewed bytes:

```powershell
.\scripts\sign-production-apk.ps1 `
    -SourceTag 'vX.Y.Z' `
    -UnsignedEvidencePath '.\dist\NanoKVM-Mobile-X.Y.Z-vNN-unsigned-evidence.json' `
    -ExpectedEvidenceSha256 '<reviewed-evidence-sha256>' `
    -JavaPath 'C:\path\to\jdk-21\bin\java.exe' `
    -BuildToolsPath 'C:\path\to\Android\Sdk\build-tools\36.0.0' `
    -KeystorePath 'C:\protected\NanoKVM-Mobile-production.keystore' `
    -KeyAlias nanokvm-release `
    -PreviousProductionApk 'C:\evidence\previous-release.apk'
```

`-FirstProductionRelease` applied only to v0.3.6 and must not be used for a
normal update. The signer rejects dirty or untagged source, repository-local
keys, changed evidence, a wrong package/version/signer, debuggable inputs,
missing v2/v3 signatures, failed alignment, and existing output paths.

The tested signed APK must be the uploaded APK. Any rebuild changes the bytes
and invalidates the signed-candidate checks.

The evidence helper does not create or validate corresponding source for
external runtime components. Before publication, create and review
`NanoKVM-Mobile-X.Y.Z-runtime-sources.json`. For each resolved component or
clearly defined dependency family, record:

- the Maven coordinate and binary SHA-256, or mark a BOM/platform entry as
  metadata-only;
- source kind (`maven-sources`, `upstream-archive`, or `metadata-only`), exact
  tag/commit and durable URL;
- source archive SHA-256 and its retained release path; and
- any exception or disposition.

Map repackaged protobuf to upstream protobuf v28.2 rather than DataStore's stub
sources, and map the WebRTC wrapper to its exact wrapper tag and underlying
WebRTC commit. Retain or mirror the exact source archives with the release when
their continued availability is not otherwise assured. Include the completed
manifest in `SHA256SUMS`; successful signing alone is not a complete release
gate.

## Required release assets

Publish these together on the GitHub release:

- the signed APK and its post-signing SHA-256 manifest;
- the public signing-certificate digest and verification output;
- complete corresponding source for the exact tag, including build scripts,
  Gradle wrapper/configuration, generated profiles, and dependency metadata;
- the reviewed runtime-source manifest and retained source archives, canonical
  CycloneDX SBOM, project licence, and complete third-party licence/notices;
- release notes covering Android/NanoKVM compatibility, upgrade behavior,
  known limitations, security/privacy changes, and actual test scope.

Keep private keys, passwords, benchmark APKs, R8 mapping details, and private
device evidence out of public assets. Preserve them privately when useful for
diagnosis and release traceability.

GPL-3.0-or-later requires equivalent access to the complete corresponding
source when the APK is offered. A repository link is useful only when it points
to the exact durable tag and the linked runtime dependencies' required source
locations remain available.

## Verify and respond

From the public release page, download the APK and checksum again and verify
their bytes, package, version, certificate, signatures, and source link. Confirm
an update from the preceding public APK on a physical phone.

If a signing, trust, credential, unintended-input, data-loss, licence, or source
defect is found, preserve evidence, publish a clear advisory, and withdraw or
supersede the artifact. Never replace assets silently or reuse the version code.
