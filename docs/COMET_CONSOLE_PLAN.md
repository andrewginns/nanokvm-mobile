# Comet Mobile console plan

## First release

Deliver an unofficial Android console for the original GL.iNet Comet (GL-RM1) on stock firmware. An operator should be able to connect over local HTTPS or an already reachable VPN address, use a BIOS with a phone, and edit text in a desktop application as comfortably as in NanoKVM Mobile. The first hardware target is RM1 firmware **1.10.3 release1**. A model joins the supported list after its own console checks pass.

| User task | First-release behavior |
| --- | --- |
| Reach the right machine | Save multiple named profiles, review the device certificate, optionally unlock the saved password with Android credentials, enter a fresh TOTP when enabled, and see the active target and connection state. |
| See a usable screen | Low-latency Direct H.264, fit to screen, pinch zoom, pan, movable viewpad, landscape and immersive fullscreen. Keep aspect ratio and input coordinates aligned when the keyboard opens, the phone rotates, or the target changes resolution. |
| Point precisely | Direct touch for ordinary use, relative trackpad for BIOS and small targets, click, right-click, drag, two-axis scrolling, and external mouse/pointer capture where Android supports it. |
| Type and send shortcuts | Native Android keyboard, physical keyboards, modifiers, function/navigation keys, and common chords such as Ctrl+Alt+Delete. Keep preview/edit/confirm for clipboard and Android share-target text, US/UK layout handling, cancellation, and intentional multiline behavior. |
| Recover from interruption | Show the difference between authentication expiry, HDMI signal loss, and a broken connection. Keep reconnect/cancel and explicit input release within reach. On reconnection, require fresh input from the operator. |

Virtual media and ISO mounting, mouse jiggling, appliance administration and firmware updates, terminals/scripts, PicoClaw, GL cloud pairing, and accessory management are outside this release. Optional host power or Wake-on-LAN can be scoped separately if needed. WebRTC is a later transport candidate if Direct fails an agreed usability case, such as an otherwise usable high-latency VPN. Ship the first release on the console acceptance criteria below.

## Implementation order

### 1. Give the fork its own identity and connection flow

- Set a distinct Android application ID, launcher label, and Comet release lineage. Keep package-bound profiles, pins, and protected passwords independent. Update the fork's signing and upgrade checks before handing off an APK. Preserve upstream licensing and attribution.
- Select a Comet backend at the existing [app factory](../app/src/main/java/org/nanokvm/mobile/AppContainer.kt). Keep DataStore, certificate review, Keystore unlock, and the existing session ownership. Adapt the [console contract](../app/src/main/java/org/nanokvm/mobile/runtime/ConsoleBackend.kt) where it exposes NanoKVM capability types and mandatory family-specific controls. Leave optional feature groups absent. Gate toolbar items, restored overlays, and their background requests too, including the current core Power entry.
- In `protocol`, implement same-origin form login at `/api/auth/login` and the `auth_token` session. Stock RM1's local form supplies the admin password with a six-digit code appended when TOTP is enabled. Prompt for the code separately and keep it out of saved credentials and logs. Surface rejected credentials, rate limiting, expired sessions, and changed certificate pins distinctly.

**Done when** a second installation coexists with NanoKVM Mobile, a private certificate can be reviewed, and a saved profile can connect, disconnect, and reconnect with 2FA. The production connection flow contacts only the selected Comet and exposes only implemented actions.

### 2. Bring up one live screen and one input path together

- Open the authenticated control socket at `/api/ws` and consume Comet's session state. Open `/api/media/ws`, wait for the advertised H.264 format, and request the stream. A video message has a type byte, a keyframe flag, and the encoded payload. Handle heartbeats separately and timestamp frames locally. Feed the existing [access-unit model](../video/src/main/java/org/nanokvm/video/H264AccessUnit.kt), bounded queue, and [MediaCodec renderer](../video/src/main/java/org/nanokvm/video/MediaCodecH264Renderer.kt). Resume decoding at a keyframe after loss or a surface change.
- Implement `RemoteInputSink` with Comet key and mouse events. Map absolute positions from the displayed video rectangle into Comet's signed range, and preserve ordering for press, movement, and release. Translate relative deltas and both wheel axes directly. Track held buttons and modifiers per foreground session. Release them while connected on focus loss, discard pending input on transport loss, and verify the host's behavior when the socket disappears.

