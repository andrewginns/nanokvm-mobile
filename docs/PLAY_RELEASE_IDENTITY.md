# Google Play package, signing and version checklist

This checklist protects update compatibility between the existing direct APK
and Google Play. Guarded repository tooling may orchestrate signing, but private
key material and credentials are intentionally omitted here and must remain
outside the repository.

## Frozen identity

| Property | Established value |
| --- | --- |
| Android package/application ID | `org.nanokvm.mobile` |
| Existing production release | `0.3.6`, version code `13` |
| Existing direct/legacy classical certificate SHA-256 | `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD` |
| Current minimum Android SDK | 26 (Android 8.0) |
| Current target/compile SDK | 37 / 37 |
| Next Play version name | `0.3.7` |
| Next Play version code | `14` (greater than 13; not yet used in a Play track) |
| Play upload certificate SHA-256 | `{{UPLOAD_CERT_SHA256}}` |
| Play Android 17+ hybrid RSA certificate SHA-256 | `{{PLAY_HYBRID_RSA_CERT_SHA256}}` |
| Play Android 17+ ML-DSA-65 fingerprint | `{{PLAY_HYBRID_MLDSA_FINGERPRINT}}` |

Target SDK 37 exceeds Google's announced API 36 requirement for new apps and
updates from 31 August 2026. The requirement shown by Play Console for the
actual upload remains authoritative.

The existing public lineage is recorded in
`release/production-signing-lineage.json`. When Play App Signing offers its
current quantum-ready flow, transfer the existing protected RSA key so it
remains the legacy/classical signing identity used for Android 16 and earlier
and for direct-install continuity. Play may additionally create RSA-4096 and
ML-DSA-65 identities for hybrid signing on Android 17 and later. Record all
Play-displayed fingerprints and validate delivery/update behavior on both sides
of that OS boundary; do not assume every delivered APK has one certificate.

## One-time Play App Signing decision

- [ ] Account owner opens the first release's Play App Signing setup.
- [ ] Choose the option to provide the existing protected production app-signing
  key, using the PEPK flow and instructions downloaded from that Play Console
  session. Do not let Play silently establish an unrelated lineage.
- [ ] Perform the transfer from the protected signing environment; never copy
  the keystore into this repository or attach it to an issue/release artifact.
- [ ] Create a distinct Play **upload** key outside the repository. The upload
  key signs AAB submissions; it is not the key on APKs installed by users.
- [ ] Store encrypted, separately controlled recoverable backups and custody
  details outside the repository.
- [ ] Record only public certificate SHA-256 values here/release evidence.
- [ ] Verify Play displays the existing production certificate as the
  transferred legacy/classical identity before accepting the irreversible
  setup, then record every additional RSA and ML-DSA identity Play creates for
  Android 17+ hybrid signing.
- [ ] Review Play's cross-store update setting and retain the intended compatible
  update path for production-signed direct installs; the physical upgrade test
  below remains authoritative.

Do not use either retained development certificate for a Play artifact. In
particular, the update-compatible development certificate
`7F:2E:51:28:EB:08:91:59:53:68:03:99:2E:38:1A:A8:30:D0:E7:A2:D9:60:1F:AC:00:48:E3:82:1E:A0:27:46`
and the incompatible sandbox certificate
`14:9D:69:4D:B3:D3:B0:D8:68:49:D1:F9:9A:57:0F:B7:8C:11:62:77:39:49:4A:A8:C4:E0:4E:EC:6E:27:60:02`
are not the production identity.

## Guarded repository workflow

The repository now separates upload-key creation, evidence generation and AAB
signing:

1. `scripts/new-play-upload-keystore.ps1` creates a distinct RSA Play upload
   key at an explicitly selected protected path outside the repository. It
   prompts interactively and exports only the public certificate.
2. `scripts/write-unsigned-release-evidence.ps1 -IncludePlayBundle` builds and
   records the dedicated unsigned Play AAB as `playBundle` alongside the direct
   release evidence.
3. After reviewing and hashing that evidence, `scripts/sign-play-upload-aab.ps1`
   signs only the recorded bytes. Its required inputs include the source tag,
   evidence path/hash, JDK path, external keystore/alias, and expected public
   upload-certificate SHA-256. It requires and signs only the `playBundle`
   evidence record; the PicoClaw-enabled generic `releaseBundle` cannot enter
   this upload lane.

