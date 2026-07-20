# NanoKVM Mobile security model

This document records the security boundaries which must remain true as the
native app approaches the official NanoKVM 2.4.3 WebUI feature set. The pinned
reference is tag commit `3b2ba7c0c1214f44da9d328f90bbdd025fac0413`.

## Trust and session boundary

- A profile identifies one exact authority and TLS identity. A self-signed
  certificate may be explicitly pinned after review; a changed certificate is
  a new trust decision and is never accepted automatically.
- Certificate subject/issuer/alternative-name text is untrusted display data.
  It is Unicode-neutralized and bounded before presentation, the verified
  identity retains a display slot, and any shortening is disclosed without
  shortening the SHA-256 fingerprint used for the trust decision.
- Passwords are mutable, short-lived values. Saved credentials are encrypted by
  Android Keystore and released only after biometric or device-credential
  authentication. Passwords, JWTs, API keys, clipboard contents, terminal
  bytes, framebuffer pixels, server paths, and remote-image URLs are excluded
  from logs and saved state.
- NanoKVM authentication is the `nano-kvm-token` cookie. REST and WebSocket
  clients attach it as a cookie, never a bearer/query token. An HTTP 401 tears
  down the whole authenticated session.
- The NanoKVM origin, authenticated signaling, and application control traffic
  require HTTPS. Android cleartext permission is disabled; initial access-point
  Wi-Fi setup is completed outside the app before a profile is created. Explicit
  WebRTC mode may contact validated appliance-supplied ICE peers as described
  below.
- Every privileged approval is bound to profile id, authority, TLS identity
  where applicable, and a monotonically increasing session generation. A
  reconnect invalidates approvals, catalogs, handles, and queued work.

## Input and clipboard

The app has no agent on the controlled computer. "Paste Android clipboard to
remote" emits USB HID keystrokes; it is not clipboard synchronization and it
cannot read the host clipboard.

- Accept direct Android plain text only. Reject URI, intent, provider-backed,
  and rich content rather than dereferencing it.
- Analyze normalized text without allocating an encoded copy and reject values
  above 1,024 UTF-8 bytes before the app retains clipboard/share content.
- Show destination, target keyboard layout, size, unsupported-character
  warnings, and a preview before typing. Sensitive previews are hidden by
  default and cleared when the app leaves the foreground.
- Paced typing has one owner and a visible cancel path. A new keyboard chord,
  mouse button, scroll action, HID reset, power action, reconnect, background,
  or disconnect cancels and joins it before altering focus or HID state.
- Release neutral keyboard and mouse reports before and after a paced operation.
  Never resume or replay an interrupted paste on a new session.

## Virtual media and Wake-on-LAN

- UI code receives feature-typed, snapshot-bound image handles, never arbitrary
  server paths or `Any` tokens. Refresh immediately before mount/delete,
  prevent deletion of the live mount, and invalidate all handles on session
  change. Stale, foreign, and lookalike handles are rejected by identity.
- Mount/eject and virtual-device changes can reset the whole USB gadget. Cancel
  paste, release HID, explain host-I/O consequences, dispatch once, then read
  authoritative state. An empty mount request restores the physical storage
  partition; it does not mean an absent device.
- Remote image transfer accepts validated HTTP(S) URLs with no credentials or
  fragment and a safe `.iso`/`.img` basename. It never auto-mounts. NanoKVM
  2.4.3 has no stable cancel or checksum contract, so the app reports that
  limitation rather than inventing success.
- Wake-on-LAN canonicalizes MAC addresses. API acknowledgement proves only that
  `ether-wake` returned, not that the target powered on.

## Appliance administration

Persistent and connectivity-affecting changes require the exact destination
and proposed value to be reviewed. Password change, online update, reboot,
hostname/mDNS, SSH, DNS, and virtual USB operations are dispatched at most once.
After an ambiguous disconnect the app reads current state or gives recovery
guidance; it does not repeat the request.

Wi-Fi administration is manual because NanoKVM 2.4.3 exposes no network scan
route. An authenticated session may configure a typed SSID or disconnect.
Initial access-point setup is deliberately outside the app.

Password change updates both WebUI and appliance root credentials but does not
revoke the current JWT. On acknowledged success the app discards its local
session and updates a saved credential only after the new credential operation
has succeeded. TLS disable is outside the app's security policy.

## Terminal, serial, and scripts

The system terminal is a root-equivalent `/bin/sh` PTY. It is an explicit,
foreground-only elevated surface: no command history persistence, no background
connection, no automatic reconnect, and no input replay.

Serial mode uses the same shell to launch `picocom`. The device path and every
option come from typed allowlists; user text is never interpolated into a shell
command. Cleanup sends the one-shot picocom exit chord and closes the socket.

The 2.4.3 script backend does not safely constrain run/delete names and has no
foreground cancellation or timeout. The app therefore operates only on opaque
handles from the latest list, validates basenames/extensions, caps local upload
and displayed output, and never retries upload, run, or delete after an
ambiguous response. Cancelling the HTTP request does not stop a running script.

## Optional transports and PicoClaw

WebRTC negotiation state belongs to one connection generation. Offers and ICE
candidates are never replayed. Any fallback creates a fresh session in the
order WebRTC, direct H.264, then MJPEG. ICE may contact bounded, validated
STUN/STUNS/TURN/TURNS URLs supplied by the trusted NanoKVM; this is the only
non-origin network exception to the HTTPS application-control boundary.

PicoClaw is optional from application 2.4.0 and is probed only after explicit
feature entry: its status GET can start a probe loop and create configuration.
Starting it enables broad appliance filesystem/command access and host HID
control. Its gateway holds a global manual-HID lock for the connection (up to
the configured lock duration); cancelling a chat run does not release that
lock. The app must show the lock persistently and close/release the session to
restore manual control.

The app never attempts to obtain NanoKVM's loopback-only internal PicoClaw
token or call internal screenshot/action/MCP routes. Provider API keys remain
memory-only on Android, are sent only over a trusted TLS session, and are never
retried, logged, or echoed.

## At-most-once mutation ledger

Do not automatically replay any of the following after timeout, EOF, reconnect,
process recreation, or ambiguous acknowledgement:

- HID paste/chords whose delivery state is unknown;
- mount/eject/delete/remote-image start and virtual-device toggle;
- Wake-on-LAN send/history rename/delete;
- password, update, reboot, SSH, hostname, mDNS, title, DNS, and other appliance
  configuration writes;
- offline application archives (the one-shot stream is consumed before dispatch; reconnect and
  read the application version after any missing or malformed acknowledgement);
- terminal/serial bytes and picocom launch/exit;
- script upload/run/delete;
- WebRTC offers/candidates across generations;
- PicoClaw install/uninstall/start/stop, configuration, chat/cancel, histories,
  session release, and any host action.

Safe read/status calls may use bounded backoff. After an ambiguous mutation,
reconcile with an authoritative read when the server exposes one; otherwise
report the uncertainty and require a fresh user decision.
