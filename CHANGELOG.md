# Changelog

This project follows semantic versioning for source milestones. Android version
codes are monotonically increasing: changed distributable bytes must never reuse
an older code. No entry below represents an approved production release unless
it explicitly says so.

## [0.3.6] - 2026-07-23 (public pre-release candidate)

Android version code: **13**. This is the first candidate prepared for
distribution with the protected production signing identity. It is scoped for
publication as a GitHub pre-release, not a stable production approval:
unchecked physical-device, accessibility, real-appliance, destructive,
endurance, and wider Android-matrix items remain open in
`docs/RELEASE_CHECKLIST.md`.

### Changed

- Advanced the version name and Android version code so the release-candidate
  bytes do not reuse the already shared 0.3.5/code-12 development identity.
- Added guarded production-key creation, clean-build evidence, signing-lineage,
  and APK signing helpers for a traceable direct-GitHub distribution path.
- Completed the strict dependency-verification manifest for fresh-cache release
  builds so missing Maven metadata cannot be hidden by a warm developer cache.
- Expanded the fail-closed evidence set to retain the exact source archive,
  manifest and network policy, dependency graph, benchmark, profiles, SBOM,
  tests, lint, and complete R8 outputs before signing.
- The in-app About surface now exposes the exact version/code/source and actual
  installed signing certificate, with offline GPL, privacy, security, project,
  wrapper, and complete pinned WebRTC notice material.
- Baseline Profile collection now waits for fully rendered destinations and
  fails closed unless AndroidX observes three identical consecutive rule sets;
  refreshed Baseline and Startup Profiles are package-verified in the release
  APK and AAB.

## [0.3.5] - Development snapshot

Android version code: **12**. This development snapshot did not pass the
signed-production-candidate gate.

### Fixed

- The live native keyboard no longer forces the installed IME into
  no-personalized-learning/incognito mode, restoring keyboard-provided voice
  typing while leaving keyboard privacy controls with the user-selected IME.

## [0.3.4] - Development snapshot

Android version code: **11**. This development snapshot did not pass the
signed-production-candidate gate.

### Changed

- Console quick actions use the compact icon treatment so keyboard, clipboard,
  and controls remain clear of the movable pan-and-zoom controls.

### Fixed

- The fixed dark console palette now also drives Material component defaults,
  keeping recovery actions, pointer chips, viewport controls, sheet handles,
  and disabled actions readable under light and dynamic application themes.

## [0.3.3] - Development snapshot

Android version code: **10**. This development snapshot did not pass the
signed-production-candidate gate.

### Changed

- Remote pointer capture now has an explicit lifecycle-owned controller and
  visible capture status, with a local escape path that does not leak the key to
  the remote host.
- Connection recovery preserves destination ownership and exposes bounded,
  user-actionable reconnect state across lifecycle transitions.

### Fixed

- Core-session destination approval, external-pointer routing, viewport
  transforms, and keyboard teardown are covered by regression tests for their
  cancellation and recovery edges.

## [0.3.2] - Development snapshot

Android version code: **9**. This development snapshot did not pass the
signed-production-candidate gate.

### Changed

- Runtime, administration, operator, automation, PicoClaw, and Phase 3 notices
  now cross the presentation boundary as closed semantic models and render from
  Android string resources. Connection status remains latest-wins while
  sequenced action feedback stays visible until the next action or session.
- Transient app notices use a ViewModel-owned FIFO with identity-bound
  acknowledgement, so equal consecutive notices are not lost and lifecycle or
  navigation changes do not dismiss the wrong message.
- Certificate subject, issuer, and alternative-name display data is bounded and
  neutralized before presentation while retaining the verified identity and the
  complete SHA-256 fingerprint. The review screen discloses any shortening.
- Session callbacks, reconnect progress, authentication results, and input/video
  ownership are generation- and lifecycle-fenced against late or
  cancellation-ignoring work.
- Baseline and Startup Profiles were regenerated from the current source.

### Fixed

- Repeated background/reconnect/foreground/close cycles no longer allow stale
  decoder, input, authentication, or reconnect callbacks to mutate a replacement
  session.
- Transient DataStore failures preserve the last known settings and retry with
  bounded backoff instead of presenting a false reset state.
- Oversized input and direct-H.264 WebSocket messages terminate their transports
  promptly; compression negotiation is refused to prevent compressed ingress
  amplification. OkHttp's first complete uncompressed-message allocation remains
  a documented availability risk.
- PicoClaw chat now validates, dispatches, and displays one exact normalized,
  bounded payload, preventing a remote send followed by a local display failure
  for multibyte surrounding whitespace.
- The shared development APK now advances to code 9 and is verified against the
  established code-8 signing lineage before handoff. The builder requires the
  actual predecessor, rejects repository-local keystores, and refuses to replace
  an existing versioned output with different bytes.

### Verification

- The exact working tree passed 548 JVM tests across 83 suites, six zero-finding
  debug/release lint reports, 76 app instrumentation tests, the native WebRTC
  regression, and the out-of-process profile-draft restart test on API 37.
- The code-8 to code-9 emulator update retained app-private data and the updated
  app cold-launched without a crash entry.
- The Android WebSocket heap/PSS instrumentation fixture was deliberately not
  invoked for this checkpoint; physical-device measurement and disposition of
  the first uncompressed allocation remain open.

## [0.3.1] - Development snapshot

