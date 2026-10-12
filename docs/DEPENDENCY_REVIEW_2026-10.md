# October 2026 dependency review

This review accompanies [PR #15](https://github.com/andrewginns/nanokvm-mobile/pull/15),
starting from Dependabot head `3b7af946e4a2e1f2eb19e7e6eda463bff6e9b5f5`
and main `9c54ad28313017abb2a8164b012a25ebf866b311`.

## Reviewed changes

| Family | Previous | Reviewed version |
| --- | --- | --- |
| Gradle | 9.7.0 | 9.8.1 |
| Android Gradle Plugin | 9.3.1 | 9.4.1 |
| Kotlin Compose/Serialization plugins and stdlib | 2.4.10 | 2.4.21 |
| Compose BOM | 2026.06.01 | 2026.09.00 |
| AndroidX Core | 1.19.0 | 1.19.1 |
| Fragment | 1.8.9 | 1.9.1 |
| OkHttp, MockWebServer and OkHttp TLS | 5.4.0 | 5.5.0 |
| WebRTC prefixed stripped Android | 144.7559.09 | 150.7871.01 |
| Baseline Profile Gradle plugins | 1.5.0-beta01 | 1.5.0 |
| CycloneDX Gradle plugin | 3.4.0 | 3.5.1 |

The stable Baseline Profile 1.5.0 plugins replace Dependabot's proposed
1.6.0-alpha01. Stable 1.5.0 retains the required AGP 9 DSL support, ending the
documented beta exception. Macrobenchmark remains 1.4.1.
[AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/benchmark#1.5.0)

Gradle 9.8.1 fixes the worker/daemon authentication vulnerability affecting
9.7.0, plus the daemon deserialization and failed TLS repository handling
issues listed in its patch notes. The distribution checksum and wrapper JAR
were independently compared with Gradle's published SHA-256 values.
[Security advisory](https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8),
[Gradle release notes](https://docs.gradle.org/9.8.1/release-notes.html)

AGP 9.4 supports API 37 and the selected Gradle/JDK versions. Kotlin 2.4.21's
fully tested compatibility matrix ends at Gradle 9.7 / AGP 9.3.1; upstream
permits newer versions, so the local gate remains necessary evidence for this
combination. Validation uses JDK 21.0.10 and SDK 37.
[AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
[Kotlin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)

The runtime graph resolves Compose UI/Foundation 1.12.1, Adaptive 1.3.0,
Material 3 1.4.0, Kotlin stdlib 2.4.21 and Okio 3.18.1. OkHttp's new ECH/DNS
features are opt-in and are not enabled by this app. Existing HTTPS trust,
redirect and retry policies remain in place.
[OkHttp changelog](https://github.com/square/okhttp/blob/master/CHANGELOG.md)

## Artifact and licence review

The dependency verification update preserves all 1,365 existing trusted hashes
and the strict verification configuration. All 459 new SHA-256 entries were
independently checked: 329 against published checksums and 130 against freshly
downloaded artifact bytes from Google Maven, Maven Central and the Gradle
Plugin Portal. Gradle module artifact aliases are
resolved using their upstream module metadata. No new repository or broad
trusted-artifact rule is introduced.

The bundled runtime licence index covers all 138 resolved external components.
Of these, 136 use Apache-2.0, with the existing external protobuf and WebRTC
licences documented separately. The index hash is updated in the build.
The device packaging test pins the reviewed index's 8,558 bytes and SHA-256;
the About-screen WebRTC notice labels now identify version 150.7871.01.

WebRTC 150 retains minSdk 21, all four native ABIs, and the existing manifest
permissions. The app's minSdk remains 26. Its arm64 and x86_64 native libraries
retain 16 KB ELF load alignment. Ten API classes used by the runtime were
compared with the previous AAR; existing signatures remain available.
The AAR grows by 491,167 bytes. Exact source revisions and notices are recorded
in [third-party notices](../THIRD_PARTY_NOTICES.md); the WebRTC, wrapper MIT and
IJG notice files are unchanged byte-for-byte at the new upstream revisions.

## Validation scope

The final source passed the complete local gate with strict dependency
verification, followed by the minified process-restart check:

```powershell
.\gradlew.bat --no-daemon --no-problems-report --no-configuration-cache --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:verifyReproducibleSbomMetadata :app:connectedDebugAndroidTest :video:connectedDebugAndroidTest
.\gradlew.bat --no-daemon --no-problems-report --no-configuration-cache --dependency-verification=strict -Pandroid.testInstrumentationRunnerArguments.class=org.nanokvm.mobile.macrobenchmark.ProcessRestartInstrumentedTest :macrobenchmark:connectedBenchmarkAndroidTest
```

| Check | Result |
| --- | --- |
| JVM tests | 618 passed: app 391, protocol 191, video 36 |
| App instrumentation | 130 passed |
| Native-video instrumentation | 5 passed |
| Minified process-restart instrumentation | 1 passed |
| Release lint | 0 errors; only the permitted `GradleDependency` freshness warning for unchanged Macrobenchmark 1.4.1 |
| Release APK/AAB and benchmark assemblies | Passed |
| Packaged Baseline/Startup Profiles | Passed: 18,523 Baseline and 16,133 Startup rules |
| Reproducible runtime SBOM | Passed |
| Release APK 16 KB ZIP alignment | Passed with `zipalign -c -P 16 4` |

All reported tests completed without failures, errors or skips. The full gate
completed in 8m 2s and the final minified restart check in 27s. The review fixed
the initial strict-verification failure and a stale licence-asset assertion;
neither strict verification nor the assertion was relaxed.

The repository deliberately uses local verification and has no hosted CI
workflow. Dependabot update runs are not application build/test checks.
No public APK, release, deployment or version-code change is part of this PR.

Android validation uses a fresh API 37 x86_64 emulator with 16 KB pages and
Gboard. Native instrumentation exercises JNI initialization, EGL setup and
offer creation; it does not establish received-video behavior on an appliance.
API 26, physical arm64 devices, OEM keyboards and real NanoKVM sessions are
outside this run's coverage.

## Release size comparison

Both versions were built with Gradle 9.8.1, JDK 21.0.10 and strict dependency
verification. The isolated baseline uses main `9c54ad2` with only the patched
wrapper and two missing Kotlin Reflect 2.4.10 build-classpath checksum entries;
both checksums were independently verified against Maven Central artifact bytes.
The baseline application source and dependency versions are unchanged. These
measurements compare the complete grouped update and review fixes, so the whole
increase cannot be attributed to WebRTC alone.

| Measured unsigned release archive | Baseline bytes | Candidate bytes | Increase |
| --- | ---: | ---: | ---: |
| Universal APK | 33,171,471 | 33,706,990 | 535,519 (1.61%) |
| AAB | 21,145,786 | 21,699,495 | 553,709 (2.62%) |

[Bundletool 1.18.3](https://github.com/google/bundletool/releases/tag/1.18.3),
verified against its published SHA-256, generated full APK sets with the same
temporary engineering signing identity. `get-size total` used fixed synthetic
specifications: API 37, density 480, locale `en`, and one supported ABI. MIN and
MAX were equal in every result.

| Estimated complete initial download | Baseline bytes | Candidate bytes | Increase |
| --- | ---: | ---: | ---: |
| arm64-v8a | 6,352,826 | 6,542,670 | 189,844 (2.99%) |
| armeabi-v7a | 5,710,619 | 5,810,096 | 99,477 (1.74%) |
| x86 | 6,635,533 | 6,798,930 | 163,397 (2.46%) |
| x86_64 | 6,607,983 | 6,777,436 | 169,453 (2.56%) |

These are device-specification estimates, not physical-device measurements or
guaranteed Play download sizes. Production signing and Play processing may alter
exact byte counts. The engineering APK sets were not installed or distributed.
Archive sizes and per-ABI compressed/uncompressed native ZIP contributions were
also measured directly with Python 3.11.9 `zipfile`; native payload sizes exclude
ZIP headers/padding and are not presented as download sizes.

Measured candidate artifact SHA-256 values, rebuilt from the reviewed source
before committing this review record (later builds may embed a different VCS
revision):

- APK: `82b101b7253e62f394c7a865088839abef86081564ad5aa6acc3b803400b38de`
- AAB: `6bda4b139a2425c3124b149f326563e63cb1853b5bde692c5343cdf9b9638176`

The baseline APK and AAB SHA-256 values are
`b7cf8f132df9d541f2bc08513a1416424309aaf2033ec331161a592ca27e243f` and
`044400d76f083a82b75d471601529d0b4458cb9c79a621adcc9da7c9d71076eb` respectively.
Local review evidence retains the exact source manifests, baseline overlay,
successful baseline build log, tool provenance, native-entry measurements, raw
per-ABI MIN/MAX results and input/output hashes.
