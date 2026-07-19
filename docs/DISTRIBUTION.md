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
publish or promise upgrade compatibility for a debug-signed APK. Before the
first binary release, freeze the application ID and establish one protected,
recoverable production signing lineage.

## Build and verify

Use a clean checkout, JDK 21, and strict dependency verification:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
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
