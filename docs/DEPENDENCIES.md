# Dependency inventory

NanoKVM Mobile deliberately keeps its dependency surface small. Gradle resolves
only from Google Maven, Maven Central, and the Gradle Plugin Portal; repository
declarations in individual projects are rejected. Versions are centralized in
`gradle/libs.versions.toml`, artifacts are checked against
`gradle/verification-metadata.xml`, and the Gradle distribution is protected by
its published checksum. Dependency and build validation is run locally; hosted
CI is not part of this repository's release process.

## Direct runtime dependency families

This table groups the dependencies maintainers review directly. The exact
transitive graph and licence IDs are generated in the canonical CycloneDX SBOM;
the exact resolved coordinate list and offline licence texts are bundled under
`app/src/main/assets/open_source_licenses/`.

| Component | Purpose | Data or permissions used | Licence | Owner / review |
| --- | --- | --- | --- | --- |
| AndroidX Core, Activity, Fragment, Lifecycle and ViewModel | Android lifecycle, Activity/permission integration, biometric host compatibility, and observable UI state | App lifecycle and platform permission state only; no data leaves the device | Apache-2.0 | App maintainers; each dependency PR |
| Jetpack Compose UI, Foundation, Material 3 and Material 3 Adaptive | Native Android UI, accessibility semantics, current-window size classification, and supporting-pane layout | User interaction, window geometry, and rendered local state only | Apache-2.0 | App maintainers; each dependency PR |
| AndroidX DataStore Preferences | Persist non-secret NanoKVM profiles and public certificate pins | App-private storage; explicitly excluded from backup | Apache-2.0 | App maintainers; each dependency PR |
| AndroidX Biometric | Authorize Android Keystore access to an explicitly saved password | System biometric/device-credential prompt; the library does not receive the NanoKVM password | Apache-2.0 | Security owner; each dependency PR |
| AndroidX ProfileInstaller | Install the app's packaged Baseline Profile when the runtime does not install it directly | App package metadata and local ART profile state only | Apache-2.0 | Performance owner; regenerate and review at each release |
| OkHttp | HTTPS, REST, WebSocket and MJPEG transport to the user-selected NanoKVM origin | `INTERNET`; NanoKVM address, login request, in-memory session cookie, video and input | Apache-2.0 | Protocol owner; each dependency PR |
| Kotlin Coroutines | Structured asynchronous session, transport and video work | In-process state only | Apache-2.0 | App maintainers; each dependency PR |
| Kotlin Serialization JSON | Encode and decode NanoKVM API envelopes | NanoKVM requests and responses | Apache-2.0 | Protocol owner; each dependency PR |
| WebRTC SDK Android (`android-prefixed-stripped` 150.7871.04) | Receive-only NanoKVM H.264 WebRTC transport; software codecs are stripped and Android hardware decode is required | `INTERNET` and `ACCESS_NETWORK_STATE`; authenticated LAN WebSocket signaling, appliance-supplied STUN/TURN peers and ICE credentials/candidates, local interface/connectivity/address metadata, and inbound video only; no microphone, camera, location, analytics, or developer cloud. Native logging is discarded through a no-op `LS_NONE` logger and guarded by unit, runtime, minification, and logcat regression review. | Wrapper MIT; WebRTC BSD-3-Clause and bundled third-party licences; see [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) | Video/security owners; exact-version, native-ABI, licence, size, privacy/logging, and appliance review on every update |

There are no analytics, advertising, crash-reporting, cloud-storage, social,
location, or third-party authentication SDKs. Runtime dependencies must not be
added without updating this inventory and the security/data-flow documentation.

The stripped WebRTC AAR is pinned by SHA-256 in dependency verification. Version
150.7871.04 is 14,265,935 bytes and contains arm64-v8a, armeabi-v7a, x86, and
x86_64 native libraries. Their uncompressed total is 29,458,936 bytes
(7,686,440 / 4,471,732 / 8,770,716 / 8,530,048 bytes respectively). Record the
actual universal APK and per-ABI/AAB download-size change whenever it is updated;
the AAR size is evidence about the dependency, not a claim about delivered size.

The [October 2026 review](DEPENDENCY_REVIEW_2026-10.md) records the grouped
update's artifact verification, compatibility checks and APK/AAB size comparison.

The 0.3.13 release's refreshed strict verification also covers the previously
omitted Guava 33.7.1-jre parent POM, Jackson 2.22.2 base and text-dataformat parent
POMs, and JUnit 6.1.3 BOM Gradle module metadata. These describe versions already
present in the verified build dependency graph; no dependency version was
changed. Each added SHA-256 was independently computed from fresh HTTPS downloads
from both `repo.maven.apache.org` and `repo1.maven.org`, checked against the
published checksum sidecar, and compared with the cached artifact. This fills
missing verification entries without replacing checksums or relaxing strict
verification.

## November 2026 grouped update

