# Android and Kotlin modernization audit

This is the durable scorecard for applying
`android-kotlin-modernization-audit-guide.md` to NanoKVM Mobile. It records the
current implementation separately from evidence that must still be executed.
The restrained Material 3/Material You and adaptive-console implementations are
in place; all-screen token, adaptive, accessibility, physical-device, and
release evidence remains incomplete. Broader visual experimentation is deferred
in favour of maintainability, security, reliability, and testable ownership.

## Checkpoint and scoring

| Field | Value |
| --- | --- |
| Baseline audit | 2026-07-17, commit `7f3f6e7` (`Initial NanoKVM Mobile app`) |
| Current checkpoint | 2026-07-20 adversarial remediation working tree based on `782e3b0`; the 0.3.2/code-9 exact-source strict gate, regenerated profiles, API 37 app/video/process-restart tests, update-lineage proof, and cold-launch diagnostics passed against this working tree |
| Application | GPL-3.0-or-later, direct-LAN Android client for a user-selected NanoKVM |
| Platform | minSdk 26, compile/target SDK 37, JDK 21 build runtime, Java/Kotlin bytecode 17 |
| Modules | `app`, `protocol`, `video`, and non-shipping `macrobenchmark` |
| Field telemetry | None; channel-provided aggregate health metrics are unavailable before distribution |

Scores follow the guide:

- **0 — absent or unknown:** no reliable evidence, or the requirement fails;
- **1 — partial:** implementation or evidence exists, but a material gap remains;
- **2 — evidenced:** a repeatable repository control/test or retained result
  demonstrates the requirement; and
- **N/A:** the component does not exist, with a reassessment trigger recorded.

A source change, generated test, or old build artifact does not close a device or
release gate. P0–P3 priority is independent of score. No confirmed P0 defect is
known at this checkpoint. P1 items below block public distribution.

## Evidence index

| Key | Evidence available in the repository | Limits at this checkpoint |
| --- | --- | --- |
| E1 — platform/build | [app build](../app/build.gradle.kts), [settings](../settings.gradle.kts), [version catalogue](../gradle/libs.versions.toml), [wrapper properties](../gradle/wrapper/gradle-wrapper.properties), [manifest](../app/src/main/AndroidManifest.xml), and [NSC](../app/src/main/res/xml/network_security_config.xml); the local JDK 21 strict gate passed at this checkpoint | Repeat and retain the gate against the eventual release-source freeze, then retain signed-candidate evidence |
| E2 — supply chain | [verification metadata](../gradle/verification-metadata.xml), [dependency inventory](DEPENDENCIES.md), [SBOM task](../app/build.gradle.kts), and [build verification recipe](BUILD_VERIFICATION.md); the local strict gate verified the dependency graph and generated the canonical SBOM | Verification is local and hosted automation is not part of the release process; signed artifact, vulnerability review, post-signing checksums/provenance, and isolated-environment comparison remain open |
| E3 — architecture/state | [AppContainer](../app/src/main/java/org/nanokvm/mobile/AppContainer.kt), [AppViewModel](../app/src/main/java/org/nanokvm/mobile/ui/AppViewModel.kt), [ProfileRepository](../app/src/main/java/org/nanokvm/mobile/data/ProfileRepository.kt), [typed console feature contracts](../app/src/main/java/org/nanokvm/mobile/runtime/ConsoleBackend.kt), [session draft owner](../app/src/main/java/org/nanokvm/mobile/ui/screens/ConsoleSessionDraftOwner.kt), [automation state owner](../app/src/main/java/org/nanokvm/mobile/ui/screens/AutomationDialogController.kt), and [backend](../app/src/main/java/org/nanokvm/mobile/runtime/NanoKvmConsoleBackend.kt) | Focused contracts, semantic FIFO app notices, separate latest status/sequenced action feedback, explicit snapshot handles, and session-bound owners reduce coupling. JVM tests cover cancellation-ignoring input/video callbacks, late authentication, transient storage failure, and 64 repeated lifecycle cycles; an API 37 out-of-process restart restores only non-secret draft state. Real Keystore and physical Surface/HID evidence remain open |
| E4 — trust/credentials | [CertificateInspector](../protocol/src/main/java/org/nanokvm/protocol/CertificateInspector.kt), [EndpointTrustPreflight](../protocol/src/main/java/org/nanokvm/protocol/EndpointTrustPreflight.kt), [SavedCredentialStore](../app/src/main/java/org/nanokvm/mobile/security/SavedCredentialStore.kt), and [request-ID coordinator](../app/src/main/java/org/nanokvm/mobile/security/CredentialAuthenticationCoordinator.kt) | Old/new pin comparison, connect-once, and explicit replacement are implemented; real Keystore/traffic/device recovery results remain open |
| E5 — bounds/control/reconnect | [protocol sources](../protocol/src/main/java/org/nanokvm/protocol), [video sources](../video/src/main/java/org/nanokvm/video), [ReconnectPolicy](../app/src/main/java/org/nanokvm/mobile/runtime/ReconnectPolicy.kt), and [ControlCommandGate](../app/src/main/java/org/nanokvm/mobile/runtime/ControlCommandGate.kt); REST execution/read/decode is internally dispatched, cancellation cancels active calls and inspection sockets, retries/redirects and WebSocket compression are disabled, clipboard/share text and tokens are rejected before retained use at their bounds, unused input server messages are discarded, direct-H.264 oversize cancels immediately, and raw slow/fragmented plus compressed-header fixtures characterize the pinned OkHttp behavior | OkHttp still allocates a complete uncompressed WebSocket message before application limits; API 37/physical heap-PSS and long real-appliance results remain open |
| E6 — UI/adaptive/accessibility | [NanoKvmApp](../app/src/main/java/org/nanokvm/mobile/ui/NanoKvmApp.kt), [Material implementation record](MATERIAL_YOU_IMPLEMENTATION.md), [UI sources](../app/src/main/java/org/nanokvm/mobile/ui), and [instrumentation tests](../app/src/androidTest/java/org/nanokvm/mobile) provide source/test implementation for the supporting pane, exclusive overlays, lifecycle-aware FIFO notice presentation, polite status regions, resource-backed runtime/feature messages, lazy catalogs, gesture alternatives, and non-secret restoration; the current API 37 run passed 76 app tests plus the process-restart case | Source implementation is not all-screen or assistive-technology proof; TalkBack, switch, API 35/36, translated resources, long-string/breakpoint matrices, hardware input, physical ARM, and signed-candidate results remain open |
| E7 — profiles/performance | [ReportDrawnWhen use](../app/src/main/java/org/nanokvm/mobile/ui/NanoKvmApp.kt), [macrobenchmark module](../macrobenchmark), [generated profiles](../app/src/main/generated/baselineProfiles), [lazy WebRTC provider](../app/src/main/java/org/nanokvm/mobile/runtime/WebRtcRuntimeProvider.kt), and [build verification](BUILD_VERIFICATION.md); provider tests prove Auto/H.264/MJPEG do not initialize the native WebRTC runtime, and current source-matched generation produced 18,528 Baseline plus 15,838 Startup rules with packaged profiles verified | Emulator timing is diagnostic; repeat console CUJs, physical ARM, and stable trends against a release source freeze |
| E8 — public assurance | [privacy](../PRIVACY.md), [security design](SECURITY.md), [threat model](THREAT_MODEL.md), [distribution](DISTRIBUTION.md), and [release checklist](RELEASE_CHECKLIST.md) | MASTG/manual results and observed-traffic/privacy reconciliation are required, not yet claimed |
| E9 — execution record | The current working tree passed 548 JVM tests across 83 suites, six zero-finding debug/release lint reports, the 378-task strict build/package/profile/SBOM gate, 76 app instrumentation tests plus one native WebRTC and one process-restart test on API 37, source profile generation, a code-8 to code-9 data-retaining update, and a fresh cold-launch crash review | Raw results are local/ignored and the installable APK uses only the development identity; repeat against the release source freeze and retain an approved signed candidate before crediting a public release. `WebSocketIngressMemoryInstrumentedTest` was not invoked and no Android heap/PSS result is claimed |
| E10 — manual/release gates | [testing matrix](TESTING.md) and [release checklist](RELEASE_CHECKLIST.md) | API 35, physical ARM, signed candidate, 30-minute H.264/MJPEG, TalkBack/switch, and field evidence remain explicitly open |

