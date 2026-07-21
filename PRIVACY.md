# Privacy notice

NanoKVM Mobile is an open-source client for a NanoKVM appliance selected by the
user. It has no developer-operated service, analytics, advertising, telemetry,
account system, or automatic crash reporting. The maintainers do not receive
data from the app.

## Data handled on the device

- A connection profile contains a generated profile ID, display name, host or
  IP address, HTTPS port, HTTPS setting, username, and optional public SHA-256
  leaf-certificate fingerprint. Profiles do not contain video preferences or
  passwords. They are stored in the app's private DataStore and are excluded
  from Android backup and device transfer.
- App-wide preferences for theme, dynamic colour, scroll sensitivity, and the
  opt-in MJPEG frame-difference setting are stored in the app's private
  DataStore. They contain no credential, session token, clipboard text, terminal
  content, or remote framebuffer data.
- Saving a password is always opt-in. The password is encrypted with
  AES-256-GCM using a non-exportable Android Keystore key that requires recent
  biometric or device-credential authentication. The encrypted record is stored
  in the app's no-backup directory and is cryptographically bound to the
  profile's login identity.
- Unsaved passwords and NanoKVM session tokens are held only in memory for the
  active operation or session. On a genuine background transition, connection
  work and non-prompt secret actions are cancelled and cleared. If showing the
  Android authentication UI itself stops the Activity, that prompt-owned action
  is retained only until its terminal callback; a callback received while the
  app is stopped clears the mutable buffer and cannot connect. Secrets are also
  cleared on success, failure, replacement, disconnect, and ViewModel/backend
  teardown. They are not written to saved instance state, notifications, the
  clipboard, backups, or app diagnostic logs.
- Remote framebuffer frames are decoded for display and are not saved or
  uploaded by the app. Parser and decoder queues are bounded. OkHttp receives a
  complete WebSocket message before the app can reject an oversized H.264 or
  input message, so a faulty or hostile configured endpoint can still cause a
  transient transport allocation up to the received message size. This is an
  availability limitation, not intentional retention.
- Keyboard, pointer, paste, GPIO, and power actions are sent directly to the
  selected NanoKVM. They are not queued for later replay or sent to the
  maintainers.
- The app reads Android clipboard text only after the user chooses the phone
  clipboard action. Plain-text share intents are removed from the Activity
  intent immediately. Normalized clipboard/share text above 1,024 UTF-8 bytes
  is rejected before the app retains it; accepted text remains memory-only
  while the app prepares the destination-bound preview or paced HID operation
  and is cleared after consumption, cancellation, session invalidation, or
  genuine backgrounding. Paste/share content is never written to the Android
  clipboard, and the app cannot read the remote host clipboard. Separately, a
  user may choose to copy a public certificate fingerprint from certificate
  review; that value then enters Android's system clipboard and is subject to
  the keyboard, clipboard manager, and other enabled services' retention rules.
- An offline-update archive selected through Android's document picker is
  opened transiently and streamed once to the selected NanoKVM. The app does
  not request broad storage access, persist the document URI or permission,
  retain a filesystem path, or copy the archive into app storage.
- Terminal/serial output, script content, automation editor content, PicoClaw
  provider keys, and PicoClaw chat/history content are memory-only **in the
  Android app**. Closing or invalidating the owning surface clears the local
  copies; they are not added to Android command history, app storage, or
  diagnostics. NanoKVM/PicoClaw and the configured provider can separately
  process or retain configuration and conversation data under their own
  settings and terms; deletion on Android does not delete those remote copies.

## Network communication

The app's authenticated REST and WebSocket control traffic goes to the HTTPS
origin configured in the selected profile. A system-trusted certificate is
accepted normally. A private or self-signed leaf can be reviewed and pinned to
that origin. Optional WebRTC media negotiation is described separately below.

The inspection-only TLS probe temporarily accepts the presented chain so it can
retrieve the leaf certificate, then independently checks hostname and validity
and returns metadata for user review. It sends no password, token, cookie, or
application request. The probe is discarded after inspection and is never used
for normal application traffic. Authenticated profiles are HTTPS-only and may
not downgrade to cleartext HTTP.

Android cleartext transport is disabled. Initial NanoKVM access-point setup must
be completed outside the app before creating an HTTPS profile.

Passwords and provider keys are removed promptly from app-owned state and
mutable buffers when their operation ends. Android text fields, the selected
IME, JSON/header serialization, and the JVM may create immutable string copies;
the app cannot guarantee those external or runtime-managed copies are zeroed.

