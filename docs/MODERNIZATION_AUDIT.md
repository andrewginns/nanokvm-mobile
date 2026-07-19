# Android and Kotlin modernization audit

This is the durable scorecard for applying
`android-kotlin-modernization-audit-guide.md` to NanoKVM Mobile. It records the
current implementation separately from evidence that must still be executed.
The restrained Material 3/Material You and adaptive-console phases are complete;
broader visual experimentation remains deferred. Functional accessibility,
security, reliability, and release evidence do not.

## Checkpoint and scoring

| Field | Value |
| --- | --- |
| Baseline audit | 2026-07-17, commit `7f3f6e7` (`Initial NanoKVM Mobile app`) |
| Current checkpoint | 2026-07-19 public-source milestone; the containing public root commit is the source identity |
| Application | GPL-3.0-or-later, direct-LAN Android client for a user-selected NanoKVM |
| Platform | minSdk 26, compile/target SDK 37, JDK 21 build runtime, Java/Kotlin bytecode 17 |
| Modules | `app`, `protocol`, `video`, and non-shipping `macrobenchmark` |
| Field telemetry | None; channel-provided aggregate Vitals are unavailable before distribution |

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
| E1 — platform/build | [app build](../app/build.gradle.kts), [settings](../settings.gradle.kts), [version catalogue](../gradle/libs.versions.toml), [wrapper properties](../gradle/wrapper/gradle-wrapper.properties), [manifest](../app/src/main/AndroidManifest.xml), and [NSC](../app/src/main/res/xml/network_security_config.xml); the local JDK 21 strict gate passed at this checkpoint | Retain the final-commit CI result and signed-candidate evidence |
| E2 — supply chain | [verification metadata](../gradle/verification-metadata.xml), [pinned CI](../.github/workflows/android.yml), [dependency inventory](DEPENDENCIES.md), and [SBOM task](../app/build.gradle.kts); main CI is configured to retain a self-verifying unsigned bundle and source archive while R8 mapping stays private by default | Signed artifact, post-signing checksums/provenance, and isolated-environment comparison remain open; retain a current successful workflow result |
| E3 — architecture/state | [AppContainer](../app/src/main/java/org/nanokvm/mobile/AppContainer.kt), [AppViewModel](../app/src/main/java/org/nanokvm/mobile/ui/AppViewModel.kt), [ProfileRepository](../app/src/main/java/org/nanokvm/mobile/data/ProfileRepository.kt), [console ports](../app/src/main/java/org/nanokvm/mobile/runtime/ConsoleBackend.kt), and [backend](../app/src/main/java/org/nanokvm/mobile/runtime/NanoKvmConsoleBackend.kt) | Real DataStore/Keystore, process death, cancellation races, and deterministic shutdown need device/integration evidence |
| E4 — trust/credentials | [CertificateInspector](../protocol/src/main/java/org/nanokvm/protocol/CertificateInspector.kt), [EndpointTrustPreflight](../protocol/src/main/java/org/nanokvm/protocol/EndpointTrustPreflight.kt), [SavedCredentialStore](../app/src/main/java/org/nanokvm/mobile/security/SavedCredentialStore.kt), and [request-ID coordinator](../app/src/main/java/org/nanokvm/mobile/security/CredentialAuthenticationCoordinator.kt) | Old/new pin comparison, connect-once, and explicit replacement are implemented; real Keystore/traffic/device recovery results remain open |
| E5 — bounds/control/reconnect | [protocol sources](../protocol/src/main/java/org/nanokvm/protocol), [video sources](../video/src/main/java/org/nanokvm/video), [ReconnectPolicy](../app/src/main/java/org/nanokvm/mobile/runtime/ReconnectPolicy.kt), and [ControlCommandGate](../app/src/main/java/org/nanokvm/mobile/runtime/ControlCommandGate.kt) | OkHttp allocates complete WebSocket messages before application limits; long hostile/slow/real-appliance results remain open |
| E6 — UI/adaptive/accessibility | [NanoKvmApp](../app/src/main/java/org/nanokvm/mobile/ui/NanoKvmApp.kt), [Material implementation record](MATERIAL_YOU_IMPLEMENTATION.md), [UI sources](../app/src/main/java/org/nanokvm/mobile/ui), and [instrumentation tests](../app/src/androidTest/java/org/nanokvm/mobile); local API 37 diagnostics exercised light/dark, 200% text, RTL, IME-open portrait, portrait-to-landscape live video, and an expanded supporting pane | TalkBack, switch, API 35, long-string/breakpoint matrices, hardware input, physical ARM, and signed-candidate results remain open |
| E7 — profiles/performance | [ReportDrawnWhen use](../app/src/main/java/org/nanokvm/mobile/ui/NanoKvmApp.kt), [macrobenchmark module](../macrobenchmark), [generated profiles](../app/src/main/generated/baselineProfiles), and [build verification](BUILD_VERIFICATION.md); a source-matched API 37 run exercised cold-none plus cold/warm/hot-Baseline and retained 12 frame traces | Emulator timing is diagnostic; console CUJs, physical ARM, and stable trends remain open |
| E8 — public assurance | [privacy](../PRIVACY.md), [security design](SECURITY.md), [threat model](THREAT_MODEL.md), [distribution](DISTRIBUTION.md), and [release checklist](RELEASE_CHECKLIST.md) | MASTG/manual results and observed-traffic/privacy reconciliation are required, not yet claimed |
| E9 — execution record | The public-source checkpoint passed 463 JVM tests across 73 suites; the API 37 device checkpoint passed 59 app instrumentation tests and one native WebRTC instrumentation test; profile packaging and the strict release-like build gate also passed | Raw results are local/ignored and unsigned; retain final CI and candidate records before crediting a public release |
| E10 — manual/release gates | [testing matrix](TESTING.md) and [release checklist](RELEASE_CHECKLIST.md) | API 35, physical ARM, signed candidate, 30-minute H.264/MJPEG, TalkBack/switch, and field evidence remain explicitly open |