## Platform and build

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| PLAT-01 | 1 | P1 partial | Minimum API 26 and target/compile API 37 are configured, but only API 37 has current local device evidence. Current-commit API 26/35/36 runs plus signed-candidate critical journeys remain open. [E1, E6] |
| PLAT-02 | 1 | P1 partial | Edge-to-edge, safe drawing, `adjustResize`, and IME-aware controls exist. Cutout, both navigation modes, window resize, and every top-level state need device evidence. [E6] |
| PLAT-03 | 2 | P2 evidenced | JDK 21, Gradle 9.6.1 with checksum, central exact catalogue, bytecode 17, and SDK 37 are recorded in the local build recipe. Review quarterly. [E1] |
| PLAT-04 | 1 | P1 partial | Release enables R8 and resource shrinking and an unsigned minified artifact can be built. Signed/minified trust/login/video/input/credential smoke is open. [E1, E9] |
| PLAT-05 | 1 | P2 partial | Optimizing defaults and narrow project rules generate mapping/usage/configuration locally. Retention and test-justified rule review are not yet a release record. [E1, E2] |
| PLAT-06 | 2 | P2 evidenced | Exact versions, restricted repositories, wrapper URL validation, and distribution checksum are repository-enforced. [E1] |
| PLAT-07 | 1 | P2 partial | Strict verification metadata, wrapper validation, and a controlled local update policy exist. Automated dependency review is absent; a retained manual vulnerability review remains a production-candidate gate. [E2] |
| PLAT-08 | 2 | P2 evidenced | `DEPENDENCIES.md` records purpose, data/permissions, licence, owner/review, exception, and removal policy. [E2] |

