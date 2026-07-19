# Dependency inventory

NanoKVM Mobile deliberately keeps its dependency surface small. Gradle resolves
only from Google Maven, Maven Central, and the Gradle Plugin Portal; repository
declarations in individual projects are rejected. Versions are centralized in
`gradle/libs.versions.toml`, artifacts are checked against
`gradle/verification-metadata.xml`, and the Gradle distribution is protected by
its published checksum. GitHub-hosted CI is intentionally disabled during
active development; dependency and build validation is run locally.

## Runtime dependencies

| Component | Purpose | Data or permissions used | Licence | Owner / review |
| --- | --- | --- | --- | --- |
| AndroidX Core, Activity, Fragment, Lifecycle and ViewModel | Android lifecycle, Activity/permission integration, biometric host compatibility, and observable UI state | App lifecycle and platform permission state only; no data leaves the device | Apache-2.0 | App maintainers; each dependency PR |
| Jetpack Compose UI, Foundation, Material 3 and Material 3 Adaptive | Native Android UI, accessibility semantics, current-window size classification, and supporting-pane layout | User interaction, window geometry, and rendered local state only | Apache-2.0 | App maintainers; each dependency PR |
| AndroidX DataStore Preferences | Persist non-secret NanoKVM profiles and public certificate pins | App-private storage; explicitly excluded from backup | Apache-2.0 | App maintainers; each dependency PR |
| AndroidX Biometric | Authorize Android Keystore access to an explicitly saved password | System biometric/device-credential prompt; the library does not receive the NanoKVM password | Apache-2.0 | Security owner; each dependency PR |
| AndroidX ProfileInstaller | Install the app's packaged Baseline Profile on Android versions and distribution paths without Play-managed profile installation | App package metadata and local ART profile state only | Apache-2.0 | Performance owner; regenerate and review at each release |
| OkHttp | HTTPS, REST, WebSocket and MJPEG transport to the user-selected NanoKVM origin | `INTERNET`; NanoKVM address, login request, in-memory session cookie, video and input | Apache-2.0 | Protocol owner; each dependency PR |
| Kotlin Coroutines | Structured asynchronous session, transport and video work | In-process state only | Apache-2.0 | App maintainers; each dependency PR |
| Kotlin Serialization JSON | Encode and decode NanoKVM API envelopes | NanoKVM requests and responses | Apache-2.0 | Protocol owner; each dependency PR |
| WebRTC SDK Android (`android-prefixed-stripped` 144.7559.09) | Receive-only NanoKVM H.264 WebRTC transport; software codecs are stripped and Android hardware decode is required | `INTERNET` and `ACCESS_NETWORK_STATE`; authenticated LAN WebSocket signaling, appliance-supplied STUN/TURN peers and ICE credentials/candidates, local interface/connectivity/address metadata, and inbound video only; no microphone, camera, location, analytics, or developer cloud. Native logging is discarded through a no-op `LS_NONE` logger and guarded by unit, API 37 runtime, minification, and logcat regression review. | Wrapper MIT; WebRTC BSD-3-Clause and bundled third-party licences; see `THIRD_PARTY_NOTICES.md` | Video/security owners; exact-version, native-ABI, licence, size, privacy/logging, and appliance review on every update |

There are no analytics, advertising, crash-reporting, cloud-storage, social,
location, or third-party authentication SDKs. Runtime dependencies must not be
added without updating this inventory and the security/data-flow documentation.

The stripped WebRTC AAR is pinned by SHA-256 in dependency verification. Version
144.7559.09 is 13,822,983 bytes and contains arm64-v8a, armeabi-v7a, x86, and
x86_64 native libraries. Their uncompressed total is 29,058,732 bytes
(7,527,464 / 4,478,208 / 8,531,724 / 8,521,336 bytes respectively). Record the
actual universal APK and per-ABI/AAB download-size change whenever it is updated;
the AAR size is evidence about the dependency, not a claim about delivered size.

## Build and test dependencies

| Component | Purpose | Shipped in APK | Licence |
| --- | --- | --- | --- |
| Android Gradle Plugin and Gradle Wrapper | Compile, shrink, verify and package Android variants | No | Apache-2.0 |
| Kotlin Compose and Serialization plugins | Kotlin compilation and generated serializers | No | Apache-2.0 |
| JUnit 4 and AndroidX Test/Espresso/Compose Test | JVM and device assertions | No | EPL-1.0 / Apache-2.0 |
| MockWebServer and OkHttp TLS | Deterministic protocol and certificate tests | No | Apache-2.0 |
| AndroidX Macrobenchmark and UI Automator | Out-of-process startup measurement against the minified benchmark APK | No | Apache-2.0 |
| AndroidX Baseline Profile Gradle plugin | Generate, merge, rewrite and package app-specific Baseline and Startup Profiles | No | Apache-2.0 |
| CycloneDX Gradle plugin | Generate the verified release-runtime software bill of materials | No | Apache-2.0 |

The `benchmark` build type is a minified, profileable copy of `release` signed
with the standard local debug key. It exists only for controlled performance and
release-like testing; it is not a distributable artifact.

Macrobenchmark remains on the stable AndroidX 1.4.1 line. The Baseline Profile
Gradle plugin alone uses 1.5.0-alpha07 because the 1.5 line is the first to
support AGP 9's new DSL; stable 1.4.1 rejects AGP 9 application modules during
configuration. Reassess this narrow exception when AndroidX 1.5 reaches stable.

Biometric 1.1.0 remains the current stable release but declares the obsolete
Fragment 1.2.5 transitively. The app therefore pins stable Fragment 1.8.9: its
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
  with a production-candidate record once public builds are contemplated.
- A dependency update must pass the local JVM tests, release lint, strict
  dependency verification, and both release-like assemblies.
- Review release notes for privacy, permission, native-code, minimum-SDK and
  shrinker changes. Refresh verification metadata only after inspecting the
  resolved diff.
- Remove a dependency when its capability is unused, supplied safely by the
  platform, or maintainership/licensing no longer meets the project policy.
- Review this inventory at every tagged release even when no versions changed.
- If hosted automation is introduced later, pin every third-party action to a
  reviewed full commit and keep its permissions and artifact retention narrow.
