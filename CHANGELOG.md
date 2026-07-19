# Changelog

This project follows semantic versioning for source milestones. Android version
codes are monotonically increasing: changed distributable bytes must never reuse
an older code. No entry below represents an approved production release unless
it explicitly says so.

## [0.3.0] - Unreleased

Android version code: **7**. This is the current development milestone and has
not yet passed the signed-production-candidate gate.

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
- Account password change, Wi-Fi AP onboarding, and opt-in MJPEG frame-difference
  coordination.

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

### Security and privacy

- Authenticated profiles remain HTTPS-only; the isolated cleartext exception is
  limited to explicit, cookie-free pre-authentication AP onboarding.
- Clipboard/share payloads, terminal/script content, PicoClaw keys/chat, and
  offline-update document access remain transient and are not persisted.
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