## Architecture and Kotlin concurrency

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| ARCH-01 | 1 | P1 partial | Repository, VM, focused feature-controller, high-rate input, and Surface boundaries are explicit. Console feature actions still bypass `AppViewModel`, and the concrete runtime adapter remains broad. [E3] |
| ARCH-02 | 1 | P1 partial | DataStore and credential stores are authoritative; Ready/Unavailable/Corrupted states and partial deletion outcomes are explicit. Corruption blocks writes until consequence-confirmed reset, a real Android corruption/reset test passes, and deterministic transient read/write failures preserve the last good state and recover through bounded retry. Real Keystore failure results remain open. [E3, E6] |
| ARCH-03 | 1 | P1 partial | `AppUiState`, backend state, and automation state are immutable. Ownership is still divided among `AppViewModel`, the saveable console shell, and session-bound feature owners, so complete screen-level UDF is not claimed. [E3] |
| ARCH-04 | 2 | P1 evidenced | `NanoKvmApp`, console state, and automation state use `collectAsStateWithLifecycle()`; replay-free operator output uses `repeatOnLifecycle(STARTED)`. [E3] |
| ARCH-05 | 1 | P1 partial | `SavedStateHandle` restores non-secret screen state, one saveable `ConsoleOverlay` prevents mutually exclusive panels from coexisting, and Tailscale navigation is an acknowledged memory-only handoff. Generation-bound owners retain bounded drafts through recreation while excluding secrets; the API 37 process-restart test proves a non-secret profile draft survives actual process replacement without restoring a password-like field. The full API/physical resize and secret-state matrix remains open. [E3, E6] |
| ARCH-06 | 2 | P1 evidenced | `AppViewModel` is factory-created from the application container; credential coordinator retains only typed non-secret request metadata and IDs, never a UI callback or secret. [E3, E4] |
| ARCH-07 | 1 | P1 partial | Repository, credential, trust, connection, video, and complete REST execute/read/decode work dispatch below callers; debug builds enable StrictMode thread/VM diagnostics. Retained device traces and exhaustive slow-boundary proof remain open. [E3–E5] |
| ARCH-08 | 1 | P1 partial | Scopes/executors are owned and the backend exposes `closeAndAwait()`. Synchronous rejection/HID release, cancellation-ignoring stale input/video callbacks, replacement-session fencing, and 64 background/reconnect/foreground/close cycles pass deterministic tests. Physical transport/decoder and long-running shutdown evidence remain open. [E3] |
| ARCH-09 | 1 | P1 partial | REST and certificate-inspection cancellation close active work; settings retain the last success and retry with bounded backoff; feature controllers rethrow cancellation; and profile/authority/generation fencing covers reconnect, input, video, and late authentication publication. Deterministic adverse races pass, while real network suspension and device cancellation remain open. [E3, E5] |
| ARCH-10 | 1 | P2 partial | Input server messages have no consumer and are bounded then discarded instead of entering an unused flow. Replay-free operator output uses a bounded incremental buffer with conflated, at-most-once-per-frame publication; motion/frame coalescing and ordered key/button policies remain explicit. Complete stress evidence across every producer remains open. [E3, E5] |
| ARCH-11 | 2 | P2 evidenced | UI/session models are immutable, mutable flows are hidden with `asStateFlow()`, and updates are atomic. [E3] |
| ARCH-12 | 2 | P2 evidenced | The four modules retain clear delivery/ownership reasons. Unsafe token bridges and the 68-method default-no-op sink were replaced by flat typed feature contracts whose required production operations have no silent defaults; feature-specific exact-snapshot handles reject stale/foreign/lookalike values. No DI framework, generic controller hierarchy, per-action use cases, or feature-module ceremony was added. [E1, E3, E7] |
| ARCH-13 | 1 | P1 partial | Broad `configChanges` is removed; `SavedStateHandle` and Compose/API 37 tests cover non-secret restoration while reset live trust/session work. The out-of-process API 37 case verifies a new PID and safe draft restoration after `am kill`. Physical Surface/HID restoration and the wider device matrix remain open. [E1, E3, E6] |
| ARCH-14 | 2 | P2 evidenced | Profile management is local; console streaming/control is foreground-only and remote commands are neither persisted nor replayed. [E3, E5] |

Target flow remains:

```text
Compose state/actions -> AppViewModel -> profile/credential repositories
                                  \-> console runtime -> protocol/video
Remote input + Surface -> narrow non-blocking ports -> console runtime
repositories + runtime StateFlow -> AppUiState -> lifecycle-aware Compose
```