## Platform and build

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| PLAT-01 | 1 | P1 partial | Target 37 is current, and API 26/35/36/37 CI lanes are configured. Retain current results from those lanes plus signed-candidate critical journeys. [E1, E6] |
| PLAT-02 | 1 | P1 partial | Edge-to-edge, safe drawing, `adjustResize`, and IME-aware controls exist. Cutout, both navigation modes, window resize, and every top-level state need device evidence. [E6] |
| PLAT-03 | 2 | P2 evidenced | JDK 21, Gradle 9.6.1 with checksum, central exact catalogue, bytecode 17, SDK 37, and pinned CI toolchain are recorded. Review quarterly. [E1] |
| PLAT-04 | 1 | P1 partial | Release enables R8 and resource shrinking and an unsigned minified artifact can be built. Signed/minified trust/login/video/input/credential smoke is open. [E1, E9] |
| PLAT-05 | 1 | P2 partial | Optimizing defaults and narrow project rules generate mapping/usage/configuration locally. Retention and test-justified rule review are not yet a release record. [E1, E2] |
| PLAT-06 | 2 | P2 evidenced | Exact versions, restricted repositories, wrapper URL validation, and distribution checksum are repository-enforced. [E1] |
| PLAT-07 | 2 | P2 evidenced | Strict verification metadata, full-SHA CI actions, wrapper validation, dependency review, and controlled update policy exist. [E2] |
| PLAT-08 | 2 | P2 evidenced | `DEPENDENCIES.md` records purpose, data/permissions, licence, owner/review, exception, and removal policy. [E2] |

