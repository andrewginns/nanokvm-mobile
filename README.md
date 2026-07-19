# NanoKVM Mobile

[![Android CI](https://github.com/andrewginns/nanokvm-mobile/actions/workflows/android.yml/badge.svg)](https://github.com/andrewginns/nanokvm-mobile/actions/workflows/android.yml)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

NanoKVM Mobile is an unofficial, open-source Android client for a trusted
[Sipeed NanoKVM](https://github.com/sipeed/NanoKVM). It replaces the cramped
mobile-browser console with direct touch, pan and zoom, Android's native
keyboard, and controls that remain reachable in portrait and landscape.

> [!IMPORTANT]
> This project is independent of Sipeed and is not an official NanoKVM app.
> It controls real keyboard, mouse, reset, and power hardware. Review the
> target device before using guarded actions.

## 0.3.0 development milestone

The current source milestone is **0.3.0** (Android version code **7**). It is a
development candidate, not an approved or signed public release. The feature
list below describes implemented source; capability-gated features still need
the device, appliance, and signed-candidate evidence recorded in the parity
ledger before they can be described as release-verified.

- Saved NanoKVM connections with HTTPS certificate review and pinning.
- NanoKVM login and cookie authentication for application versions 2.3.2+.
- Hardware-decoded direct H.264 with automatic MJPEG fallback.
- Optional MJPEG frame-difference detection with a bounded temporary wake when a
  stream starts or falls back.
- Absolute direct-touch and optional trackpad pointer modes.
- Tap, double-tap, drag, all five mouse buttons, wheel scroll, 1x–4x pinch zoom,
  and pan.
- Live Android IME input plus a special-key tray while keyboard mode is open.
- Explicit, previewed Android clipboard/share-target text typing with target
  layout selection, pacing, cancellation, and reconnect-safe destination binding.
- Opt-in Android Keystore-encrypted passwords unlocked with biometrics or the
  device screen lock on supported Android versions.
- Account-password changes with explicit current/new review, generation binding,
  and optional protected replacement only after the appliance acknowledges it.
- Stream quality, HID reset, Ctrl-Alt-Delete, and guarded power/reset actions.
- Device, firmware, hardware, network, capability, GPIO, RTT, and stream
  diagnostics kept outside the framebuffer.
- Virtual-media image list/mount/eject/delete controls, appliance-side HTTP(S)
  image fetching, virtual USB modes, and Wake-on-LAN history with
  generation-bound confirmations.
- Native appliance administration for updates (including a transient offline
  package picker), reboot, HDMI/OLED/HID, SSH, hostname/mDNS/title, memory/swap,
  DNS, manual Wi-Fi/AP onboarding, TLS enablement, and Tailscale.
- Foreground-only system/serial terminals, bounded custom-script management,
  HID shortcut recording, leader-key controls, and autostart script editing.
- An explicit WebRTC-first transport preference with fresh direct-H.264 and
  MJPEG fallbacks, plus an explicitly entered, globally locked PicoClaw control
  surface on supported appliances.
- Restrained Material 3 system/light/dark appearance with optional Android
  dynamic colour; the live console stays neutral so wallpaper colour never
  tints the remote image or status meaning.
- Adaptive phone, landscape, and expanded layouts with bottom-sheet, side, or
  supporting-pane controls plus a vertically movable pan/zoom pad that docks
  above the keyboard while typing.

The app implements the native side of the NanoKVM 2.4.3 parity roadmap while
retaining a 2.3.2 compatibility floor. Capability gates and retained
verification are tracked in [docs/WEBUI_PARITY.md](docs/WEBUI_PARITY.md); the
trust boundaries and at-most-once mutation rules are in
[docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md). A code-complete feature is not
claimed as release-verified until its required Android and real-appliance
evidence is attached to that ledger.

## Requirements

- Android 8.0 (API 26) or newer.
- A NanoKVM reachable over the local network. Add its device-specific mDNS
  hostname or IP address on first launch.
- NanoKVM application version 2.3.2 or newer.

Private and self-signed certificates are supported through explicit per-device
fingerprint trust. The certificate-inspection probe temporarily accepts the
presented chain only so it can obtain the leaf certificate; it sends no
credential, token, or application request and independently checks hostname and
validity before showing certificate metadata. Application traffic never uses
that inspection client. The app installs no global CA and has no reusable or
global trust-all transport.

Authenticated profiles are HTTPS-only. The sole cleartext exception is the
explicit, pre-authentication access-point onboarding flow opened from the Wi-Fi
action on the connection catalogue; it never carries an account cookie or saved
credential and does not create an HTTP profile.

## Install and update

There is not yet an approved production binary. To build an installable
self-signed development APK with the machine's Android debug key:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-problems-report --dependency-verification=strict :app:assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. Android accepts an
update only when the application ID and signing certificate match the installed
copy and the new version code is not lower. Android debug keys are local to a
build account, so an APK built on another machine or under another user can be
rejected as **App not installed** even when the filename and version name look
correct. Verify the APK certificate before distribution and retain the signing
key securely. Do not uninstall merely to bypass a mismatch without first
accepting that app-private profiles, certificate decisions, and saved
credentials will be removed.

Production artifacts must be signed outside the repository with the approved,
stable release identity and pass [the release checklist](docs/RELEASE_CHECKLIST.md).
Never commit a signing key or its credentials.

## Find your way around

1. On the connection catalogue, use **Add a NanoKVM profile** to enter the
   hostname or IP address, HTTPS port, and username. The top actions also provide
   **Set up NanoKVM Wi-Fi in access-point mode**, **Appearance**, and **About and
   open-source licence**.
2. Choose **Connect**, review an untrusted private certificate if prompted, then
   enter the NanoKVM password. Password saving is a separate opt-in choice.
3. On the console, the floating actions provide **Show keyboard**, **Type phone
   clipboard**, and **Open console controls**.
4. **Open console controls** contains scroll sensitivity, direct/trackpad mode,
   the view pad toggle, Middle/Back/Forward mouse actions, Fit view, Video,
   Power, and More actions.
5. **More actions** contains reconnect and HID reset, **Device details**,
   **Virtual media**, **Wake-on-LAN**, **Administration**, **Operator tools**,
   **Shortcuts & autostart** when available, **PicoClaw (optional)**, full screen,
   and disconnect.

## Known limitations

- The compatibility floor is NanoKVM application 2.3.2. Later-version,
  hardware-dependent, and extension features are capability-gated and may be
  disabled, read-only, or unavailable on a particular appliance.
- **Auto** video uses direct H.264 and then MJPEG. WebRTC is tried first only
  when **WebRTC** is explicitly selected; it needs compatible native/hardware
  H.264 support and may contact ICE/STUN/TURN endpoints supplied by the trusted
  NanoKVM before falling back to fresh direct-H.264 and MJPEG attempts.
- Phone clipboard and Android share-target text is typed as USB HID input into
  the currently focused remote field. It is not a shared or bidirectional host
  clipboard. A paste is limited to 1,024 UTF-8 bytes and the target mapping is
  US or UK. Clipboard paste rejects unmappable text before typing; live IME text
  can report and omit characters unavailable in the selected mapping.
- Horizontal scrolling is implemented as Shift+wheel because NanoKVM exposes
  one wheel axis, so behavior depends on the remote operating system and app.
- A virtual-media URL tells the appliance to fetch an HTTP(S) `.iso` or `.img`;
  it is not a download to the phone. NanoKVM 2.4.3 exposes no stable transfer
  cancel or checksum contract.
- Root terminal, serial, scripts, updates, network changes, power controls, and
  PicoClaw can disrupt the appliance or controlled host. They require deliberate
  entry and the confirmations shown by the app.
- The current parity implementation has extensive source and API 37 emulator
  coverage, but the compatibility-floor, physical-device, accessibility,
  destructive, endurance, and signed-candidate matrices remain open where the
  ledger says so.

## Build

The repository includes the Gradle wrapper. Use Android Studio's bundled JDK 21
(or another supported JDK 21) and Android SDK platform 37. Java/Kotlin bytecode
targets version 17. Dependency verification is strict and must not be disabled:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
```

This produces an unsigned, minified release candidate. It is not a publishable
release until the signing, device, accessibility, appliance, and distribution
gates in [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) pass. Profile
regeneration and performance commands are documented in
[docs/BUILD_VERIFICATION.md](docs/BUILD_VERIFICATION.md).

## Controls

| Gesture/control | Direct-touch mode | Trackpad mode |
| --- | --- | --- |
| One-finger tap | Left click at the touched remote pixel | Left click |
| One-finger drag | Hold left button and drag | Move pointer |
| Double tap | Remote double-click | Remote double-click |
| Long press | Right click | Right click |
| Two-finger vertical drag on the remote preview | Wheel scroll | Wheel scroll |
| Drag on the pan/zoom pad | Pan the local viewport | Pan the local viewport |
| Pinch on the pan/zoom pad | Zoom the local viewport | Zoom the local viewport |
| Drag on the dedicated scroll rail | Scroll remote content up, down, left, or right | Scroll remote content up, down, left, or right |

The keyboard action opens the installed Android keyboard and, while keyboard
mode is visible, an accessory tray for Escape, Tab, Enter, Backspace, Delete,
modifiers, arrows, viewport positioning, function keys, and Ctrl-Alt-Delete.
Committed supported text is translated to USB HID reports; composing text
remains local until committed.

The separate pan/zoom pad floats over the full-height stream. Drag its left
handle vertically (or tap the handle to cycle bottom, middle, and top) to expose
the part of the remote screen needed for the current task. Opening the Android
keyboard temporarily places the pad directly above it; closing the keyboard
restores the previous position. Separate keyboard, phone-clipboard, and console
controls actions follow above or below the pad, leaving the pan/scroll strip full
width; they remain available as floating actions when the pad is hidden.

The dedicated four-direction scroll rail sits immediately to the right of the
view-controls caret. Adjust it from **Open console controls > Scroll
sensitivity** between 0.5x and 3.0x. Up and down use the NanoKVM mouse-wheel
axis; left and right use Shift+wheel compatibility and therefore depend on
support in the remote app. The same controls surface provides one-shot Middle,
Back, and Forward mouse actions; tap/drag and long press provide Left and Right.

Physical keyboards and external mice are also routed to the remote host while
the connected console is foregrounded. Direct mode maps an external pointer to
the remote framebuffer; Trackpad mode sends relative movement. Support for
special hardware keys and host handling of mouse buttons four/five still varies
by Android device, NanoKVM firmware, and remote operating system.

Saving a password is always opt-in. The encrypted replacement is committed only
after the NanoKVM accepts it, and unlocking it requires Android's system device
authentication prompt. Cancelling that prompt falls back to ordinary password
entry. An authentication prompt survives screen rotation without retaining the
old Activity or exposing the password to the replacement UI.

## Project layout

- `app/` – Compose UI, profile storage, gestures, IME bridge, and orchestration.
- `protocol/` – authentication, REST/WebSocket models, TLS policy, HID, optional
  feature APIs, and terminal transports.
- `video/` – WebRTC, direct H.264, and MJPEG lifecycles with Android rendering.
- `macrobenchmark/` – Baseline/Startup Profile generation and out-of-process
  release-like performance tests; it is not shipped in the app.
- `docs/` – architecture, protocol, security, testing, and distribution notes.

## Contributing and license

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
User-visible milestone and upgrade changes are recorded in
[CHANGELOG.md](CHANGELOG.md).
NanoKVM Mobile is licensed under the GNU General Public License v3.0 or later.
See [LICENSE](LICENSE). Release composition and corresponding-source
requirements are documented in [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md).
