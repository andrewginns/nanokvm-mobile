# Build verification and performance artifacts

Use JDK 21. Java/Kotlin bytecode remains version 17. The release gate uses the
dependency hashes in `gradle/verification-metadata.xml`. All maintainer
validation commands must retain strict verification and must not refresh that
file implicitly.

Outputs in a local `build/` directory are ephemeral evidence. Record the source
commit and retain the command result before using them to close an audit or
release gate.

## Release-like build and SBOM

Run the focused build gate with:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
```

Important outputs are:

- `app/build/outputs/apk/release/app-release-unsigned.apk` — minified unsigned
  release candidate;
- `app/build/outputs/bundle/release/app-release.aab` — minified unsigned app
  bundle with the release profile metadata;
- `app/build/outputs/apk/benchmark/app-benchmark.apk` — minified, profileable,
  locally debug-signed benchmark target;
- `app/build/reports/cyclonedx/nanokvm-mobile.cdx.json` — canonical CycloneDX
  1.6 JSON for `releaseRuntimeClasspath`;
- `app/build/outputs/mapping/<variant>/` — R8 mapping, seeds, usage and effective
  configuration.

GitHub-hosted build/test lanes and public artifact uploads are intentionally not
configured during active development. Maintainers run this gate locally and
retain the exact command, source commit, dirty-tree status, output hashes, and
relevant reports in a private evidence archive. These unsigned outputs are not
post-signing release artifacts or checksums. The debug-signed benchmark and R8
mapping outputs must also remain private test evidence. Before the first binary
release, the release process must create an exact-source archive and checksum
manifest without assuming that development build output is publishable.

### Debug signing and update compatibility

Android's default debug keystore is selected from the JVM process's user-home
directory, not necessarily the interactive Windows account. Sandboxed shells,
Android Studio, and an ordinary terminal can therefore produce APKs with
different debug certificates even on one computer. A replacement install is
accepted only when package name and signing lineage match and the new
`versionCode` is not lower.

Before sharing any development APK, record `:app:signingReport` and verify the
actual file with SDK `apksigner verify --print-certs`. Debug keys are local
developer identities, are not recoverable release identities, and must not be
treated as a stable update channel. Never solve a mismatch by asking a user to
uninstall without first explaining that uninstalling clears profiles, pins, and
protected credentials. Production distribution requires a dedicated protected
release key and a monotonically increasing version code.

The SBOM normalization removes the per-run timestamp and random serial number,
then canonically orders object keys and collections. When changing dependencies,
generate it twice from the same verified graph and compare SHA-256 hashes before
publishing it with a release.

`scripts/verify-reproducible-release.ps1` performs a no-build-cache, two-clean-
build repeatability smoke in the same worktree. It compares the unsigned APK,
AAB, canonical SBOM, and selected R8 evidence. Distributable artifacts and the
R8 mapping/configuration reports are byte-compared. R8 resource-reachability,
seed, and removed-code diagnostics are retained for review but are outside this
claim because their traversal/reachability explanations can vary between clean
builds even when the distributable bytes and deobfuscation mapping are identical.
This is useful local evidence, but it is not the two-isolated-environment proof
required before making a public reproducibility claim; that release gate remains
in `DISTRIBUTION.md`.

## Baseline and Startup Profiles

With a booted API 33+ emulator or supported physical device, regenerate and
package the app profiles with:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:generateBaselineProfile :app:verifyReleaseProfiles
```

`BaselineProfileGenerator` records the launcher-to-profile-catalog startup CUJ,
then separately opens and exits the add-profile flow as a repeatable catalog
interaction. The first CUJ is emitted to both
`app/src/main/generated/baselineProfiles/baseline-prof.txt` and
`app/src/main/generated/baselineProfiles/startup-prof.txt`; the navigation CUJ
is Baseline-only so the Startup Profile stays focused. These generated sources
are versioned and must be refreshed when startup or profile-catalog code changes.