## Architecture and Kotlin concurrency

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| ARCH-01 | 2 | P1 evidenced | Application container, repository boundary, VM-scoped runtime, and narrow high-rate input/Surface ports replace direct UI ownership. [E3] |
| ARCH-02 | 1 | P1 partial | DataStore and credential stores are authoritative; Ready/Unavailable/Corrupted states and partial deletion outcomes are explicit. Android repository I/O/corruption tests remain open. [E3] |
| ARCH-03 | 1 | P1 partial | `AppUiState` is immutable and durable flows are VM-owned. Low-frequency console commands still use a direct narrow sink; document/verify this deliberate boundary. [E3] |
| ARCH-04 | 2 | P1 evidenced | Compose uses `collectAsStateWithLifecycle()`. [E3] |
| ARCH-05 | 1 | P1 partial | `SavedStateHandle` restores non-secret screen state and saveable UI owns viewport/pad context. Real process death, resize, and complete pointer/viewport continuity remain open. [E3, E6] |
| ARCH-06 | 2 | P1 evidenced | `AppViewModel` is factory-created from the application container; credential coordinator retains only typed non-secret request metadata and IDs, never a UI callback or secret. [E3, E4] |
| ARCH-07 | 1 | P1 partial | repository, credential, trust, connection, and video work have I/O/worker dispatchers. StrictMode and slow/cancellation evidence are still required. [E3, E4] |
| ARCH-08 | 1 | P1 partial | Scopes/executors are owned and backend exposes `closeAndAwait()`. Prove production teardown waits and old callbacks cannot publish; no untracked cleanup may remain. [E3] |
| ARCH-09 | 1 | P1 partial | Attempt/generation ownership and many cancellation paths are tested. Rethrow coverage, cancellation-ignoring transports, reconnect/decoder races, and repeated lifecycle cycles remain open. [E3, E5] |
| ARCH-10 | 1 | P2 partial | State and loss policies are mostly explicit, but input server-event flow consumption/loss and every coalescing boundary need an ownership decision. [E3, E5] |
| ARCH-11 | 2 | P2 evidenced | UI/session models are immutable, mutable flows are hidden with `asStateFlow()`, and updates are atomic. [E3] |
| ARCH-12 | 2 | P2 evidenced | The three shipping modules and one test-only performance module each have a test, ownership, or delivery reason; no ceremonial layer is proposed. [E1, E3, E7] |
| ARCH-13 | 1 | P1 partial | Broad `configChanges` is removed, explicit non-secret restoration exists, and recreation tests are defined. True process-death/Surface/HID evidence is open. [E1, E3, E6] |
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
| UI-01 | 2 | P3 evidenced | A complete restrained Material 3 system now covers fixed fallback and dynamic light/dark schemes, typography, shapes, semantic surface roles, and fixed neutral console tokens. [E6] |
| UI-02 | 1 | P2 partial | Native dialogs, sheets, back handling, and IME behavior exist; signed-device lifecycle/window verification remains. [E6] |
| UI-03 | 1 | P1 partial | Loading/terminal profile-catalog, transient unavailable, corruption, reconnect, connection, and video states exist. Retain storage/adverse UI results. [E3, E6] |
| UI-04 | 1 | P1 partial | Typed backend/session progress and failures exist; save/storage/credential paths still need complete busy/duplicate and announcement tests. [E3, E5] |
| UI-05 | 2 | P1 evidenced | Power/reset/GPIO/Ctrl-Alt-Delete have consequence confirmation; command-key claims, serialization, generation invalidation, and unit tests prevent duplicate/replay execution. [E5] |
| UI-06 | 1 | P2 partial | Local API 37 diagnostics passed the profile catalogue at 200% text and RTL. Long strings, every top-level state, and narrow-window extremes remain open. [E6] |
| UI-07 | 1 | P2 partial | Navigation is single-flow and non-secret screen state is restorable; selection/draft/viewport continuity needs process/resize evidence. [E3, E6] |
| UI-08 | 2 | P3 evidenced | The application is cohesively Compose-based; no migration program is needed. [E6] |
| ADAPT-01 | 2 | P2 evidenced | `currentWindowAdaptiveInfo()` and current constraints select compact bottom sheet, compact-landscape/medium side overlay, and expanded supporting-pane behavior. [E6] |
| ADAPT-02 | 2 | P2 evidenced | Decision tests cover height and width boundaries, and API 37 instrumentation resizes through 500/900/700dp while asserting video-Surface identity. Physical foldable/posture evidence remains open. [E6] |
| ADAPT-03 | N/A | — | List-detail is not justified for the current single active-console journey. Reassess if profile management gains simultaneous detail work. |
| ADAPT-04 | 1 | P1 partial | Some viewport/pad/editor state is saveable; prove all non-secret context through rotation, resize, fold-like dimensions, and process death. [E6] |
| ADAPT-05 | 2 | P1 evidenced | No fixed orientation, aspect-ratio, or resizability restriction is present. [E1] |
| ADAPT-06 | 1 | P2 partial | Streaming is foreground-only; repeated resize/multi-window lifecycle results remain open. [E6] |
| ADAPT-07 | 1 | P1 partial | Touch, IME, mouse/trackpad, and keyboard paths exist. Hardware traversal, shortcuts, scroll, and focus evidence is open. [E6] |
| ADAPT-08 | 2 | P2 evidenced | A permanent Material supporting-pane scaffold changes one/two-pane directives without moving the `RemoteViewport` composition slot; instrumentation asserts the same `TextureView` and unchanged attach/detach counts across the breakpoint. [E6] |
| ADAPT-09 | 2 | P3 evidenced | Expanded width assigns a bounded supporting pane while retaining a usable video main pane and full-width safe-area input strip; compact modes remain intentionally transient. [E6] |
| ADAPT-10 | 2 | P1 evidenced | Viewport-transform and Surface-generation tests plus a live API 37 portrait-to-landscape NanoKVM journey retain the stream across resize. Signed physical-device crop/posture evidence remains a release gate. [E3, E6] |
| A11Y-01 | 1 | P1 partial | Custom controls expose semantics/actions; complete role/state/value and manual TalkBack evidence is open. [E6] |
| A11Y-02 | 1 | P2 partial | Semantics tests exist; review merged/unmerged trees for duplicates. [E6] |
| A11Y-03 | 2 | P2 evidenced | Target-size semantics tests and automated WCAG contrast checks cover fallback and fixed console palettes; connection status pairs colour with glyph and text. Scanner/UI-check and focus review remain open. [E6] |
| A11Y-04 | 1 | P1 partial | The profile catalogue remains operable at 200% text in a local API 37 diagnostic; repeat every top-level console, trust, credential, and safety state before release. [E6] |
| A11Y-05 | 0 | P1 open | No retained TalkBack, switch-style, or hardware-keyboard traversal result. [E10] |
| A11Y-06 | 2 | P1 evidenced | Pan/zoom gestures have dedicated pad/buttons and a movable handle; semantic alternatives are testable. [E6] |
| A11Y-07 | 1 | P1 partial | Errors are visible; async recovery announcement, repetition, and focus behavior need assistive-technology evidence. [E6] |
| A11Y-08 | 1 | P2 partial | Semantics assertions exist; scanner and retained manual assistive-technology results remain open. [E6, E10] |

