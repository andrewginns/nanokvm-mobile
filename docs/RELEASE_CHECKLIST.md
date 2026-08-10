# GitHub APK release checklist

Use this checklist only when publishing new APK bytes. The current stable
release record is retained with the [v0.3.6 GitHub
release](https://github.com/andrewginns/nanokvm-mobile/releases/tag/v0.3.6), not
as a mutable checklist in `main`.

## Release identity

- [ ] `versionName` and `versionCode` are higher than the latest published APK.
- [ ] `CHANGELOG.md` and the release notes describe user-visible changes,
  compatibility, privacy/security effects, and known limitations.
- [ ] The worktree is clean and the source is frozen under the recorded release
  tag and commit.
- [ ] The exact preceding production APK and the public signing lineage in
  `release/production-signing-lineage.json` are available.

## Build and review

- [ ] The full strict local gate in [Testing](TESTING.md) passes with JDK 21.
- [ ] Dependency changes have reviewed verification metadata, inventory,
  licences, source locations, and vulnerability dispositions.
- [ ] `NanoKVM-Mobile-X.Y.Z-runtime-sources.json` maps every resolved external
  runtime component/family to an exact source revision and hashed retained
  archive, or explicitly marks metadata-only entries, as defined in
  [Distribution](DISTRIBUTION.md).
- [ ] The generated Baseline/Startup Profiles and canonical SBOM are current.
- [ ] No credentials, private topology, signing material, or private console
  data appears in the source, logs, screenshots, or release bundle.
- [ ] No known security, trust, credential, unintended-input, data-loss,
  licence, or corresponding-source blocker remains undispositioned.

## Device and appliance checks

- [ ] The signed candidate installs as an update over the latest public APK
  without clearing profiles, pins, or protected credentials.
- [ ] A representative physical Android phone runs connection, certificate
  review, login, first frame, keyboard/pointer input, foreground recovery, and
  reconnect against a real NanoKVM.
- [ ] H.264 and MJPEG are exercised; WebRTC is exercised when the release or
  target uses it. The NanoKVM hardware and application version are recorded.
- [ ] Changed features pass their applicable cases in
  [the appliance test plan](APPLIANCE_TEST_PLAN.md). Destructive cases use a
  disposable or recovery-capable target.
- [ ] Changed UI is checked for large text, light/dark theme, keyboard focus,
  screen rotation or resizing, and accessibility semantics.
- [ ] The signed candidate's package, version, permissions, non-debuggable
  state, signer, v2/v3 signatures, and alignment are verified.

Broader Android-version, accessibility-service, endurance, and performance
coverage is useful but is not implied when it was not run. Record the actual
scope in the release notes.

## Publish

- [ ] `scripts/write-unsigned-release-evidence.ps1` and
  `scripts/sign-production-apk.ps1` complete against the frozen tag as described
  in [Distribution](DISTRIBUTION.md).
- [ ] The uploaded APK is byte-identical to the tested signed candidate.
- [ ] The GitHub release contains the signed APK, `SHA256SUMS`, public signing
  certificate digest, exact project source archive/link, canonical SBOM,
  reviewed runtime-source manifest and archives, licence and notice bundle, and
  release notes.
- [ ] The published checksum, source link, APK download, and in-app About
  documents are checked from the public release page.
- [ ] A rollback or withdrawal note is ready for signing, trust, credential,
  unintended-input, data-loss, or corresponding-source defects.

Record the release owner, date, tag/commit, APK SHA-256, signing certificate
SHA-256, Android device, NanoKVM hardware/application version, and the outcome
of each applicable check with the release evidence.