The Gradle distribution and wrapper JAR match Gradle's published SHA-256
values. Android Gradle Plugin 9.5.0 supports API 37 with the Gradle 9.8.2 and
JDK 21 toolchain used for validation. The verification manifest retains the
1,828 previous checksums and adds 175 verified SHA-256 pins, including AAPT2 for
Windows, Linux, and macOS.
[Gradle release notes](https://docs.gradle.org/9.8.2/release-notes.html) ·
[Android Gradle Plugin compatibility](https://developer.android.com/build/releases/agp-9-5-0-release-notes)

OkHttp 5.5.1 blocks eight compromised eMudhra intermediates on affected
platforms. DataStore 1.3.0 updates Tracing to 2.0.1 and adds AtomicFU 0.28.0
to the resolved graph. Its Preferences protobuf artifact repackages protobuf-javalite
4.36.1. The resolved inventory covers 140 external coordinates, including
Kotlin Serialization 1.12.0. [OkHttp changelog](https://github.com/lysine-dev/okhttp/blob/parent-5.5.1/CHANGELOG.md) ·
[DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore#1.3.0) ·
[AndroidX release version catalog](https://android.googlesource.com/platform/frameworks/support/+/de4cda56f812357e582b3e1aa32bc737636614a2/gradle/libs.versions.toml) ·
[Kotlin Serialization release notes](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.12.0)

All four native libraries in the WebRTC Maven AAR match the wrapper release's
stripped Android asset byte for byte after the JNI filename change. The wrapper
licence and generated notices also match the bundled files. The native source
and wrapper revisions are recorded in the third-party notices.
[WebRTC wrapper release](https://github.com/webrtc-sdk/android/releases/tag/v150.7871.04)

The full grouped update reduced the unsigned release APK from 33,707,994 to
33,604,999 bytes and the AAB from 21,704,428 to 21,645,643 bytes. Bundletool
1.18.3 estimated these complete initial downloads for API 37, density 480, and
locale `en`, with one supported ABI per device specification.

| ABI | v0.3.14 baseline bytes | Updated bytes |
| --- | ---: | ---: |
| arm64-v8a | 6,543,136 | 6,515,514 |
| armeabi-v7a | 5,810,563 | 5,781,383 |
| x86 | 6,799,405 | 6,770,179 |
| x86_64 | 6,777,899 | 6,750,161 |

The strict local gate passed 618 JVM tests, release lint, APK/AAB and benchmark
assembly, and the packaged profile and SBOM checks. Release lint reported six
dependency advisories. On the API 37 x86_64 emulator, an in-place engineering
update retained a profile saved with DataStore 1.2.1. The screenshot regression,
offline licence tests, and native WebRTC peer-offer smoke test passed. The
WebRTC check covers native initialization and offer creation on the emulator.

## Build and test dependencies

| Component | Purpose | Shipped in APK | Licence |
| --- | --- | --- | --- |
| Android Gradle Plugin 9.5.0 and Gradle Wrapper 9.8.2 | Compile, shrink, verify and package Android variants | No | Apache-2.0 |
| Kotlin Compose and Serialization plugins | Kotlin compilation and generated serializers | No | Apache-2.0 |
| JUnit 4 and AndroidX Test/Espresso/Compose Test | JVM and device assertions | No | EPL-1.0 / Apache-2.0 |
| MockWebServer and OkHttp TLS | Deterministic protocol and certificate tests | No | Apache-2.0 |
| AndroidX Macrobenchmark and UI Automator | Out-of-process startup measurement against the minified benchmark APK | No | Apache-2.0 |
| AndroidX Baseline Profile Gradle plugin | Generate, merge, rewrite and package app-specific Baseline and Startup Profiles | No | Apache-2.0 |
| CycloneDX Gradle plugin 3.5.1 | Generate the verified release-runtime software bill of materials | No | Apache-2.0 |

The `benchmark` build type is a minified, profileable copy of `release` signed
with the standard local debug key. It exists only for controlled performance and
release-like testing; it is not a distributable artifact.

Macrobenchmark remains on the stable AndroidX 1.4.1 line. The Baseline Profile
Gradle plugin uses stable 1.5.0, which supports AGP 9's new DSL. This ends the
previous 1.5.0-beta01 exception; the proposed 1.6.0-alpha01 is unnecessary for
the current build and test platform.

Biometric 1.1.0 remains the current stable release but declares the obsolete
Fragment 1.2.5 transitively. The app therefore pins stable Fragment 1.9.1: its
Activity Result integration is required for the Android 17 local-network
permission launcher hosted by `FragmentActivity`. Keep this explicit override
until Biometric's stable dependency graph no longer selects the pre-Activity-
Result Fragment line.

The canonical release-runtime SBOM is generated at
`app/build/reports/cyclonedx/nanokvm-mobile.cdx.json`. Its ephemeral timestamp
and serial number are removed and JSON maps and collections are ordered so the
same verified dependency graph produces the same bytes.

## Update and removal policy

- Dependabot proposes grouped Gradle updates weekly. Maintainers review them
  deliberately; no bot or hosted workflow is treated as an approval signal,
  and updates are never merged automatically.
- Before accepting an update, review the resolved dependency diff and known
  vulnerabilities using current authoritative advisories. Retain the review
  with the next public-release record.
- A dependency update must pass the local JVM tests, release lint, strict
  dependency verification, and both release-like assemblies.
- Review release notes for privacy, permission, native-code, minimum-SDK and
  shrinker changes. Refresh verification metadata only after inspecting the
  resolved diff.
- Remove a dependency when its capability is unused, supplied safely by the
  platform, or maintainership/licensing no longer meets the project policy.
- Review this inventory at every tagged release even when no versions changed.