## Performance, reliability, and efficiency

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| PERF-01 | 1 | P1 partial | Launch, connect-to-first-frame, 30-second console, reconnect/foreground recovery, and fallback CUJs are named. Device/data state, budgets, and retained runs are open. [E7] |
| PERF-02 | 1 | P1 partial | Macrobenchmark defines and locally executed cold no-compilation and cold/warm/hot Baseline-required methods; `ReportDrawnWhen` uses the terminal profile-catalog state. The API 37 emulator result is diagnostic, not a physical baseline. [E7] |
| PERF-03 | 1 | P1 partial | `FrameTimingMetric` produced 12 source-matched API 37 traces. Physical P50/P90/P95/P99 evidence and thresholds are not retained. [E7, E9] |
| PERF-04 | 1 | P1 partial | A minified profileable benchmark target exists. Representative/slower physical ARM evidence is open. [E1, E7] |
| PERF-05 | 1 | P1 partial | Current versioned Baseline Profile generation and APK/AAB package verification passed. Physical benefit evidence remains open. [E7] |
| PERF-06 | 1 | P2 partial | Current focused Startup Profile generation passed and is consumed by R8. Independent DEX-layout evidence remains open. [E7] |
| PERF-07 | 1 | P1 partial | Known file/Keystore/network work is dispatched off Main; startup catalog and StrictMode/trace proof remain open. [E3, E7] |
| PERF-08 | 1 | P2 partial | Stable list keys and rendering outside Compose are strengths; no recomposition measurement justifies score 2. [E6, E7] |
| PERF-09 | 2 | P2 evidenced | No unjustified stability annotations or speculative performance wrappers are present. [E6] |
| PERF-10 | 1 | P1 partial | Parser/decoder queues are bounded, but WebSocket first allocation, heap/PSS/leaks, low-memory, and long-session behavior remain open. [E5] |
| PERF-11 | N/A | — | No durable/background job is required. Reassess if background work is introduced; verify traffic stops today. |
| PERF-12 | 1 | P1 partial | Heartbeat ownership and no wake lock are evident. Network volume, metered policy, background stop, and physical power evidence are open. [E5] |
| PERF-13 | 0 | P2 open/conditional | No field exposure or Vitals exists. Review aggregate channel Vitals only after distribution; do not mark passing before data exists. [E10] |
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
| SEC-06 | 2 | P1 evidenced | Release manifest/NSC deny cleartext; TLS/pin tests and pre-secret trust preflight exist. Signed-device traffic capture remains a release gate but the repository control is repeatable. [E1, E4] |
| SEC-07 | 1 | P2 partial | Exact leaf pinning fails closed; mismatch review compares old/new identities and makes connect-once versus stored replacement explicit. Expiry-warning and retained device recovery rehearsal remain open. [E4, E8] |
| SEC-08 | 1 | P2 partial | System biometric/device credential protects opt-in passwords with a documented ten-second window. Real device lifecycle/expiry/invalidation evidence is open. [E4] |
| SEC-09 | 2 | P1 evidenced | One Activity is app-exported for the launcher and a strict `ACTION_SEND` `text/plain` share target; no other app component is exported in source. Retain signed merged-manifest evidence per release. [E1, E8] |
| SEC-10 | 1 | P1 partial | The direct plain-text share route validates action/type/payload, opens the same destination-bound preview, and never auto-types. Retained cold-start, process-death, malformed-intent, and supported-API evidence remains open. [E1, E6, E8] |
| SEC-11 | N/A | — | No PendingIntent. Reassess on notification/widget/service introduction. |
| SEC-12 | N/A | — | No WebView. Reassess if web content is embedded. |
| SEC-13 | 1 | P1 partial | No APK secret is trusted; token origin, firmware assumptions, command serialization, and no replay/retry are documented/tested. Real adverse appliance evidence is open. [E4, E5, E8] |
| SEC-14 | N/A | — | Play Integrity adds no meaningful boundary for this open-source local client without a developer backend. Reassess on backend entitlement. |
| SEC-15 | 2 | P1 evidenced | Exact dependencies, repository restrictions, hashes, action pins, dependency inventory, SBOM task, and minified variants exist. Signed release retention remains TEST-10. [E1, E2] |
| SEC-16 | 1 | P1 partial | Threats map to applicable MASTG v2 IDs and dispositions. Actual signed/minified device results are not retained. [E8] |
| SEC-17 | 1 | P1 partial | Privacy, data inventory, manifest, and dependencies are reconciled in docs; observed traffic and distribution-channel disclosure remain open. [E8] |