## UI behavior, adaptive use, and accessibility

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| UI-01 | 1 | P3 partial | Source-controlled fallback/dynamic colour, typography, shapes, semantic roles, and fixed console colours exist. Spacing, elevation, and motion are not a coherent token system and many raw values remain. [E6] |
| UI-02 | 1 | P2 partial | Native dialogs, sheets, back handling, and IME behavior exist; signed-device lifecycle/window verification remains. [E6] |
| UI-03 | 1 | P1 partial | Loading/terminal profile-catalog, transient unavailable, corruption, reconnect, connection, and video states exist. Retain storage/adverse UI results. [E3, E6] |
| UI-04 | 1 | P1 partial | Profile save/delete synchronously claim one mutation slot, expose Saving/Deleting state, disable controls, and reject duplicate submission; settings remain repository-authoritative and failed writes are tested. Repeated-tap/progress/recovery and announcement evidence is incomplete across privileged features. [E3, E5] |
| UI-05 | 2 | P1 evidenced | Power/reset/GPIO/Ctrl-Alt-Delete have consequence confirmation; UI confirmation is ephemeral and cleared on destination/connection/generation change, while runtime command-key claims, serialization, generation invalidation, and tests prevent duplicate/replay execution. [E5, E6] |
| UI-06 | 1 | P2 partial | Local API 37 diagnostics passed the profile catalogue at 200% text and RTL. Static labels plus runtime, feature, certificate, and app notices render from Android resources through typed mappings. No translated resource set exists; translated/long strings, every top-level state, and narrow-window extremes remain open. [E6] |
| UI-07 | 1 | P2 partial | Navigation is single-flow; selected non-secret screen state, bounded drafts, viewport geometry, and the profile draft are restorable through configuration and process replacement without restoring secrets. Wider resize/fold and device evidence remain open. [E3, E6] |
| UI-08 | 2 | P3 evidenced | The application is cohesively Compose-based; no migration program is needed. [E6] |
| ADAPT-01 | 2 | P2 evidenced | `currentWindowAdaptiveInfo()` and current constraints select compact bottom sheet, compact-landscape/medium side overlay, and expanded supporting-pane behavior. [E6] |
| ADAPT-02 | 1 | P1 partial | Decision tests cover selected height/width boundaries and an API 37 console test resizes while retaining Surface identity. Every screen, breakpoint edge, long-string/font-scale matrix, and fold/posture remains unproved. [E6] |
| ADAPT-03 | 2 | P2 evidenced | `SupportingPaneScaffold` models the primary remote video plus persistent contextual controls from current-window adaptive information; decision and instrumentation tests cover selection and Surface identity. [E6] |
| ADAPT-04 | 1 | P1 partial | API 37 restoration covers viewport dimensions/zoom/pan, non-replayed Fit, automation/editor state, stale-action invalidation, and an out-of-process non-secret profile draft; resolution replacement exercises pan/zoom callbacks against the new transform. Fold-like dimensions and the complete context matrix remain open. [E6] |
| ADAPT-05 | 2 | P1 evidenced | No fixed orientation, aspect-ratio, or resizability restriction is present. [E1] |
| ADAPT-06 | 1 | P2 partial | Streaming is foreground-only; repeated resize/multi-window lifecycle results remain open. [E6] |
| ADAPT-07 | 1 | P1 partial | Touch, IME, mouse/trackpad, and keyboard paths exist. Hardware traversal, shortcuts, scroll, and focus evidence is open. [E6] |
| ADAPT-08 | 2 | P2 evidenced | A permanent Material supporting-pane scaffold changes one/two-pane directives without moving the `RemoteViewport` composition slot; instrumentation asserts the same `TextureView` and unchanged attach/detach counts across the breakpoint. [E6] |
| ADAPT-09 | 2 | P3 evidenced | Expanded width assigns a bounded supporting pane while retaining a usable video main pane and full-width safe-area input strip; compact modes remain intentionally transient. [E6] |
| ADAPT-10 | 1 | P1 partial | Viewport-transform/Surface-generation tests and one earlier API 37 portrait-to-landscape appliance journey retain the stream across resize. Folded posture, physical-device crop, and full control evidence remain open. [E3, E6] |
| A11Y-01 | 1 | P1 partial | Custom controls expose semantics/actions; complete role/state/value and manual TalkBack evidence is open. [E6] |
| A11Y-02 | 1 | P2 partial | Semantics tests exist; review merged/unmerged trees for duplicates. [E6] |
| A11Y-03 | 1 | P1 partial | Target-size assertions and WCAG checks cover selected fixed palette pairs, including corrected pill foregrounds. Icon/focus/disabled/error/dynamic states and scanner/manual review remain open. [E6] |
| A11Y-04 | 1 | P1 partial | The profile catalogue remains operable at 200% text in a local API 37 diagnostic; repeat every top-level console, trust, credential, and safety state before release. [E6] |
| A11Y-05 | 0 | P1 open | No retained TalkBack, switch-style, or hardware-keyboard traversal result. [E10] |
| A11Y-06 | 2 | P1 evidenced | Instrumentation invokes semantic alternatives for pointer movement/clicks, four-direction scrolling, and moving the view pad; existing tests invoke dedicated pan/zoom/fit controls and assert resulting input/transform effects. [E6] |
| A11Y-07 | 1 | P1 partial | Shared polite live regions announce async status in administration, phase-3, operator, automation, offline-update, and PicoClaw surfaces, with semantics tests. Repetition and focus behavior still need actual assistive-technology evidence. [E6] |
| A11Y-08 | 1 | P2 partial | Semantics assertions exist; scanner and retained manual assistive-technology results remain open. [E6, E10] |