**Done when** a phone shows a live desktop, taps all four visible corners correctly, and navigates a BIOS using relative input and a keyboard. Unplugging and reconnecting HDMI recovers the picture. Disconnecting while dragging or holding Shift leaves the host ready for fresh input.

### 3. Restore the everyday phone experience

- Keep the existing [viewport](../app/src/main/java/org/nanokvm/mobile/ui/viewport/ViewportTransform.kt), gesture routing, [native keyboard](../app/src/main/java/org/nanokvm/mobile/ui/components/NativeKeyboard.kt), and paste approval UI. Wire physical key down/up, modifier latching, edit/backspace, and shortcut buttons through the same ordered input session. Preserve previewed paste line breaks and layout checks with paced key events wherever the device's bulk text operation would change their behavior.
- Keep view-only gestures away from the host input path. Preserve zoom and fit across rotation where appropriate, keep keyboard controls reachable above the IME, and expose only video settings the session can apply. Show actionable status for no HDMI, expired login, and interrupted transport. Retiring a session drops pending paste, clicks, and shortcuts.

**Done when** Gboard and a physical keyboard can edit text with US and UK layouts, clipboard/share text waits for confirmation, a drag stays aligned while zoomed, and changing profiles during a paste never sends remaining text to either target.

### 4. Verify and hand off

| Check | Evidence required |
| --- | --- |
| Protocol and video | Mock HTTP/WebSocket fixtures for password and TOTP login, expiry, media negotiation, heartbeat vs. frame, truncated frames, keyframe recovery, coordinate extremes, and key/button release ordering. |
| Android usability | Exercise the existing viewport, IME, clipboard, pointer-capture, and accessibility tests against the Comet backend or its test double. Cover a compact phone in portrait and landscape, large font, fullscreen exit, rotation with the keyboard open, and app background/foreground. |
| Physical RM1 | On 1.10.3 release1, run one complete BIOS and desktop session at 1080p. Check 4K30 on a capable source and phone, and record the supported result. Test a reachable VPN address, lost Wi-Fi during a held modifier, HDMI loss while the network stays up, and session expiry. Compare typing, pointer alignment, and recovery with the NanoKVM Mobile experience. |
| Distribution | Verify the Comet APK's application ID and signer, install it alongside NanoKVM Mobile, and verify profile isolation. For subsequent Comet builds, test an in-place upgrade from the preceding Comet APK. |

## Evidence and starting point

