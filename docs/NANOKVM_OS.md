# NanoKVM OS compatibility (draft)

This is an initial client adapter, not full feature parity or a verified binary release.
NanoKVM OS is an independent SG2002 firmware for Cube, Lite and PCIe.

## Source contract

Source reviewed at [NanoKVM OS b98e78c7](https://github.com/dormancygrace/NanoKVM-OS/tree/b98e78c71a9948588f1f724fccd70eb48a578a78).
Client baseline: aee9642d9dac4d54a9d06120e978d272b3b9bbaa.

- server/service/auth/login.go: a native login requests the JSON token with
  X-NanoKVM-Return-Token: true. Browser logins retain their HttpOnly cookie.
- server/service/vm/video.go: read-only capabilities and batch video settings.
- server/service/ws/client.go and server/router/input_owner.go: control
  notifications, socket-specific input lease and guarded ATX operations.
- server/service/stream/direct/client.go: compatible 9-byte H.264 framing.
- server/router: the OS management API is distinct from the Sipeed updater.

## First adapter

OS-style application labels (for example v3.0-a1) trigger a read-only video
capability probe. The label alone never grants support. Required contract
fields are validated; missing/failed responses remain unsupported or unknown.
Stock three-component Sipeed versions retain their existing version rules.
This is a compatibility fingerprint for current OS releases, not a promise
that arbitrary forks or future version formats will be recognized.

Native login, H.264 Direct/WebRTC and MJPEG use the existing transports.
HEVC is not implemented. The server may reject H.264 when another viewer owns
an incompatible shared codec. The client does not silently change that codec.
MJPEG is removed from the fallback chain when the input cannot support it.

Input waits for the server's control grant. View-only sessions show an explicit
Take control action; reconnect never sends a takeover automatically. HID reports
are gated locally as well as by the server. ATX POST requests carry the current
socket lease. Replaced sockets, revocation, logout and disconnect invalidate it.
Leases are never exposed in the UI or included in model diagnostics.

Administrators may apply the intersection of runtime video limits and the
client's existing controls, through one validated OS batch. No removed GOP
write or implicit EDID/codec/portrait change is sent. Non-admin/unknown roles
can select local transport but cannot write shared encoder settings.

The first adapter hides media/network/system/terminal/automation/PicoClaw
management, HID reset and legacy update/password paths for OS sessions.
These features may exist in OS; their Sipeed API adapters have not been ported.
Use the OS web interface for those operations. Unsupported API calls are
also rejected locally before dispatch.

## Verification status and remaining work

Eight protocol regression tests cover discovery, login opt-in, batch writes,
role restrictions, control events, stale leases and runtime limits.
All eight new tests passed in a standalone JVM protocol harness using Kotlin
2.4.21, the declared protocol dependency versions and strict checksum verification.
The harness substitutes only the Android SuppressLint annotation; it cannot
validate Android runtime behavior or Compose UI. Its build tooling uses the
already-verified error_prone_annotations 2.30.0 and omits the unused Kotlin
scripting compiler extension; production dependency metadata is unchanged.

The wider protocol run did not pass: the first run reported 196/199 passing,
with timing assertions in PacedCommittedTextTest and NanoKvmTerminalSocketTest.
The terminal timing failure also reproduced against the unmodified upstream
source. These failures are retained, not suppressed. The Android Gradle gate,
lint, APK build and physical-device tests remain pending.

Before marking this PR ready:
- Run the full CONTRIBUTING.md Gradle gate with JDK21 and Android SDK37.
- Exercise current Sipeed and OS v3.0-a1 on a real Android handset.
- Test all three transports; include 4K input with MJPEG unavailable, portrait
  capture, H.265 active in another viewer and a missing HDMI source.
- Test two viewers, takeover, automatic transfer, background/reconnect,
  held keys, clipboard cancellation, mouse buttons and ATX lease rejection.
- Check non-admin and password-change-required accounts, large text, RTL,
  TalkBack, saved destination switching and obsolete UI actions.

No APK was distributed, no signing identity changed, and no physical appliance
was modified for this draft. Upstream contribution policy requests discussion
before substantial protocol work; this draft is prepared in the contributor's
fork for discussion, not submitted as ready upstream.