## Performance, reliability, and efficiency

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| PERF-01 | 1 | P1 partial | Launch, connect-to-first-frame, 30-second console, reconnect/foreground recovery, and fallback CUJs are named. Device/data state, budgets, and retained runs are open. [E7] |
| PERF-02 | 1 | P1 partial | Macrobenchmark defines cold no-compilation and cold/warm/hot Baseline-required methods; `ReportDrawnWhen` uses the terminal profile-catalog state. Earlier API 37 diagnostics executed them, but the current working tree repeated profile generation/package verification rather than performance measurement; no physical baseline exists. [E7] |
| PERF-03 | 1 | P1 partial | Earlier API 37 diagnostics produced 12 `FrameTimingMetric` traces. They are not a current physical trend; current-source P50/P90/P95/P99 evidence and thresholds are not retained. [E7, E9] |
| PERF-04 | 1 | P1 partial | A minified profileable benchmark target exists. Representative/slower physical ARM evidence is open. [E1, E7] |
| PERF-05 | 1 | P1 partial | Current versioned Baseline Profile generation and APK/AAB package verification passed. Physical benefit evidence remains open. [E7] |
| PERF-06 | 1 | P2 partial | Current focused Startup Profile generation passed and is consumed by R8. Independent DEX-layout evidence remains open. [E7] |
| PERF-07 | 1 | P1 partial | Known file/Keystore/network and REST decode work dispatch off Main; debug StrictMode is enabled, WebRTC is lazy for non-WebRTC selections, and a fresh API 37 cold launch produced no StrictMode policy violation after credential-directory resolution moved behind the IO boundary. Retained physical traces remain open. [E3, E7, E9] |
| PERF-08 | 1 | P2 partial | Virtual-media/WOL, HID/autostart, and script catalogs use one stable-keyed lazy list per surface with 512/1,024-item bounded-composition tests; operator output publishes once per frame and parent console state no longer observes viewport zoom. No recomposition measurement justifies score 2. [E6, E7] |
| PERF-09 | 2 | P2 evidenced | No unjustified stability annotations or speculative performance wrappers are present. [E6] |
| PERF-10 | 1 | P1 partial | Parser/decoder queues, retained operator output/editor text, accepted clipboard/share text, profile fields/pins, certificate display metadata, and session tokens are bounded; hot text validation avoids encoded copies. WebSocket compression is refused, slow fragmentation is characterized, and rejection terminates transport. The Android heap/PSS fixture was deliberately not invoked at this checkpoint; the first complete uncompressed allocation, physical memory/leaks, low-memory, and long-session behavior remain open. [E5] |
| PERF-11 | N/A | — | No durable/background job is required. Reassess if background work is introduced; verify traffic stops today. |
| PERF-12 | 1 | P1 partial | Heartbeat ownership and no wake lock are evident. Network volume, metered policy, background stop, and physical power evidence are open. [E5] |
| PERF-13 | 0 | P2 open/conditional | No field exposure or channel health metrics exist. Review aggregate channel metrics only after distribution; do not mark passing before data exists. [E10] |
| PERF-14 | 0 | P2 open | No three-run controlled trend or active relative-plus-absolute threshold exists. [E7] |

Provisional thresholds remain report-only until three stable physical reference
runs exist. Candidate policy: investigate/fail startup above both 10% and 50 ms,
connect-to-frame above both 10% and 100 ms, frame P95 above both 10% and 2 ms,
or memory above both 10% and 20 MiB. Recalculate rather than activating these
numbers if device/run variance shows they are inappropriate.

## Security and privacy

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| SEC-01 | 1 | P1 partial | Threat/privacy inventory covers profile, credential, token, framebuffer, HID/text/IME, GPIO, build inputs, purpose, recipient, retention, and deletion. Observed traffic/device reconciliation is open. [E8] |
| SEC-02 | 2 | P2 evidenced | Manifest permissions are limited to Internet, Android 17 local-network access, WebRTC connectivity-state observation, and biometric/fingerprint prompt access; the compatibility fingerprint permission ends at API 27. No location, storage, camera, microphone, notification, or background-service permission is declared. [E1, E8] |
| SEC-03 | 1 | P1 partial | Private/no-backup storage, explicit deletion outcomes, secure display, and no telemetry are strong. Backup, logcat, capture, secret-lifetime, and device evidence is open. [E3, E4, E8] |
| SEC-04 | 1 | P2 partial | AES-GCM, AAD, Keystore auth, atomic storage, and fail-closed recovery exist. Real invalidation/expiry/hardware/deletion tests are open. [E4] |
| SEC-05 | 2 | P2 evidenced | Deprecated AndroidX Security Crypto APIs are not used. [E2, E4] |
| SEC-06 | 2 | P1 evidenced | Manifest and NSC deny cleartext; legacy HTTP profiles and AP onboarding/AP-only APIs are removed; clients disable retries and redirects; 3xx/login/upload tests prevent credential/body replay; self-signed HTTPS still uses pre-secret inspection and explicit leaf pinning. Signed traffic capture remains a release gate. [E1, E4, E5] |
| SEC-07 | 1 | P2 partial | Exact leaf pinning fails closed; mismatch review compares old/new identities and makes connect-once versus stored replacement explicit. Expiry-warning and retained device recovery rehearsal remain open. [E4, E8] |
| SEC-08 | 1 | P2 partial | System biometric/device credential protects opt-in passwords with a documented ten-second window. Real device lifecycle/expiry/invalidation evidence is open. [E4] |
| SEC-09 | 2 | P1 evidenced | One Activity is app-exported for the launcher and a strict `ACTION_SEND` `text/plain` share target; no other app component is exported in source. Retain signed merged-manifest evidence per release. [E1, E8] |
| SEC-10 | 1 | P1 partial | The direct plain-text share route validates action/type/payload, opens the same destination-bound preview, and never auto-types. Retained cold-start, process-death, malformed-intent, and supported-API evidence remains open. [E1, E6, E8] |
| SEC-11 | N/A | — | No PendingIntent. Reassess on notification/widget/service introduction. |
| SEC-12 | N/A | — | No WebView. Reassess if web content is embedded. |
| SEC-13 | 1 | P1 partial | No APK secret is trusted; token origin, firmware assumptions, command serialization, and no replay/retry are documented/tested. Real adverse appliance evidence is open. [E4, E5, E8] |
| SEC-14 | N/A | — | Remote attestation adds no meaningful boundary for this open-source local client without a developer backend. Reassess on backend entitlement. |
| SEC-15 | 2 | P1 evidenced | Exact dependencies, repository restrictions, wrapper and artifact hashes, dependency inventory, SBOM task, and minified variants exist. Signed release retention and vulnerability review remain TEST-10. [E1, E2] |
| SEC-16 | 1 | P1 partial | Threats map to applicable MASTG v2 IDs and dispositions. Actual signed/minified device results are not retained. [E8] |
| SEC-17 | 1 | P1 partial | Privacy, data inventory, manifest, and dependencies are reconciled in docs; observed traffic and distribution-channel disclosure remain open. [E8] |