Android version code: **8**. This development snapshot did not pass the
signed-production-candidate gate.

### Fixed

- Shareable development APKs can be built with an explicitly selected local
  signing key instead of silently inheriting the build process's JVM-home debug
  key. A local verifier rejects a wrong package, signing lineage, or
  non-increasing version before handoff.
- The development version code now increases past the earlier 0.3.0/code-7
  snapshot so an otherwise compatible APK can update it in place.

## [0.3.0] - Development snapshot

Android version code: **7**. This development snapshot did not pass the
signed-production-candidate gate.

### Added

- Native virtual-media, Wake-on-LAN, device-details, administration, operator,
  automation, offline-update, and PicoClaw surfaces with capability and
  session-generation gates.
- Previewed Android clipboard and plain-text share-target typing with US/UK
  target selection, cancellable pacing, progress, and reconnect-safe destination
  binding.
- Explicit WebRTC-first video preference with fresh direct-H.264 and MJPEG
  fallback attempts; Auto remains direct H.264 followed by MJPEG.
- Five-button mouse support, external keyboard/mouse routing, four-direction
  scroll pad, adjustable sensitivity, immersive mode, and movable pan/zoom pad.
- Material 3 system/light/dark appearance, optional dynamic colour, adaptive
  control presentations, and neutral live-console colours.
- Device/application/hardware/network/capability/GPIO/RTT/video diagnostics kept
  outside the remote framebuffer.
- Account password change, authenticated manual Wi-Fi administration, and
  opt-in MJPEG frame-difference coordination.

### Changed

- Advanced optional endpoints now preserve available sections when another
  endpoint is absent or returns a compatible empty/null-list response.
- Console quick actions are separate keyboard, phone-clipboard, and console
  controls buttons; the view/scroll pad retains the full available width.
- Mouse-action pill labels use the console foreground colour for readable
  contrast in dark and light appearances.
- WebRTC now declares its required network-state permission and retains its JNI
  surface in minified builds.
- Documentation now distinguishes source implementation, capability support,
  retained verification, and signed-release status.
- Pre-authentication AP onboarding was removed; initial setup now stays outside
  the app so Android cleartext transport can remain disabled.
- The monolithic default-no-op console command sink was replaced by focused,
  typed feature controls; unsafe opaque feature tokens and recovery casts were
  removed.
- Automation refresh, editor, approval, and mutation state now belongs to a
  closeable lifecycle-aware state owner instead of the composable.
- Console overlays are one mutually exclusive saveable state, settings remain
  DataStore-authoritative with bounded-backoff recovery, and replay-free output
  is collected only while its UI lifecycle is started.
- Session-bound automation/operator drafts and viewport transforms survive
  configuration recreation without retaining secrets; destructive confirmations
  and one-use approvals are cleared when their destination generation changes.
- Pan/zoom gestures and their button alternatives rebind to replacement viewport
  state after a remote-resolution change instead of retaining detached callbacks.
- Large appliance catalogs use stable-keyed lazy lists, while bounded operator
  output is coalesced to display-frame publication instead of rebuilding its
  complete visible string for every event.
- REST execution, response reads, and decoding are internally main-safe and
  cancellation-aware. Native WebRTC/EGL initialization is deferred until the
  user explicitly selects WebRTC.
- Input and WebRTC heartbeats use fixed-delay scheduling so Android process
  suspension cannot cause catch-up bursts, and credential-store directory
  resolution is deferred until an I/O-dispatched operation needs it.
- WebSocket compression negotiation is refused to prevent compressed ingress
  amplification. Oversized direct H.264 now cancels immediately into fallback;
  input stops command acceptance, queues HID releases, and uses a bounded close.
- Debug builds enable StrictMode thread and VM diagnostics to expose accidental
  blocking work and leaked closeable or registration ownership during local QA.

### Security and privacy

- NanoKVM origin, authenticated signaling, and control traffic require HTTPS;
  HTTP redirects are never followed, while explicit WebRTC may use validated
  appliance-supplied STUN/TURN ICE peers.
- Clipboard/share payloads, terminal/script content, PicoClaw keys/chat, and
  offline-update document access remain transient and are not persisted.
- Clipboard/share content is rejected above 1,024 UTF-8 bytes before retention;
  oversized session tokens and malformed saved certificate pins fail closed.
- Profile DataStore corruption is recorded explicitly and blocks writes until a
  consequence-confirmed reset instead of appearing as an empty catalog.
- WebRTC ICE/STUN/TURN traffic supplied by the trusted appliance is disclosed.
- Destructive or persistent writes are reviewed, generation-bound, non-replayed,
  and reconciled after ambiguous responses.

### Upgrade notes

- An APK can update an installed copy only when its application ID and signing
  certificate match and its version code is not lower. Development debug keys
  are machine/account specific.
- Do not uninstall to work around a signing mismatch without accepting removal
  of app-private profiles, certificate decisions, and saved credentials.

## [0.2.1] - Development snapshot

Android version code: **6**.

- Established the Material 3 console-first MVP with saved HTTPS connections,
  certificate review/pinning, H.264-to-MJPEG video, Android IME input, direct and
  trackpad pointer modes, pan/zoom, guarded host controls, and opt-in protected
  credentials.
- This snapshot was debug-signed development output, not an approved public
  production release.

## [0.1.0] - Development snapshot

Android version code: **1**.

- Initial native Android NanoKVM console prototype.