The ephemeral inspection trust manager is deliberately narrow: metadata-only,
no credential/token/application request, independent hostname/date validation,
and no reuse for application traffic. Parser bounds must likewise be described
accurately: OkHttp's complete WebSocket allocation occurs before the app check.

## Testing and release assurance

| ID | Score | Priority/status | Current evidence and remaining gate |
| --- | ---:| --- | --- |
| TEST-01 | 1 | P1 partial | A current 463/463 JVM run across 73 suites covers protocol, transforms, attempts, reconnect, credential staging, parity gateways, catalog/layout/contrast logic, control gates, and the WebRTC logging policy. Device-backed state/adverse matrices remain open. [E3–E6, E9] |
| TEST-02 | 1 | P1 partial | MockWebServer/parser tests exist. Real DataStore, Keystore, backend fallback/reconnect/no-replay, and pre-allocation transport tests remain. [E3–E5] |
| TEST-03 | 1 | P1 partial | Debug UI journeys exist. Signed/minified trust/login/first-frame/failure/reconnect/credential journeys are open. [E6, E10] |
| TEST-04 | 0 | P3 deferred | Broad visual snapshots are deferred; retain only high-value structural compact/expanded, theme, 200% text, and RTL cases later. [E6] |
| TEST-05 | 1 | P1 partial | Semantics tests exist; analysis tools plus manual TalkBack, keyboard, and switch-style traversal are open. [E6, E10] |
| TEST-06 | 1 | P1 partial | Recreation and lifecycle tests are defined; true process death, resize, offline/slow endpoint, storage failure, auth cancellation, and repeated background cycles remain. [E3, E6] |
| TEST-07 | 1 | P1 partial | Source-matched API 37 profile generation and four-mode Macrobenchmark execution passed. Controlled physical results and trends do not exist. [E7, E9] |
| TEST-08 | 1 | P1 partial | A local API 37 run passed 59/59 app instrumentation tests plus the video module's 1/1 native WebRTC crash-regression test. Retained API 26/35/36/37 CI results and representative physical ARM remain explicit open gates. [E6, E10] |
| TEST-09 | 1 | P1 partial | Risk-based tests and MASTG mapping exist; signed-release execution/dispositions are open. [E8, E10] |
| TEST-10 | 0 | P1 open | No approved signed production candidate or signed/minified device smoke evidence exists. [E10] |

## P0/P1 risk register

