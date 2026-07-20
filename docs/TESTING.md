# Testing and evidence

NanoKVM Mobile treats a test definition, a local passing run, and retained
release evidence as different states. `MODERNIZATION_AUDIT.md` reaches score 2
only when a repeatable result is attached or retained in the maintainer's local
evidence or release record.

## Test layers

- **JVM unit and integration tests:** password and API vectors, endpoint/trust
  policy, cookie origin, parser boundaries, HID reports, reconnect policy,
  command serialization, ViewModel attempt ownership, credential staging,
  profile codec, viewport math, WebRTC's bounded double-JSON signaling and
  candidate ordering/teardown, H.264 queues, fallback policy, and MJPEG parsing.
- **Android instrumentation:** launcher/profile-catalogue semantics, real
  DataStore corruption/reset, profile editing, certificate review, IME focus,
  gesture/control geometry, credential opt-in, configuration restoration,
  generation-bound action invalidation, and background/foreground behavior.
  The `video` module also creates a real EGL-backed native WebRTC peer and waits
  for a local offer, catching missing permissions, native-library loading
  failures, and process aborts. These tests normally run against `debug`; they
  do not satisfy the signed-production-candidate gate.
- **Macrobenchmark/profile generation:** the separate `macrobenchmark` module
  drives the target out of process. It generates versioned Baseline and Startup
  Profiles and defines cold startup/frame runs with no compilation and with the
  packaged Baseline Profile.
- **Manual Android/device assurance:** API behavior, accessibility services,
  hardware keyboard/mouse, Keystore behavior, process death, window resizing,
  display security, and signed/minified candidate smoke.
- **Real NanoKVM assurance:** trust, login, first frame, H.264/MJPEG, input,
  fallback/reconnect, and long-session behavior. GPIO/power tests require a
  disposable target and a separate explicit decision.

## Supported build runtime and local gate

Use JDK 21 and Android SDK platform 37. Java/Kotlin bytecode targets version 17.
Run all Gradle commands with strict dependency verification:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
```

This verifies repository tests and unsigned release-like artifacts. It does not
sign a production candidate or execute device/manual gates.

## Device commands

With a booted emulator or USB device:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:connectedDebugAndroidTest :video:connectedDebugAndroidTest
```

The `video` task is intentionally included: it creates a real native WebRTC
peer and is the regression gate for the process-abort failure previously seen
when selecting WebRTC. It does not connect to a NanoKVM or prove ICE/frame
continuity on an appliance. This normal device command does not include the
protocol module's hostile-ingress heap/PSS fixture; run and record that fixture
only as a separate, intentional memory investigation.

With a supported API 33+ profile-generation device, regenerate versioned rules
when startup or profile-catalog code changes:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:generateBaselineProfile :app:verifyReleaseProfiles
```

With an API 36+ benchmark device:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark :macrobenchmark:connectedBenchmarkAndroidTest
```

The focused non-secret process-restart regression can be run independently:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict `
    '-Pandroid.testInstrumentationRunnerArguments.class=org.nanokvm.mobile.macrobenchmark.ProcessRestartInstrumentedTest' `
    :macrobenchmark:connectedBenchmarkAndroidTest
