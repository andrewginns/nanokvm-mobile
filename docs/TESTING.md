# Testing

Use JDK 21 and Android SDK platform 37. The project emits Java 17 bytecode.
Run Gradle with the checked-in wrapper and strict dependency verification; do
not refresh `gradle/verification-metadata.xml` as a side effect of testing.

## Local build gate

Before requesting review, run:

```powershell
.\gradlew.bat --no-problems-report --no-configuration-cache --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:verifyReproducibleSbomMetadata
```

The metadata tasks deliberately disable Gradle's configuration cache for this
gate. It runs JVM tests and release lint, builds the unsigned release APK/AAB
and benchmark targets, verifies packaged Baseline Profiles, and creates the
canonical CycloneDX SBOM. Important outputs include:

- `app/build/outputs/apk/release/app-release-unsigned.apk`;
- `app/build/outputs/bundle/release/app-release.aab`;
- `app/build/outputs/apk/benchmark/app-benchmark.apk`;
- `app/build/reports/cyclonedx/nanokvm-mobile.cdx.json`; and
- `app/build/outputs/mapping/release/`.

These are local engineering artifacts. They are not production-signed APKs and
must not be uploaded as a public release.

For a fast source-build check, use the debug command in the
[README](../README.md#build-from-source).

## Android tests

With a booted emulator or USB device:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:connectedDebugAndroidTest :video:connectedDebugAndroidTest
```

The video test creates a native WebRTC peer and catches native-library or
process-abort regressions. It does not prove an appliance video session.

Choose Android versions based on the change. At minimum, exercise the oldest
supported behavior affected by the change and the current target API. A public
APK also needs a representative physical phone; an emulator does not prove
hardware input, Keystore, decoder, thermal, or OEM behavior.

## Baseline Profiles and performance

Regenerate versioned Baseline and Startup Profiles when launcher or profile
catalog journeys change:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:generateBaselineProfile :app:verifyReleaseProfiles
```

With an API 36+ benchmark device:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark :macrobenchmark:connectedBenchmarkAndroidTest
```

Run the focused process-death/profile-draft regression when startup, profile
generation, or saved-state behavior changes:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict '-Pandroid.testInstrumentationRunnerArguments.class=org.nanokvm.mobile.macrobenchmark.ProcessRestartInstrumentedTest' :macrobenchmark:connectedBenchmarkAndroidTest
```

Treat emulator timings as diagnostic. Performance claims need repeated runs on
a named physical device with the APK hash, build mode, thermal state, and
measurement method recorded.

`scripts/verify-reproducible-release.ps1` compares two clean builds in one
worktree. It is a repeatability smoke test, not proof of isolated-environment
reproducibility.

## Real NanoKVM checks

For ordinary changes, run the relevant safe cases in
[APPLIANCE_TEST_PLAN.md](APPLIANCE_TEST_PLAN.md). A public APK should cover on a
real NanoKVM:

- certificate review or saved-pin validation, login, and first video frame;
- H.264 and MJPEG, plus WebRTC when selected or affected;
- keyboard, pointer, HID release, foreground recovery, reconnect, and fallback;
- saved-credential behavior when authentication/storage changed; and
- every changed persistent or destructive feature on a disposable or
  recovery-capable target.

Do not infer support for an untested hardware model, firmware version, Android
version, accessibility service, or destructive operation. Record the actual
scope and any exclusions.

## Manual UI and privacy checks

For changed screens, check compact and expanded widths, portrait/landscape or
resizing, keyboard open/closed, 200% font/display scale, light/dark theme,
focus order, target size, contrast, RTL layout, semantic labels, and an
accessibility service where practical.

For security/privacy-sensitive changes, verify the merged manifest and Network
Security Config, cleartext denial, trust/pin behavior, log redaction, secure
screen behavior, backup exclusions, local-network denial/revocation, and that
no credential or private endpoint appears in retained evidence.

## Optional hostile-ingress diagnostic

The protocol module contains an opt-in Android memory fixture for an 8 MiB slow
fragmented WebSocket message. It is intentionally excluded from the routine
gate:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict `
    '-Pandroid.testInstrumentationRunnerArguments.class=org.nanokvm.protocol.WebSocketIngressMemoryInstrumentedTest' `
    :protocol:connectedDebugAndroidTest
```

Run it only as an intentional heap/PSS investigation on an appropriate target.

## Evidence record

For a device, appliance, benchmark, or release run, record:

- source commit and dirty-tree status;
- artifact version/code, APK SHA-256, and signing-certificate digest;
- device model/ABI and Android/API version;
- NanoKVM hardware and application version, transport, and trust mode;
- exact command or manual cases, result, and redacted evidence; and
- any excluded or failed scope and its disposition.

Release packaging and signing are described in
[Distribution](DISTRIBUTION.md) and the
[release checklist](RELEASE_CHECKLIST.md).