The ephemeral inspection trust manager is deliberately narrow: metadata-only,
no credential/token/application request, independent hostname/date validation,
and no reuse for application traffic. Parser bounds must likewise be described
accurately: OkHttp's complete WebSocket allocation occurs before the app check.

## Testing and release assurance

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| TEST-01 | 1 | P1 partial | The current working tree passes 548/548 JVM tests across 83 suites, covering protocol, transforms, bounds, attempts, retries/cancellation, credentials, semantic notices, feature owners, state restoration, layout/contrast, control gates, teardown, and lazy WebRTC. The wider device-backed matrix remains open. [E3–E6, E9] |
| TEST-02 | 1 | P1 partial | MockWebServer/parser tests, transient DataStore failure/recovery tests, cancellation-ignoring input/video callbacks, repeated lifecycle cycles, and a real Android DataStore corruption/reset test pass. Real Keystore, hostile-ingress Android memory, and real-network suspension evidence remain open. [E3–E6] |
| TEST-03 | 1 | P1 partial | Debug UI journeys exist. Signed/minified trust/login/first-frame/failure/reconnect/credential journeys are open. [E6, E10] |
| TEST-04 | 0 | P3 deferred | Broad visual snapshots are deferred; retain only high-value structural compact/expanded, theme, 200% text, and RTL cases later. [E6] |
| TEST-05 | 1 | P1 partial | Semantics tests exist; analysis tools plus manual TalkBack, keyboard, and switch-style traversal are open. [E6, E10] |
| TEST-06 | 1 | P1 partial | API 37 recreation plus real process replacement cover non-secret draft restoration, while deterministic tests cover transient storage failure, auth cancellation/late results, cancellation-ignoring callbacks, stale sessions, and 64 repeated background/reconnect/foreground/close cycles. Physical lifecycle, real slow/offline endpoints, Surface/HID restoration, and real Keystore behavior remain open. [E3, E6] |
| TEST-07 | 1 | P1 partial | Current-source API 37 profile generation and package verification passed. Four-mode Macrobenchmark methods have earlier emulator diagnostics but were not rerun as measurements for this working tree; controlled physical results and trends do not exist. [E7, E9] |
| TEST-08 | 1 | P1 partial | The current local API 37 run passed 76/76 app instrumentation tests, the video module's 1/1 native WebRTC crash regression, and the macrobenchmark module's 1/1 out-of-process restart test; the crash buffer remained empty after the final update cold launch. Current-commit API 26/35/36 and representative physical ARM remain explicit open gates. [E6, E9, E10] |
| TEST-09 | 1 | P1 partial | Risk-based tests and MASTG mapping exist; signed-release execution/dispositions are open. [E8, E10] |
| TEST-10 | 0 | P1 open | No approved signed production candidate or signed/minified device smoke evidence exists. [E10] |

## P0/P1 risk register

| Risk | IDs | Current status | Closure evidence |
| --- | --- | --- | --- |
| Current source and generated artifacts can diverge from passing outputs | PLAT-04, TEST-01, TEST-07 | Locally closed at checkpoint; release-source repetition/retention open | Clean JDK 21 strict gate and applicable device runs retained against the exact release source; no stale result cited |
| Storage failure or cancellation can contradict deletion/save intent | ARCH-02, UI-03, SEC-03, TEST-02 | P1 source-mitigated; Android corruption/reset and deterministic transient-I/O recovery pass; real storage/Keystore verification open | Corruption sentinel/write block, consequence-confirmed reset, identity-edit block, partial deletion, cancellation, and transient failure/recovery tests pass; retain real Keystore/device evidence |
| Pending password/auth action may outlive foreground or accept a stale host result | ARCH-06, ARCH-09, SEC-03/04 | P1 mitigated in source; process replacement and late-result tests pass; wider device verification open | Background cancels non-prompt work; stopped/stale/old-host results clear without connecting; rotation and process replacement exclude secret restoration; real Keystore expiry/invalidation remains open |
| Duplicate/stale destructive controls can affect hardware | UI-05, SEC-13 | P1 mitigated in source; local generation-invalidation tests pass; appliance verification open | Ephemeral UI confirmation plus runtime claim/serialize/generation-invalidate/no-retry tests pass; repeat destructive cases only on a disposable appliance |
| Hostile endpoint can force a full uncompressed WebSocket allocation before parser rejection | PERF-10, SEC-03, TEST-02 | P1 partially mitigated: compression amplification is refused, slow/fragment behavior is characterized, and rejected transport terminates; first uncompressed allocation remains open | API 37 and representative physical heap/PSS evidence, then a pre-allocation transport cap or explicit risk acceptance with an approved availability budget |
| Production distribution lacks signed, source-matched, traceable evidence | PLAT-04/05, SEC-15–17, TEST-10 | P1 open | Approved signed candidate; source/SBOM/licence/checksum/signing/provenance bundle; signed smoke; mapping custody; two-build comparison |
| Supported real devices and appliance journeys are not represented | PLAT-01/02, ADAPT-10, TEST-03/06/08 | P1 open | Current API 26/35/36/37, representative ARM, and 30-minute H.264 plus 30-minute MJPEG evidence retained |
| Primary journeys may be inaccessible with assistive technology | A11Y-01/04/05/07, TEST-05 | P1 open | TalkBack, switch, hardware keyboard, scanner, 200% text, RTL, focus/announcement results pass on signed candidate |
| Performance/profile changes lack physical trends | PERF-01–06/10/12/14, TEST-07 | P1 open | Fully drawn, named CUJs, physical ARM startup/frame/memory/network/power evidence, three stable runs, and active thresholds |
| Backend teardown/cancellation race can leak old-session ownership | ARCH-08/09, TEST-02/06 | P1 source-mitigated; deterministic cancellation-ignoring/repeated-cycle tests pass; physical integration evidence open | Synchronous command rejection, HID/input release, terminal publication fencing, late input/video/auth guards, replacement sessions, and repeated lifecycle cycles pass; retain real transport/decoder/device evidence |