The app does not contact a NanoKVM until the user requests a connection. It has
no developer-operated cloud endpoint. Android and the user's installed keyboard
or accessibility services may process interaction according to their own
settings and privacy policies. The remote-input field does not force the
keyboard into no-personalized-learning/incognito mode, so keyboard-provided
voice typing remains available; typing history, personalization, voice audio,
and transcripts are governed by the selected keyboard's permissions and privacy
settings. The app itself requests no microphone permission, receives no voice
audio, and does not persist committed remote-input text.

When the user explicitly selects WebRTC, signaling remains on the authenticated
NanoKVM origin, but the appliance can supply ICE server URLs and temporary
credentials. Android's native WebRTC stack may contact those STUN/TURN endpoints
and exchange ICE connectivity traffic with candidate peers to establish the
video path. The app bounds and validates the reported ICE URL schemes, does not
persist the ICE credentials, and tears the peer down when the attempt ends.
**Auto** video does not initiate WebRTC; it uses direct H.264 and then MJPEG.

If the user starts a remote image download, online application update,
Tailscale installation, or PicoClaw installation, NanoKVM—not this app—may
fetch packages or images from the upstream service selected by the appliance.
The app sends a reviewed HTTP(S) URL only for remote images. Opening a Tailscale
login uses the official HTTPS login origin in the user's browser. Configuring
PicoClaw sends the transient provider key to the trusted NanoKVM over the
selected HTTPS session; the appliance may then contact the provider selected by
the user under that provider's terms.

The app's user-relevant permissions are `INTERNET`, `ACCESS_NETWORK_STATE`,
`ACCESS_LOCAL_NETWORK`, `USE_BIOMETRIC`, and the Android 8 fingerprint
compatibility permission (limited to API 27 and older). They support direct
communication with the selected appliance, WebRTC's connectivity monitor, and
Android's system authentication prompt. `ACCESS_NETWORK_STATE` lets native
WebRTC process connectivity and interface/address metadata; it does not grant
packet-payload access or Wi-Fi scanning. WebRTC logging is disabled at
`LS_NONE` so that metadata is not written to logcat. On Android 17 and later,
local-network access is requested only after the user chooses Connect; denial
leaves the profile usable and offers a recoverable retry or system-settings
route without starting a transport. The app
requests no location, storage, camera, microphone, notification, or
background-service permission. The merged manifest also contains AndroidX's
package-scoped dynamic-receiver permission, a DUMP-protected Profile Installer
receiver, and an unexported startup provider; these library components are not
user-granted capabilities and are reviewed for every release candidate.

## Retention and deletion

A profile and its saved credential remain until the user removes them, resets
corrupt profile storage, clears app storage, or uninstalls the app. Profile
deletion first verifies credential removal. If the profile write then fails,
the app reports that the protected password was removed but the profile remains.
Changing the address, protocol, port, or username is blocked while a saved
password exists; the user must explicitly remove that credential first.

Transient profile-storage unavailability offers retry and does not delete data.
Confirmed corrupt profile storage requires an explicit reset. Reset removes all
user-saved profile records, certificate decisions, and protected credentials,
then returns to an empty connection catalog. The release UI must describe that
consequence before performing it. Clearing app storage or uninstalling
removes application files and Keystore aliases according to Android platform
behavior.

Disconnecting clears active session and transport state. The app does not call
the NanoKVM logout endpoint because supported firmware can rotate a shared
server secret and affect other clients.

## Screen privacy

Non-debug builds prevent Android Recents previews and set `FLAG_SECURE`, which
blocks ordinary screenshots, screen recording, and non-secure displays. This
does not protect against a rooted/compromised device, malicious input or
accessibility service, physical control of an unlocked device, or an external
camera.

## Diagnostics and user reports

The app does not automatically collect or transmit diagnostics. If a user
chooses to attach logs, screenshots, traces, or profile details to an issue,
that submission is controlled by the user and handled by the service on which
the issue is filed. Users should redact hosts, usernames, certificate
fingerprints, local IP and interface data, SSID/BSSID/MAC values, ICE/STUN/TURN
URLs or credentials, candidates, SDP, terminal/chat contents, remote-screen
contents, and other private information.

The implementation is publicly inspectable under GPL-3.0-or-later. Security
concerns should be reported using [SECURITY.md](SECURITY.md).