| Risk | IDs | Current status | Closure evidence |
| --- | --- | --- | --- |
| Current source and generated artifacts can diverge from passing outputs | PLAT-04, TEST-01, TEST-07 | Locally closed at checkpoint; CI retention open | Clean JDK 21 strict gate and applicable device runs attached to the final commit; no stale result cited |
| Storage failure or cancellation can contradict deletion/save intent | ARCH-02, UI-03, SEC-03, TEST-02 | P1 mitigated in source, verification open | Transient retry vs corrupt confirmed reset, reset consequence, identity-edit block, partial deletion, DataStore I/O/corruption, and cancellation tests all pass |
| Pending password/auth action may outlive foreground or accept a stale host result | ARCH-06, ARCH-09, SEC-03/04 | P1 mitigated in source, verification open | Background cancels non-prompt work; a stopped prompt result clears without connecting; rotation, process-death, stale request/host, and real Keystore tests pass |
| Duplicate/stale destructive controls can affect hardware | UI-05, SEC-13 | P1 mitigated in source, verification open | Claim/serialize/generation-invalidate/no-retry tests pass at backend level and on a disposable appliance |
| Hostile endpoint can force a full WebSocket allocation before parser rejection | PERF-10, SEC-03, TEST-02 | P1 open residual availability risk | Pre-allocation transport cap or an explicitly accepted release risk with boundary/heap/slow/oversize evidence and documented device behavior |
| Production distribution lacks signed, source-matched, traceable evidence | PLAT-04/05, SEC-15–17, TEST-10 | P1 open | Approved signed candidate; source/SBOM/licence/checksum/signing/provenance bundle; signed smoke; mapping custody; two-build comparison |
| Supported real devices and appliance journeys are not represented | PLAT-01/02, ADAPT-10, TEST-03/06/08 | P1 open | Current API 26/35/36/37, representative ARM, and 30-minute H.264 plus 30-minute MJPEG evidence retained |
| Primary journeys may be inaccessible with assistive technology | A11Y-01/04/05/07, TEST-05 | P1 open | TalkBack, switch, hardware keyboard, scanner, 200% text, RTL, focus/announcement results pass on signed candidate |
| Performance/profile changes lack physical trends | PERF-01–06/10/12/14, TEST-07 | P1 open | Fully drawn, named CUJs, physical ARM startup/frame/memory/network/power evidence, three stable runs, and active thresholds |
| Backend teardown/cancellation race can leak old-session ownership | ARCH-08/09, TEST-02/06 | P1 open | `closeAndAwait` production path plus cancellation-ignoring transport/decoder/reconnect and repeated lifecycle tests pass |

## Sequenced remaining backlog

Estimates are focused engineering/QA time, excluding access or review latency.
“Rollback” means the safe route if the item fails; it does not authorize
publishing with a failed P1 gate.

