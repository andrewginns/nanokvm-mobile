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
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:verifyReproducibleSbomMetadata
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

GitHub-hosted build/test lanes are intentionally not used as an unattended
release gate. Maintainers run this gate locally and retain the exact command,
source commit, dirty-tree status, output hashes, and relevant reports in a
private evidence archive. These unsigned outputs are not post-signing release
artifacts or checksums. The debug-signed benchmark and R8 mapping outputs must
also remain private test evidence. A public pre-release is created only through
the exact-source evidence and protected-signing process in `DISTRIBUTION.md`;
development build output is never uploaded as though it were publishable.

### Debug signing and update compatibility

Android's default debug keystore is selected from the JVM process's user-home
directory, not necessarily the interactive Windows account. Sandboxed shells,
Android Studio, and an ordinary terminal can therefore produce APKs with
different debug certificates even on one computer. A replacement install is
accepted only when package name and signing lineage match and the new
`versionCode` is not lower.

Before sharing any development APK, verify the actual file with SDK `apksigner
verify --print-certs`; this artifact result is authoritative. Retain
`:app:signingReport` when auditing how a variant selected its signing
configuration, but do not substitute that configuration report for inspecting
the delivered bytes. Debug keys are local developer identities, are not
recoverable release identities, and must not be treated as a production update
channel. Never solve a mismatch by asking a user to uninstall without first
explaining that uninstalling clears profiles, pins, and protected credentials.
Production distribution requires a dedicated protected release key and a
monotonically increasing version code.

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
renderable terminal state. A benchmark artifact is attributable only if it
names the four source test methods and contains the expected startup/frame
metrics from the same revision. The 2026-07-18 API 37 result met that structural
requirement but is now historical local emulator diagnostics, not evidence for
the latest checkpoint or a production baseline. Connect/console CUJs remain
separate open work.

## Required device and release evidence matrix

| Lane | Local validation and retained evidence |
| --- | --- |
| API 26 minimum | Run debug installation and full instrumentation locally for the current commit; no retained current-commit result is claimed here |
| API 35 / Android 15 | Run debug instrumentation locally for the current commit and retain a separate signed-candidate critical-journey result before release |
| API 36 / Android 16 | Run and retain current-commit instrumentation locally before release |
| API 37 | Current local app, native WebRTC, and non-secret process-restart results exist; profile generation/packaging and generated-source drift review also passed. Startup/frame Macrobenchmark measurement and `WebSocketIngressMemoryInstrumentedTest` were not run for the latest checkpoint. This does not replace API 35/36, physical ARM, real-appliance, real-Keystore, or signed-candidate evidence |
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

## Adversarial modernization checkpoint - 2026-07-19

The modernization working tree based on `782e3b0` was independently reviewed
for architecture/lifecycle, platform/security/cancellation, UI/accessibility/
performance, and post-remediation regressions, then passed its final local
implementation gate. This is unsigned, uncommitted development evidence, not a
release approval. Hosted CI remains intentionally disabled during active
development; these checks were run locally with JDK 21 and SDK 37.

- the complete strict Gradle matrix passed 361 tasks in 10 minutes 36 seconds:
  503/503 JVM tests across 75 suites, six zero-finding debug/release lint
  reports across `app`, `protocol`, and `video`, debug/release/benchmark APK
  assembly, release AAB, release-profile verification, and the canonical
  CycloneDX SBOM;
- source-matched profile generation completed in 8 minutes 26 seconds and
  produced 18,524 Baseline rules and 15,831 Startup rules; APK/AAB verification
  found the compiled profiles packaged at 11,631 bytes;
- the Android 17/API 37 x86_64 emulator passed 76/76 app instrumentation tests
  and 1/1 real-native WebRTC peer test, with no failures, errors, or skips; and
- after reinstalling the exact debug APK, a fresh cold launch resolved
  `MainActivity` and rendered the labelled empty connection catalogue in
  1,686 ms. The scoped log review found no fatal app exception, ANR, or
  StrictMode policy violation, and the crash buffer was empty. This timing is
  diagnostic only.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Self-signed debug APK | 53,223,502 | `46a462ecc5096f17aea5ac3bcb3ad102e0de068a93751fa2febcdeb03b1ffad8` |
| Unsigned minified release APK | 32,806,556 | `51d6ee9785b26aed94c3b52b751e396bd9b3a4cadd72aa06ca02362a9296e098` |
| Debug-signed minified benchmark APK | 32,687,758 | `18c934cded1bae1c6396abf159fbc3b83c7f0d3bf0c4186c822966a29b6f03a3` |
| Release AAB (development output) | 20,785,108 | `e330b1b44a775a68df84759199ad00e057fae9a75f01d689f0e829ea656f6bc7` |
| Canonical CycloneDX SBOM | 373,351 | `7512bf2c0fa5f4bab5b06e7e47a530e7f35d51409811b575dc4a8ac0e44805b3` |

The device suite uses deterministic local fixtures and does not disclose or
exercise a real NanoKVM credential or framebuffer. API 26/35/36, representative
physical ARM, TalkBack/switch/hardware-input review, real Keystore failure
paths, signed/minified candidate smoke, current real-appliance negotiation and
30-minute H.264/MJPEG endurance remain open release gates.

## Development update-lineage checkpoint - 2026-07-19

The previously shared development APKs (version codes 1 through 6) use one
certificate with SHA-256
`7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746`.
The sandbox's ambient JVM-home debug key instead produced certificate
`149d694db3d3b0d86849d1f99a570fb78c11627739494aa8c4e04eec6e276002`,
which Android correctly rejected as an update. Package identity and SDK levels
matched; signing lineage was the root cause.