`verifyReleaseProfiles` requires app-owned rules in both text profiles, requires
the Startup set to be a strict subset of the Baseline set, and assembles both
minified release artifacts. It verifies non-empty `.prof` and `.profm` entries
below Android's 1.5 MB compiled-profile limit in the APK and AAB, and requires
the APK entries to be uncompressed. The APK paths are
`assets/dexopt/baseline.prof` and `baseline.profm`; the AAB paths are under
`BUNDLE-METADATA/com.android.tools.build.profiles/`. Startup Profile rules are
consumed by R8 for DEX layout, so there is no separate `startup.prof` inside the
APK.

## Device tests and macrobenchmark

With a booted API 36+ emulator or device:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:connectedDebugAndroidTest :video:connectedDebugAndroidTest
.\gradlew.bat --no-problems-report --dependency-verification=strict -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark :macrobenchmark:connectedBenchmarkAndroidTest
```

The macrobenchmark module launches the minified, profileable `benchmark` target
out of process without requiring a NanoKVM. It defines three iterations for cold
startup without compilation and for cold, warm, and hot startup with the
packaged Baseline Profile required. Every method records startup and frame
timing. Traces and metric results are copied beneath
`macrobenchmark/build/outputs/connected_android_test_additional_output/`.

The app calls the fully-drawn reporting API after the profile catalog reaches a
renderable terminal state. A benchmark artifact is current only if it names the
four source test methods and contains the expected startup/frame metrics from the
same revision. The current API 37 result meets that structural requirement but
remains local emulator diagnostics, not a production baseline. Connect/console
CUJs remain separate open work.

## Required device and release evidence matrix

| Lane | Local validation and retained evidence |
| --- | --- |
| API 26 minimum | Run debug installation and full instrumentation locally for the current commit; no retained current-commit result is claimed here |
| API 35 / Android 15 | Run debug instrumentation locally for the current commit and retain a separate signed-candidate critical-journey result before release |
| API 36 / Android 16 | Run and retain current-commit instrumentation locally before release |
| API 37 | A current local app plus native WebRTC instrumentation result exists; future candidates must also run profile generation/packaging, generated-source drift review, and startup/frame Macrobenchmark locally. This does not replace API 35/36, physical ARM, real-appliance, or signed-candidate evidence |
| Representative physical ARM | Required before release for cold-start/frame comparison, input/video CUJs, thermal state and OEM behavior |

## Local verification snapshot — 2026-07-18

The final modernization checkpoint produced the following local, unsigned
evidence:

- the strict gate completed successfully in 1 minute 47 seconds across 374
  tasks, including 143/143 JVM tests, release lint, debug APK, minified APK/AAB, benchmark
  APK, canonical SBOM, and profile verification;
- API 37 instrumentation passed 25/25 tests; profile generation passed 2/2;
  the four Macrobenchmarks passed and produced 12 Perfetto traces;
- the generated sources contain 19,756 Baseline rules and 17,295 Startup rules;
  APK/AAB package verification measured the compiled profile at 10,630 bytes;
  and
- two serialized clean, no-daemon/no-build-cache builds completed in 2 minutes
  16 seconds and 2 minutes 12 seconds; every compared artifact was byte-identical.

The matching repeatability SHA-256 values are:

| Artifact | SHA-256 |
| --- | --- |
| Unsigned release APK | `B78C93E0ADCD68B1F9D98687034C409C58EF052856399A2631182A974EC21373` |
| Release AAB | `1486A8F2695EF451815BF1FEE34F9E7957C23E563263C4BD49BE69789CA9F889` |
| Canonical SBOM | `8A7A3D713D9F8F25FB184CEFDC01BD2341141EAA0268CD7DD83C0C5AB7E56013` |
| R8 effective configuration | `15C062AF77DE4C9F9BE4815F80538C44BD7CA61B32CAB31C48F5942293864716` |
| R8 mapping | `E4F55280339ED7099DAF749254E88C90E5C0B5395E39B861EB7DF9DA674E9D9F` |

The same API 37 emulator also completed a diagnostic connection to a trusted
LAN NanoKVM: contextual Android 17 local-network grant, private-certificate
review and pinning, login, first MJPEG frame, native keyboard with the view pad
docked above the IME, portrait-to-landscape stream continuity, and expanded
supporting-pane controls all passed. That journey exposed an obsolete
Fragment 1.2.5 transitive dependency in stable Biometric; pinning stable
Fragment 1.8.9 corrected the Activity Result permission-launch crash, and the
identical platform flow then passed without a fatal log entry. The app never
stored the test password in repository files or diagnostic logs.

On the unlocked API 37 x86_64 userdebug emulator, diagnostic TTID/TTFD medians
in milliseconds were: cold no compilation 739.238/776.032, cold Baseline
741.901/821.976, warm Baseline 186.889/186.889, and hot Baseline TTID
75.414 (no TTFD sample). The device had four cores and about 4 GB RAM; CPU
locking and sustained-performance mode were unavailable. Frame and jank values
were poor and variable, so these results establish execution coverage only—not
a performance threshold or a Baseline Profile benefit claim.

Raw JSON, XML, traces, UI trees, and screenshots are retained locally under the
ignored `qa-artifacts/modernization/material-you-final-20260718/` and
`qa-artifacts/material-you/` directories. They are not hosted or
signed-candidate release records.

## Local parity implementation checkpoint — 2026-07-18

This newer checkpoint covers the WebUI-parity implementation built on baseline
commit `cd7fef3`. The working tree intentionally remained dirty because the user
requested a baseline commit before implementation, not a parity release commit.
It therefore records development evidence and does not approve distribution.

- the strict, no-configuration-cache gate passed 374 tasks: 451/451 JVM tests
  across 72 suites, `lintRelease`, debug/release/benchmark APKs, the release AAB,
  Baseline/Startup Profile verification, and the normalized CycloneDX SBOM;
- the API 37 emulator passed the complete 57/57 instrumentation suite with zero
  failures, errors, or skips;
- the generated sources still contain 19,756 Baseline rules and 17,295 Startup
  rules; the final packaged profile measured 11,547 bytes; and
- after direct installation, the debug APK cold-launched `MainActivity` as the
  resumed foreground Activity with no `AndroidRuntime` fatal. The measured
  4,568 ms emulator launch is diagnostic and is not a startup budget.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Self-signed debug APK | 53,646,637 | `e2ea5d4906a10814c69a12a9b4d3b88b9ed8559cc200dfb3b6033d73443c8638` |
| Unsigned minified release APK | 32,630,290 | `a21e1446b4738eaccb69379728124d889ffa78d2f985fdcf54e1487f88690193` |
| Release AAB | 20,487,539 | `fcdf41f7b19ed2460c9123e0014fe5df6dbb48b22a5e21baeaae53350258c62e` |
| Canonical SBOM | 382,835 | `61f38792cbfc0621281e2dd63ffe8609381d990edad2cfa22fdc0fe2d486ab09` |

The debug APK certificate subject is `C=US, O=Android, CN=Android Debug`; its
SHA-256 digest is
`7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746`.
The real-appliance destructive, update, password, network-handoff, WebRTC
endurance, PicoClaw, compatibility-floor, physical-ARM, and signed-candidate
cases remain open in `WEBUI_PARITY.md` and `APPLIANCE_TEST_PLAN.md`.

## WebRTC crash regression checkpoint — 2026-07-19

Selecting WebRTC was reproduced as a native `SIGABRT` on API 37. WebRTC's network
thread encountered a `SecurityException` because `ACCESS_NETWORK_STATE` was absent;
its JNI boundary treated the pending exception as a fatal invariant. The video
library now contributes that normal permission through manifest merging.

The audit also found that the prefixed/stripped WebRTC AAR supplies no consumer
R8 rules. Release and benchmark builds had removed or renamed Java classes and
callbacks that `libjingle_peerconnection_so` resolves by exact JNI name. The video
library now retains that JNI surface for every minified consumer.

- the strict test/lint/package gate passed 364 tasks, including 451/451 JVM tests
  across 72 suites, release lint, and debug/release/benchmark APK assembly;
- API 37 passed 57/57 app instrumentation tests and 1/1 native WebRTC runtime test;
  the latter created a real EGL surface, peer connection, receive-only transceiver,
  and local offer, and the cleared crash buffer remained empty;
- release and benchmark mappings retain `HardwareVideoDecoderFactory`,
  `VideoDecoderFactory`, `PeerConnection.Observer`, and `SdpObserver` under their
  original names. Release DEX retains `createDecoder`, `getSupportedCodecs`, and
  the observer/SDP callback methods; and
- the replacement debug APK installed and cold-launched with
  `ACCESS_NETWORK_STATE` granted and no Java or native fatal entry.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Self-signed debug APK | 52,989,634 | `0eaf9aba7ba40ae0a34e6545b8f8edafdb5cc6d7a40b62dae9c9d2d70867b339` |
| Unsigned minified release APK | 32,794,146 | `870d34faeb78aad1df3db6e84cde5047260dd69b02ed2f823ad5221df8c22e97` |
| Debug-signed minified benchmark APK | 32,691,728 | `42eb03e811b78d60dd813383056d04d9dcabb3695d0f8acbf841d1d25d03dd36` |

Real-appliance WebRTC signaling, ICE, rendered-frame continuity, fallback, and
endurance remain separate gates because this regression test intentionally needs
no NanoKVM credentials or remote framebuffer.

## Public-source checkpoint — 2026-07-19

The pre-publication 0.3.0 (version code 7) source snapshot passed the complete
local source-publication gate. This is unsigned development evidence, not a
binary release approval; every production-candidate gate remains open.

- the strict no-configuration-cache build completed 328 tasks in 9 minutes 20
  seconds and passed 463/463 JVM tests across 73 suites, `lintRelease`, minified
  unsigned APK/AAB assembly, benchmark assembly, profile verification, and the
  canonical CycloneDX SBOM;
- the packaged identity is `org.nanokvm.mobile`, version `0.3.0` (code 7),
  minSdk 26 and target/compile SDK 37;
- generated sources contain 19,756 Baseline rules and 17,295 Startup rules, and
  the packaged compiled profile measured 11,568 bytes;
- a fresh Android 17/API 37 x86_64 emulator passed 59/59 app instrumentation
  tests and 1/1 EGL-backed native WebRTC test in 4 minutes 23 seconds, with no
  failures, errors, or skips; and
- the WebRTC-scoped log review found no native network-monitor, renderer,
  factory, ICE URL/candidate, or interface-identifier entry, and the crash buffer
  was empty. A separate 3,119 ms cold launch resolved `MainActivity`, rendered
  the labelled empty connection catalogue and 0.3.0 About dialog, and produced
  no fatal or unhandled app exception. The timing is diagnostic only.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Unsigned minified release APK | 32,794,594 | `fe23e3e8334c657684f1ba971860968d6ace185f32399793847f5e21ab645522` |
| Release AAB | 20,754,969 | `8faca4216928f2c26f099038d7f449f92422c93e5d6b20a3f5ef2ac4c826434c` |
| Canonical SBOM | 382,835 | `8ec5cb9178885116d15a5e513fc9fbcbdf87a07563d845e1acfbe7e25df1529b` |

The app/device results exercise deterministic local fixtures and reveal no real
appliance address, credential, or framebuffer. They do not close real NanoKVM,
current-commit API 26/35/36, physical ARM, accessibility, endurance,
destructive-control, full third-party notice packaging, or signed production
candidate evidence.

Emulator timing is diagnostic only. Performance baselines and regression
thresholds must come from the controlled physical ARM lane; record device,
build, thermal state, data/network fixture, iterations and both compilation
modes with the retained release evidence.