## Sequenced remaining backlog

Estimates are focused engineering/QA time, excluding access or review latency.
“Rollback” means the safe route if the item fails; it does not authorize
publishing with a failed P1 gate.

### Completed remediation tranche and next evidence

The 0.3.2/code-9 working tree completed the prepared source tranche without
introducing a framework or generic abstraction layer. It adds adverse lifecycle,
late-callback, transient-storage, and authentication tests; an API 37 real
process-restart journey; semantic resource-backed runtime notices; bounded
certificate display metadata; retained action feedback; and durable FIFO app
notices. The exact source then passed the strict JVM/lint/APK/AAB/profile/SBOM
gate and a code-8 to code-9 emulator update retained app-private data.

The hostile WebSocket JVM fixture proves slow fragmented accumulation, rejects
compression at the handshake/frame header, and verifies input/H.264 termination.
At the user's direction, its Android heap/PSS instrumentation counterpart was
not run at this checkpoint. The first uncompressed allocation therefore remains
an explicit availability risk requiring representative physical measurement and
either a narrow pre-allocation cap or documented acceptance against an approved
budget.

The next work is evidence-driven: API 26/35/36, representative physical ARM,
real Keystore, assistive technology, signed/minified critical journeys, and
real-appliance negotiation/endurance. Keep message mappings exhaustive and add
translations/long-string coverage as those presentation paths evolve. Do not
reopen the architecture without a reproduced defect.

Explicit non-goals remain Hilt, per-action use cases, extra feature modules,
Room/offline command queues, broad screenshot matrices, remote attestation, and
background-service infrastructure: the current app has no evidence that those
would improve this direct-LAN KVM.

| Order | Work item / owner | Estimate | Dependency | Rollback route | Success metric |
| ---:| --- | ---:| --- | --- | --- |
| 1 | Freeze current source and run strict verification — build owner | Local checkpoint complete; repeat at release source freeze | All active source changes complete; JDK 21/SDK 37 | Revert the failing change set; retain last known source baseline; do not publish | Exact release commit has green local strict JVM/lint/release/AAB/benchmark/profile/SBOM outputs and retained logs |
| 2 | Adverse storage, secret lifecycle, and teardown integration — app/security owner | Focused source/JVM/API 37 restart checkpoint complete; physical/Keystore evidence remains | Order 1; Android test device | Revert affected feature changes or disable distribution; never downgrade deletion/trust guarantees | Deterministic transient DataStore, stale auth, cancellation-ignoring callbacks, repeated lifecycle, and `closeAndAwait` tests pass; retain real Keystore and physical lifecycle results |
| 3 | Produce signed production candidate and evidence bundle — release/security owner | 1–2 days | Order 1; approved signing environment/key custody | Withdraw candidate and rotate/revoke signing material if compromised; never publish debug-signed benchmark | Signature verified; final SHA-256/source tag recorded; source/licence/SBOM/mapping custody/checksums/provenance assembled; signed critical journeys pass |
| 4 | Complete Android/API matrix — QA owner | 1–2 days | Order 3 candidate; API 26/35/36/37 devices | Hold release and revert platform-specific regression | Current-commit results for API 26, 35, 36, 37 plus navigation, resize, IME, rotation, process/lifecycle journeys |
| 5 | Real appliance endurance and adverse network — protocol/video owner | 1–2 days | Order 3; trusted NanoKVM and isolated/disposable target for destructive tests | Hold release; force the stable transport only for diagnosis, not as an untested publication workaround | ≥30 min H.264 and ≥30 min MJPEG with frame progress, input, reconnect/background, no stuck HID, bounded observed memory; fallback/trust/auth cases pass |
| 6 | Accessibility/adaptive manual gate — accessibility/QA owner | 1–2 days | Order 3; TalkBack/Switch Access/hardware input | Hold release; revert inaccessible interaction change | TalkBack, switch, keyboard/mouse, scanner, 200% text, RTL, focus/announcement and breakpoint results pass |
| 7 | Physical performance baseline — performance owner | 2–3 days | Orders 3 and 5; representative and slower ARM devices; stable fixture | Remove an ineffective profile/optimization change and retain report-only gates | Fully drawn plus cold/warm/hot, first-frame, console, recovery/fallback traces; P50/P90/P95/P99, PSS/leak/network/power context; three stable runs |
| 8 | Bound or explicitly disposition WebSocket first allocation — protocol/security owner | 3–5 days investigation/implementation | Transport spike/alternative assessment; physical memory fixture | Keep current parser/downstream limits and hostile-endpoint warning; hold distribution if observed impact violates availability budget | Oversize input/H.264 cannot exceed approved peak memory before rejection, or signed risk acceptance records measured bound/affected devices |
| 9 | Pin-rotation device evidence — app/security owner | 1 day | Orders 2/4; certificate-recovery fixture | Retain the implemented fail-closed old/new review flow | Old/new identity, expiry, rejection, connect-once, replacement, and recovery journeys pass without automatic trust |
| 10 | Reproducibility and channel launch — release owner | 2–3 days | Orders 1–9; selected channel | Do not claim reproducibility or submit to F-Droid; withdraw mismatched artifact | Two isolated unsigned builds reconciled; public corresponding source and verification recipe match signed release |
| 11 | Field evidence review — reliability owner | 28–90 days after exposure | Actual public distribution with aggregate metrics, or issue-report process | Pause rollout/withdraw and publish advisory for severe regression | Health metrics by version/OS/device reviewed where available; top crash/ANR/startup/frame issues have owners and verified dispositions |