Version 0.3.1 increases the Android version code to 8. The local
`scripts/build-development-update.ps1` builder explicitly supplies the
out-of-repository development key and delegates to
`scripts/verify-apk-upgrade.ps1`. The verifier rejects a wrong package, a
different signer, multiple signers, an invalid signature, or a version code
that does not strictly increase. A negative test rejected the old wrong-signer
code-7 build before handoff.

The API 37 emulator then installed the preceding 0.2.1/code-6 APK, launched it,
and created an app-private retention marker. `adb install -r` accepted the new
0.3.1/code-8 APK; the marker remained present, the profile catalogue rendered,
and the crash buffer remained empty. This proves development update and data
retention for that lineage; it is not production-signing or physical-device
evidence.

The source-matched focused Gradle gate then passed 129 tasks with strict
dependency verification: 297/297 app JVM tests across 43 suites, zero debug
lint findings, and the explicitly signed debug APK assembly.

| Artifact | SHA-256 |
| --- | --- |
| `NanoKVM-Mobile-0.3.1-update-compatible-debug.apk` | `a5fab8fd055094d5d3d901d2313528c5e28eb9cc8413125ef0c9e66bee0a7779` |

The private key is not in the repository. `AGENTS.md` records the handoff
invariant so future automated work does not silently select the sandbox key.

## Modernization remediation checkpoint — 2026-07-20

The dirty 0.3.2/code-9 working tree based on commit `782e3b0` completed the
prepared lifecycle, state, presentation, certificate, and transport remediation
tranche. This is local development evidence, not approval of a production
release. Hosted CI remains
intentionally disabled during active development.

The exact-source strict gate used JDK 21, SDK 37, strict dependency verification,
one worker, no parallel execution, no configuration cache, and in-process Kotlin
compilation. It passed 378 tasks in 6 minutes 43 seconds:

```powershell
.\gradlew.bat --no-daemon --no-parallel --no-configuration-cache `
    --no-problems-report --max-workers=1 `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    --dependency-verification=strict `
    test lintDebug lintRelease assembleRelease bundleRelease assembleBenchmark `
    :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark `
    :app:reproducibleSbom
```

- all 548 JVM tests across 83 suites passed with no failures, errors, or skips:
  331 tests/48 suites in `app`, 181/25 in `protocol`, and 36/10 in `video`;
- all six `app`, `protocol`, and `video` debug/release lint reports contained no
  findings;
- the Android 17/API 37 x86_64 emulator passed 76/76 app instrumentation tests
  and the video module's 1/1 EGL-backed native WebRTC regression, with no
  failure, error, or skip;
- the out-of-process 1/1 process-restart case killed the app process, relaunched
  a new process, restored a non-secret profile draft, and did not restore a
  password-like field;
- source profile generation passed and produced 18,528 Baseline rules and
  15,838 Startup rules. Verification found the release APK/AAB profiles
  packaged, with `baseline.prof` measuring 11,793 bytes; and
- the canonical CycloneDX SBOM task passed; the dependency graph was unchanged,
  so the normalized SBOM retained its prior deterministic hash.

The JVM WebSocket tripwire passed and continues to demonstrate that OkHttp
buffers a complete uncompressed fragmented message before listener delivery.
Production handshakes refuse compression, reject unsolicited extensions, and
terminate oversized input/direct-H.264 transports promptly. At the user's
direction, `WebSocketIngressMemoryInstrumentedTest` was **not invoked** during
this checkpoint. No Android heap/PSS result is claimed; representative physical
measurement and explicit disposition of the first uncompressed-message
allocation remain open.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Update-compatible development APK, `NanoKVM-Mobile-0.3.2-update-compatible-debug.apk` | 53,271,445 | `9e837f8bd1ac5076ebcb695810c4ed05e8ad792d79d87ad59c27440d96c16428` |
| Unsigned minified release APK | 33,010,324 | `21935b9726ec4f73135b5e35193ebc8dc7b0fbb4414ef2dc5a2099fc5b1e98f4` |
| Release AAB (development output) | 20,897,257 | `5d0996556858820e88fa1befcd3fc800347e807127cbc02d2953f605367b6521` |
| Debug-signed minified benchmark APK | 32,891,526 | `7468e1f45fec909e95eb790d19913f0ff4159605861120811cbc1961ce64f525` |
| Canonical CycloneDX SBOM | 373,351 | `3eeeb5a54d4dfb8ebf7e638234458f5ccc4e8114b001b76708fb3390cf8dc5f7` |

The development APK is `org.nanokvm.mobile` 0.3.2/code 9 and has the established
development certificate SHA-256
`7f2e5128eb089159536803992e381aa830d0e7a2d9601fac0048e3821ea02746`.
The verifier confirmed it can update the preceding 0.3.1/code-8 APK. On the API
37 emulator, `adb install -r` retained an app-private marker; the updated app
then cold-launched as the resumed `MainActivity`, and the crash buffer remained
empty. This proves update compatibility and data retention for this development
lineage only. It is not production-signing, physical-device, real-Keystore,
accessibility, appliance-endurance, or public-release evidence.

The hardened builder also rejected a keystore resolved inside the repository
before invoking Gradle. A second invocation against the existing final output
verified the artifact and reported an identical SHA-256 without overwriting it;
its fail-closed output branch requires a higher version/code and new path when
the existing versioned file has different bytes.