The fork starts at [NanoKVM Mobile `a4e829b`](https://github.com/andrewginns/nanokvm-mobile/commit/a4e829b6888d622de4d5ec1f3012a26c402d5b2a). Its [parity ledger](https://github.com/andrewginns/nanokvm-mobile/blob/a4e829b6888d622de4d5ec1f3012a26c402d5b2a/docs/WEBUI_PARITY.md) records the original phone behavior. The scope above selects the console features from that ledger.

The inspected stock RM1 image identifies itself as `V1.10.3 release1`. Its SHA-256 is `8c26a2baf83ff3788e902f3ca4099fd714de58d2b1dedaecf1fd1c627849fe6a`. Its bundled local login and console clients establish the TOTP form, Direct H.264 framing, and control socket used here. The public KVMD revision is labelled 1.10.0. Physical RM1 verification is still open. [Official firmware catalog](https://dl.gl-inet.com/kvm/rm1/stable) · [GL.iNet console guide](https://docs.gl-inet.com/kvm/en/user_guide/gl-rm1/console_guide/) · [Published HID handlers](https://github.com/gl-inet/glkvm/blob/3e8dd23c4bd638a4650433664cc3b0f3b4d29395/kvmd/apps/kvmd/api/hid.py) · [Published Direct media server](https://github.com/gl-inet/glkvm/blob/3e8dd23c4bd638a4650433664cc3b0f3b4d29395/kvmd/apps/media/server.py)

## Stock protocol observations used by the Android client

The RM1 image identified above contains the GL login bundle and console assets under `/usr/share/kvmd/glweb/assets`, plus the browser console under `/usr/share/kvmd/web/share/js/kvm`. The GL login form concatenates the current six-digit code to the password. The authenticated client keeps the `auth_token` cookie on the selected origin.

The GL console’s mode switch posts `gl_webrtc=false` to `/api/streamer/set_params` for Direct video. The control socket receives partial state events. The GL UI reads HDMI presence from `streamer.hdmi.signal` and dimensions from `streamer.source.resolution`. The Direct browser console also reads `source.online`. Both sockets use binary 0 for heartbeat and 255 for pong. Media negotiation waits for `event.video.h264`, requests `start` with type `video` and format `h264`, and consumes binary type 1 followed by the keyframe byte and H.264 access unit. Binary 1 in the opposite direction requests a keyframe. [Published Direct server](https://github.com/gl-inet/glkvm/blob/3e8dd23c4bd638a4650433664cc3b0f3b4d29395/kvmd/apps/media/server.py)

The stock browser sends ASCII web key names and mouse button names as binary event types 1 and 2. Type 3 carries two big-endian signed 16-bit absolute coordinates. Relative motion and wheel events use types 4 and 5 with a squash byte and signed X/Y deltas. The stock HID selector chooses `usb` or `usb_rel` through `/api/hid/set_params`. The published KVMD control server clears held events on control-socket open and close. Physical timing after Wi-Fi loss still needs an authorized RM1. [Published HID handlers](https://github.com/gl-inet/glkvm/blob/3e8dd23c4bd638a4650433664cc3b0f3b4d29395/kvmd/apps/kvmd/api/hid.py) · [Published control server](https://github.com/gl-inet/glkvm/blob/3e8dd23c4bd638a4650433664cc3b0f3b4d29395/kvmd/apps/kvmd/server.py)

The Android integration fixture drives `MainActivity` over loopback HTTPS with a newly generated in-memory certificate. A synthetic 64×64 green H.264 IDR fixture (baseline AVC, I_PCM macroblocks) allows the test to check a decoded TextureView pixel before checking HID input and recovery. Run `:protocol:testDebugUnitTest`, `:video:testDebugUnitTest`, `:app:testDebugUnitTest`, `:app:lintDebug`, and the Android `CometConsoleInstrumentedTest` on an emulator. The physical acceptance table above remains the distribution gate.

## Android verification evidence

On an API 36 x86_64 phone emulator, the production Activity created a named profile through the editor, enabled TOTP and connected to the HTTPS fixture on its loopback IP. The test checked the decoded green frame, ordered tap, IME typing, Ctrl+Alt+Delete, a fresh stream after manual Retry and after background/foreground, edited multiline share confirmation, HDMI recovery, and expired login. After rotating the Activity with the remote keyboard open, the test checked a decoded frame on the replacement surface and a fresh keystroke from the restored keyboard.

The protocol, video and app JVM suites passed 198, 37 and 397 tests respectively. Android lint and the canonical release SBOM metadata check passed. The 116 selected Android tests covered the production console fixture, clipboard, profiles, certificate review, credential UI, Gboard, pointer capture, accessibility, compact landscape, large text, rotation and immersive mode.

The QA installation of `org.cometkvm.mobile` and the signed upstream NanoKVM Mobile 0.3.15 installation had distinct Android UIDs on the same emulator. A saved NanoKVM profile appeared only in the upstream app. Android Build Tools 36 and 37 both reported the upstream release certificate correctly and the Comet signer guard rejected the emulator-only debug APK. Physical and signed-update acceptance are still open.