## 30/60/90 evidence trend

The score trend credits repository evidence and keeps manual gates at 0/1 until
run. The current 102/170 remains provisional because local unsigned/emulator
evidence does not close signed, physical-device, accessibility, appliance, or
field gates; it is not a release-readiness percentage. Score reductions below
are audit-truth corrections, not product regressions. The denominator grew by
six from Day 0: supporting-pane work made both ADAPT-03 and ADAPT-08 applicable
(four points), and protected credential authentication made SEC-08 applicable
(two points).

| Area | Day 0 baseline (2026-07-17) | Current checkpoint (toward Day 30) |
| --- | ---:| ---:|
| Platform/build | 5/16 | 11/16 |
| Architecture/concurrency | 15/28 | 19/28 |
| UI behavior | 10/16 | 10/16 |
| Adaptive behavior | 9/16 | 15/20 |
| Accessibility | 8/16 | 8/16 |
| Performance/reliability | 6/26 | 12/26 |
| Security/privacy | 12/26 | 19/28 |
| Testing/release | 6/20 | 8/20 |
| **Total** | **71/164 (43%)** | **102/170 (60%) provisional** |

| Horizon | Verified progress already available | Required evidence/outcome before exit |
| --- | --- | --- |
| Day 30 — establish truthful release foundation | JDK 21/target 37; strict verification policy and local strict result; minified variants; HTTPS-only NSC; application container/narrow ports; lifecycle collection; request-ID auth coordinator; explicit catalog states; control gate; dependency inventory/SBOM task; generated profile sources; privacy/threat/distribution documents | Repeat and retain Order 1 locally against the exact release source, then complete Orders 2–3: adverse secret/storage/teardown evidence and a signed traceable candidate. No stale artifact or unsigned smoke may be counted |
| Day 60 — prove supported journeys | Current automated protocol/state/viewport/control tests and the local API 37 device checkpoint provide a base | Complete Orders 4–6 and the appliance portion of 5: retain local API 26/35/36/37 results, physical ARM functional smoke, 30-minute H.264/MJPEG, TalkBack/switch/hardware input, real Keystore, process death, resize/adverse network/storage |
| Day 90 — make evidence durable | Macrobenchmark, profile generation/packaging, build inventory, and evidence templates exist | Complete Orders 7–10: fully drawn and named physical CUJs, three-run trends/thresholds, WebSocket allocation disposition, two-build comparison, exact source/checksum/signing bundle. Start Order 11 only after field exposure |

User outcomes tracked across the horizons are: no credential or trust action
before endpoint verification; no profile/credential loss from transient storage
failure; no stuck/replayed/duplicate input or destructive command; readable and
operable console with IME/assistive technology across supported windows; first
frame and interaction budgets measured on actual devices; and a binary that can
be traced to exact public source and signing identity.

## Explicitly deferred or not applicable

- Visual experimentation beyond the restrained Material 3 system, animation
  polish, and broad screenshot/golden matrices are deferred.
- Hilt, a domain/use-case layer, one module/ViewModel per screen, Room, and
  offline command queues add no current value.
- WorkManager, foreground services, notifications, deep links, PendingIntents,
  WebView, remote attestation, and anti-tamper controls have no corresponding
  component today.
- Third-party analytics/crash/performance SDKs will not be added. Use retained
  local lab evidence, opt-in issue reports, and aggregate channel health metrics only if a
  future channel provides them.

Revisit every N/A when a new exported component, inbound link, WebView, cloud
backend, background job, distribution channel, or independent data destination
is introduced.