| Order | Work item / owner | Estimate | Dependency | Rollback route | Success metric |
| ---:| --- | ---:| --- | --- | --- |
| 1 | Freeze current source and run strict verification — build owner | Locally complete; CI retention open | All active source changes complete; JDK 21/SDK 37 | Revert the failing change set; retain last known source baseline; do not publish | Exact current commit has green strict JVM/lint/release/AAB/benchmark/profile/SBOM outputs and attached logs |
| 2 | Adverse storage, secret lifecycle, and teardown integration — app/security owner | 2–3 days | Order 1; Android test device | Revert affected feature changes or disable distribution; never downgrade deletion/trust guarantees | DataStore unavailable/corrupt/reset, foreground secret wipe, real process death, stale auth, cancellation race, and `closeAndAwait` tests pass |
| 3 | Produce signed production candidate and evidence bundle — release/security owner | 1–2 days | Order 1; approved signing environment/key custody | Withdraw candidate and rotate/revoke signing material if compromised; never publish debug-signed benchmark | Signature verified; final SHA-256/source tag recorded; source/licence/SBOM/mapping custody/checksums/provenance assembled; signed critical journeys pass |
| 4 | Complete Android/API matrix — QA owner | 1–2 days | Order 3 candidate; API 26/35/36/37 devices | Hold release and revert platform-specific regression | Current-commit results for API 26, 35, 36, 37 plus navigation, resize, IME, rotation, process/lifecycle journeys |
| 5 | Real appliance endurance and adverse network — protocol/video owner | 1–2 days | Order 3; trusted NanoKVM and isolated/disposable target for destructive tests | Hold release; force the stable transport only for diagnosis, not as an untested publication workaround | ≥30 min H.264 and ≥30 min MJPEG with frame progress, input, reconnect/background, no stuck HID, bounded observed memory; fallback/trust/auth cases pass |
| 6 | Accessibility/adaptive manual gate — accessibility/QA owner | 1–2 days | Order 3; TalkBack/Switch Access/hardware input | Hold release; revert inaccessible interaction change | TalkBack, switch, keyboard/mouse, scanner, 200% text, RTL, focus/announcement and breakpoint results pass |
| 7 | Physical performance baseline — performance owner | 2–3 days | Orders 3 and 5; representative and slower ARM devices; stable fixture | Remove an ineffective profile/optimization change and retain report-only gates | Fully drawn plus cold/warm/hot, first-frame, console, recovery/fallback traces; P50/P90/P95/P99, PSS/leak/network/power context; three stable runs |
| 8 | Bound or explicitly disposition WebSocket first allocation — protocol/security owner | 3–5 days investigation/implementation | Transport spike/alternative assessment; physical memory fixture | Keep current parser/downstream limits and hostile-endpoint warning; hold distribution if observed impact violates availability budget | Oversize input/H.264 cannot exceed approved peak memory before rejection, or signed risk acceptance records measured bound/affected devices |
| 9 | Pin-rotation device evidence — app/security owner | 1 day | Orders 2/4; certificate-recovery fixture | Retain the implemented fail-closed old/new review flow | Old/new identity, expiry, rejection, connect-once, replacement, and recovery journeys pass without automatic trust |
| 10 | Reproducibility and channel launch — release owner | 2–3 days | Orders 1–9; selected channel | Do not claim reproducibility or submit to F-Droid; withdraw mismatched artifact | Two isolated unsigned builds reconciled; public corresponding source and verification recipe match signed release |
| 11 | Field evidence review — reliability owner | 28–90 days after exposure | Actual Play/channel distribution with aggregate metrics, or issue-report process | Pause rollout/withdraw and publish advisory for severe regression | Vitals by version/OS/device reviewed where available; top crash/ANR/startup/frame issues have owners and verified dispositions |

## 30/60/90 evidence trend

The score trend credits repository evidence and keeps manual gates at 0/1 until
run. The current 106/168 remains provisional because local unsigned/emulator
evidence does not close signed, physical-device, accessibility, appliance, or
field gates; it is not a release-readiness percentage. The denominator grew by
two because ADAPT-08 became applicable when the supporting-pane transition was
implemented.

| Area | Day 0 baseline (2026-07-17) | Current checkpoint (toward Day 30) |
| --- | ---:| ---:|
| Platform/build | 5/16 | 12/16 |
| Architecture/concurrency | 15/28 | 20/28 |
| UI behavior | 10/16 | 11/16 |
| Adaptive behavior | 9/16 | 15/18 |
| Accessibility | 8/16 | 9/16 |
| Performance/reliability | 6/26 | 12/26 |
| Security/privacy | 12/26 | 19/28 |
| Testing/release | 6/20 | 8/20 |
| **Total** | **71/164 (43%)** | **106/168 (63%) provisional** |

| Horizon | Verified progress already available | Required evidence/outcome before exit |
| --- | --- | --- |
| Day 30 — establish truthful release foundation | JDK 21/target 37; strict verification policy and local strict result; minified variants; HTTPS-only NSC; application container/narrow ports; lifecycle collection; request-ID auth coordinator; explicit catalog states; control gate; dependency inventory/SBOM task; generated profile sources; privacy/threat/distribution documents | Retain Order 1 in CI, then complete Orders 2–3: adverse secret/storage/teardown evidence and a signed traceable candidate. No stale artifact or unsigned smoke may be counted |
| Day 60 — prove supported journeys | Current automated protocol/state/viewport/control tests and configured API 26/35/36/37 lanes provide a base | Complete Orders 4–6 and the appliance portion of 5: retain API 26/35/36/37 CI results, physical ARM functional smoke, 30-minute H.264/MJPEG, TalkBack/switch/hardware input, real Keystore, process death, resize/adverse network/storage |
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
  WebView, Play Integrity, and anti-tamper controls have no corresponding
  component today.
- Third-party analytics/crash/performance SDKs will not be added. Use retained
  lab/CI evidence, opt-in issue reports, and aggregate Android Vitals only if a
  future channel provides them.

Revisit every N/A when a new exported component, inbound link, WebView, cloud
backend, background job, distribution channel, or independent data destination
is introduced.