```

Emulator results are diagnostic. Do not establish production performance
thresholds from an unlocked x86_64 emulator.

## Required release matrix

| Lane | Current local evidence | Required evidence before distribution |
| --- | --- | --- |
| API 26 | No retained current-commit result is claimed here | Run debug installation and instrumentation locally and retain the current-commit result |
| API 29 | No retained current-commit result is claimed here | Retain clipboard, share-target, IME, and lifecycle results for the current commit |
| API 31 | No retained current-commit result is claimed here | Retain clipboard, share-target, IME, and lifecycle results for the current commit |
| API 33 | No retained current-commit result is claimed here | Retain clipboard, share-target, IME, and lifecycle results for the current commit |
| API 34 | No retained current-commit result is claimed here | Retain clipboard, share-target, IME, and lifecycle results for the current commit |
| API 35 / Android 15 | No retained current-commit result is claimed here | Run instrumentation locally for the current commit and separately run the signed/minified candidate through profile creation, trust, credential, IME, rotation, video/input, reconnect, and background journeys |
| API 36 / Android 16 | No retained current-commit result is claimed here | Run instrumentation locally and retain the current-commit result |
| API 37 | The 2026-07-20 0.3.2 checkpoint passed 76/76 app instrumentation tests, 1/1 real-native WebRTC peer test, and 1/1 real process-restart test for a non-secret profile draft; exact scope and exclusions are recorded in `BUILD_VERIFICATION.md` | Repeat and retain the eventual release-source result; this emulator checkpoint remains diagnostic and does not replace the other API levels, physical ARM, real-appliance negotiation, real Keystore, or signed-production-candidate evidence |
| Representative physical ARM | Not automated | Required for signed-candidate smoke, startup/frame comparison, real Keystore, input/video, thermal/OEM behavior, memory, and power/network observations |
| Real NanoKVM | Requires local hardware and private credentials | Run at least 30 continuous minutes each on direct H.264 and forced/fallback MJPEG. If WebRTC is enabled and supported for the candidate, also run 30 minutes and force negotiation/ICE/decoder failure, retaining proof of the fresh WebRTC to H.264 to MJPEG chain; otherwise record WebRTC as an explicit capability-gated exclusion. Include keyboard, pointer, reconnect, foreground loss, frame continuity, memory, and input-release checks |

## Manual functional and accessibility gates

Retain device/build/OS/window/input details and pass/fail notes for:

- portrait, landscape, split-screen/resizable window, gesture navigation, and
  three-button navigation;
- compact, medium, and expanded widths, plus 200% font/display scale, long
  strings, RTL, dark/light theme, and IME open/closed;
- TalkBack reading/action order, Switch Access or equivalent switch-style
  traversal, hardware keyboard focus/activation, mouse/trackpad scrolling, and
  accessibility scanner/UI-check results;
- dedicated scroll-pad swipes in all four directions on the intended host OS;
  verify Shift+wheel left/right compatibility in both a browser with horizontal
  overflow and a spreadsheet, because NanoKVM has no native horizontal wheel axis;
- system biometric and device-credential success, cancellation, expiry,
  lockout/invalidation, deletion, rotation, background, and process death;
- profile DataStore unavailable/corrupt/reset behavior, including explicit
  confirmation that reset removes user-saved records, pins, and credentials
  before returning to an empty connection catalog;
- `FLAG_SECURE`, Recents preview, backup/restore-to-another-device, filesystem,
  Keystore, logcat, and observed-network-traffic checks; and
- signed/minified production candidate trust, login, first-frame, video input,
  credential save/unlock/remove, reconnect, and destructive-control guards.

TalkBack/switch, API 35, physical ARM, signed-production-candidate, and real
appliance long-session gates are open until their results are attached. Source
tests or emulator screenshots do not close them.

An unretained 2026-07-18 API 37 Material/adaptive diagnostic additionally exercised
system/light/dark appearance, 200% text, RTL, IME-open portrait, live
portrait-to-landscape resize into the expanded supporting pane, Android 17
local-network permission, reviewed private-certificate pinning, login, first
frame, and MJPEG console continuity against a trusted LAN NanoKVM. The emulator
was restored to its normal locale, text scale, and rotation afterwards. This is
useful implementation context, not a current-commit or release result: this
document does not retain its source commit, APK hash, signing digest, or artifact
archive. It also is not physical-device, assistive-technology, or endurance
evidence.

## Performance evidence

The repository contains generated Baseline and Startup Profile sources,
Macrobenchmark code for cold startup without compilation plus cold/warm/hot
startup with the Baseline Profile, frame timing, and a fully-drawn report tied
to the renderable terminal profile-catalog state. The evidence set is not
complete until it also includes:

- connect-to-first-rendered-video-frame, 30-second console interaction,
  reconnect/foreground recovery, WebRTC-to-H.264-to-MJPEG and
  H.264-to-MJPEG fallback CUJs;
- frame P50/P90/P95/P99, memory/PSS/leak checks, network volume, and power/thermal
  context on controlled physical ARM hardware; and
- three stable reference runs before regression thresholds become blocking.

An earlier 2026-07-18 API 37 emulator diagnostic contains the four named
compilation/startup modes and frame traces. The latest 2026-07-20 modernization
checkpoint regenerated and verified 18,528 Baseline and 15,838 Startup rules;
the four startup benchmark cases were intentionally skipped rather than rerun as
performance measurements. The older timing and jank values are diagnostic only;
production benefit and regression thresholds require controlled physical ARM
evidence.

## WebSocket ingress evidence

`OkHttpWebSocketIngressBehaviorTest` is an intentional dependency tripwire. A
raw peer controls slow RFC 6455 fragment boundaries and confirms that OkHttp
5.4.0 accumulates the complete uncompressed message before listener delivery.
It also proves production handshakes omit compression, unsolicited negotiation
fails, and an RSV1 frame with a declared 8 MiB body fails from its header without
waiting for that body. Input and direct-H.264 integration tests verify exact
boundary acceptance, HID release/command rejection, immediate H.264 cancellation,
and normal fallback notification.

`WebSocketIngressMemoryInstrumentedTest` can repeat an 8 MiB slow-fragment case
on Android and emit Java-heap and PSS samples. It was deliberately **not
invoked** for the 2026-07-20 checkpoint, so no Android memory sample or
termination result is claimed. When it is intentionally run, retain API level,
ABI, device/emulator, build identity, samples, and termination result. A
representative physical run and explicit availability budget remain required
before accepting the residual uncompressed first-allocation risk.

This fixture is intentionally opt-in and must not be added to the routine device
gate. Its exact class-filtered command is:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict `
    '-Pandroid.testInstrumentationRunnerArguments.class=org.nanokvm.protocol.WebSocketIngressMemoryInstrumentedTest' `
    :protocol:connectedDebugAndroidTest
```

## Evidence record

For each manual or benchmark run, retain:

- source commit and dirty-tree status;
- artifact version, signing certificate digest, and APK/AAB SHA-256;
- device model/ABI, Android/API level, security patch, refresh rate, navigation
  mode, window size, font/display scale, and IME/input devices;
- NanoKVM hardware/application version, configured transport, network topology,
  and whether the endpoint used system trust or a reviewed leaf pin;
- exact command or manual script, start/end time, result, logs/traces/screenshots,
  and redactions; and
- finding owner, disposition, and rerun link.

Release-specific artifact handling is defined in `RELEASE_CHECKLIST.md` and
`DISTRIBUTION.md`.