The signer emits a checksum file, upload-certificate digest and PEM, verbose
`jarsigner` verification, and machine-readable Play-upload metadata beside the
signed AAB. Review those outputs; the upload certificate is not the certificate
that Play places on delivered APKs.

## Candidate checks before upload

- [ ] Source is a clean, reviewed, annotated release tag.
- [ ] The candidate is built with the dedicated `:app:bundlePlay` lane; do not
  substitute the PicoClaw-enabled generic `bundleRelease` artifact.
- [ ] `applicationId` and bundle package are exactly `org.nanokvm.mobile`.
- [ ] Version name/code equal `0.3.7` / `14`; code is strictly greater than 13
  and has never been
  used in any Play track.
- [ ] Target API meets the requirement shown by Play Console on upload.
- [ ] The `play` build is non-debuggable, minified and contains the expected 64-bit
  native libraries and packaged Baseline Profile.
- [ ] The exact evidence-manifest `playBundle` passes the fail-closed 16 KB
  native-library and generated-APK audit in `PLAY_PAGE_SIZE.md`; retain its
  immutable JSON result with the release evidence.
- [ ] Strict tests, lint, dependency verification, SBOM and unsigned release
  evidence belong to the same source tag and exact AAB bytes.
- [ ] The AAB is signed only with the registered upload key; `jarsigner
  -verify -verbose -certs` succeeds and its public certificate is
  `{{UPLOAD_CERT_SHA256}}`.
- [ ] AAB SHA-256 and byte size are recorded; no existing versioned output was
  overwritten.
- [ ] No keystore, passwords, signing properties or private key material are in
  the repository, Gradle state, build logs or distributable evidence.
- [ ] Do not upload the retained v0.3.6 AAB under `dist/` as the new candidate;
  prepare new evidence from the exact current release tag.

## Play-delivered checks

- [ ] Upload first to Internal testing and wait for Play processing.
- [ ] Record Play Console's bundle version, AAB SHA-256 and upload certificate.
- [ ] Download or install Play-generated APKs for representative devices.
- [ ] Verify every delivered APK reports package `org.nanokvm.mobile` and
  version `0.3.7` / `14`. On Android 16 and earlier, confirm the legacy
  classical certificate is
  `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD`.
- [ ] On Android 17+, record and verify the Play-delivered hybrid signing
  identities/lineage against `{{PLAY_HYBRID_RSA_CERT_SHA256}}` and
  `{{PLAY_HYBRID_MLDSA_FINGERPRINT}}`; test a fresh install and every update
  path rather than assuming the pre-17 signer result.
- [ ] If a future API provider or App Links configuration authenticates package
  fingerprints, register every fingerprint Play says applies. None is used by
  the current app, but recheck before each release.
- [ ] Verify fresh install and core appliance journeys on physical hardware.
- [ ] Install the actual production-signed v0.3.6 APK, create a disposable
  profile/certificate decision/protected credential, then update through Play.
- [ ] Confirm the update succeeds without uninstalling and preserves that test
  state. Uninstalling would erase profiles, certificate pins and protected
  credentials and is not an acceptable compatibility test.
- [ ] Complete the Data Safety traffic audit on a Play-delivered build.
- [ ] Attach device-specific results to `PLAY_FIELD_TEST_EVIDENCE.md` and the
  formal release evidence.

## Sign-off

| Item | Value |
| --- | --- |
| Source tag / commit | |
| AAB SHA-256 | |
| Upload certificate SHA-256 | `{{UPLOAD_CERT_SHA256}}` |
| Legacy/classical certificate SHA-256 | `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD` |
| Play Android 17+ hybrid RSA certificate SHA-256 | `{{PLAY_HYBRID_RSA_CERT_SHA256}}` |
| Play Android 17+ ML-DSA-65 fingerprint | `{{PLAY_HYBRID_MLDSA_FINGERPRINT}}` |
| Delivered APK/APKS evidence | |
| Cross-store update result | |
| Signing operator / date | |
| QA owner / date | |
| Publisher approval / date | |

## Official references

- [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Prepare and roll out a release](https://support.google.com/googleplay/android-developer/answer/9859348)
- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

References were reviewed on 2026-08-02. Follow the instructions generated by
the live Play App Signing flow for the account and candidate.
