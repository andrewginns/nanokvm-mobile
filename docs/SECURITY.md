# Security design

This document describes implemented controls and their limits. Passing release
evidence is tracked separately in `MODERNIZATION_AUDIT.md` and
`RELEASE_CHECKLIST.md`; source code alone is not evidence that a device journey
passed.

## TLS and endpoint identity

The NanoKVM origin, authenticated signaling, and application control traffic
require HTTPS. The manifest and Network Security Config both deny cleartext,
and initial access-point setup is outside the app. System trust is the default.
A private or self-signed NanoKVM can be pinned by the SHA-256 digest of its leaf
certificate to one canonical HTTPS origin. Explicit WebRTC mode may separately
contact validated appliance-supplied STUN/STUNS/TURN/TURNS peers for ICE.

Certificate discovery uses a short-lived inspection client whose trust manager
accepts the presented chain only to retrieve certificate metadata. That client:

- sends no password, token, cookie, or application request;
- independently verifies the requested hostname and certificate validity;
- returns metadata for an explicit user decision; and
- is discarded and never reused for login, video, input, or REST traffic.

There is no global hostname-verification bypass, installed CA, reusable
trust-all client, or application-data trust-all mode. This distinction must be
preserved when interpreting automated checks that flag the inspection trust
manager.

A saved-pin mismatch is a hard failure. It is never accepted or rotated
automatically. The review UI compares the stored and presented fingerprints and
offers three explicit outcomes: reject, connect once without changing the saved
pin, or replace the saved pin. The decision is bound to the inspected origin and
certificate generation. Retained real-device expiry and recovery rehearsal
remain release evidence gates.

## Credentials and session tokens

Trust preflight completes before the app collects a password or asks Android to
unlock a saved one. NanoKVM expects its browser-compatible encrypted password
payload and returns a JWT; that compatibility cipher is not treated as a
security boundary. TLS provides transport confidentiality and integrity.

Passwords are mutable connection-attempt buffers owned by `AppViewModel` and
the active request. JWTs are origin-scoped and memory-only. Profile DataStore
records contain only profile ID, display name, address, HTTPS setting, port,
username, and optional public certificate fingerprint. They contain neither
passwords nor video preferences.

Saving is explicit. AES-256-GCM encrypts the password with a non-exportable
Android Keystore key requiring recent strong-biometric or device-credential
authorization. Ciphertext is bound as authenticated additional data to profile
ID, scheme, canonical host, explicit port, and trimmed case-sensitive username.
The staged envelope is committed to `noBackupFilesDir` only after successful
NanoKVM login, so a failed replacement cannot overwrite a working credential.
Changing login identity is blocked until the user explicitly removes the saved
credential.

The Activity-retained authentication coordinator owns only non-secret prompt
metadata and request/host IDs. `AppViewModel` owns the password and pending
save/unlock action. Request IDs and host generations reject stale callbacks
after recreation. Genuine backgrounding cancels connection and non-prompt
secret work. Because Android's system authentication UI may itself stop the
Activity, its prompt-owned action is retained only until a terminal callback; a
callback received while stopped clears the mutable buffer and is forbidden from
connecting. Teardown and all other terminal connection paths clear their owners.
Device tests must still verify real Keystore authorization expiry, invalidation,
deletion, and process-death behavior.

Normal disconnect forgets local session state without calling the NanoKVM
logout endpoint, which can rotate a shared server secret and affect other
clients.

## Local storage and display

- Backup and device transfer are disabled in the manifest and exclusion rules.
- Protected credentials live in `noBackupFilesDir`; profiles live in private
  DataStore.
- Transient profile-storage unavailability offers retry. Confirmed corruption
  requires an explicit reset that removes all user-saved profile records, pins,
  and credentials before returning to an empty connection catalog.
- Non-debug builds disable Recents screenshots and set `FLAG_SECURE`.
- The application has no analytics or automatic diagnostics. It currently has
  no application diagnostic logging path; future logging must prohibit secrets,
  cookies, request bodies, fingerprints, and framebuffer contents.

## Parser and resource bounds

REST bodies, H.264 payloads, input messages, MJPEG headers/frames, credential
records, paste input, endpoint syntax, certificate pins, session tokens, GPIO
duration, and video settings have application-level validation. Clipboard and
share text over 1,024 normalized UTF-8 bytes is rejected before retention, and
session tokens over 2,048 characters are rejected before storage/use. Video
queues and latest-frame dispatch are bounded; key/button transitions are
ordered rather than dropped. Certificate subject and issuer display values are
limited to 1,024 UTF-8 bytes each, each alternative name to 512 bytes, and the
combined alternative-name list to 64 entries. Controls, bidi/format characters,
line separators, and malformed surrogates are neutralized while preserving the
verified identity and full fingerprint for review.

WebSocket compression is disabled at the handshake: the client removes
OkHttp's unconditional `permessage-deflate` offer and rejects an unsolicited
server extension. This prevents a small compressed wire payload from expanding
before an application callback. Oversized direct-H.264 input cancels its socket
and enters normal fallback immediately; input control stops command acceptance,
queues HID releases, and all session WebSockets use a two-second close timeout.

An important transport-layer limitation remains: OkHttp materializes a complete
uncompressed WebSocket message before the H.264, terminal, or input callback can
apply its size check. Deterministic slow-fragment tests confirm cumulative
buffering before callback delivery. The checks bound parser copies, downstream
decoder work, compressed amplification, and post-rejection lifetime, but not the
first uncompressed allocation. Do not claim an end-to-end WebSocket memory bound
until a pre-allocation-capped transport exists or measured physical-device risk
is explicitly accepted.

## Lifecycle, reconnect, and control safety

- Every background, cancellation, disconnect, and generation transition emits
  or schedules zero HID state before transport ownership is dropped.
- Reconnect is foreground-only, bounded, and limited to transient transport
  failures. Trust, authentication, firmware, and terminal protocol failures
  stop it.
- Remote input, paste, GPIO, power, and reset actions are never persisted,
  queued for later replay, or automatically retried.
- Destructive controls require consequence confirmation. Runtime execution
  rejects duplicate command keys, serializes commands, and invalidates leases
  when the session generation changes. UI confirmations are ephemeral and are
  cleared on profile, authority, connection, or generation change before they
  can target a replacement session.
- `closeAndAwait()` defines deterministic backend shutdown; cancellation and
  lifecycle tests remain required evidence for every production candidate.
  Current deterministic tests cover cancellation-ignoring callbacks and 64
  repeated background/reconnect/foreground/close cycles; an API 37 process
  replacement test confirms only a non-secret profile draft is restored.

## Permissions and platform surface

One Activity is exported. It owns the launcher entry and a deliberately narrow
`ACTION_SEND` `text/plain` share target. Shared text is treated as untrusted,
bounded input: it opens the normal destination-bound paste preview and is never
typed automatically. There are no deep links, WebViews, PendingIntents,
services, content providers, background workers, or notifications owned by the
app. Merged-manifest evidence is still reviewed because AndroidX may contribute
protected components.

`INTERNET`, `ACCESS_NETWORK_STATE`, Android 17's `ACCESS_LOCAL_NETWORK`, and
biometric/fingerprint prompt permissions support implemented features.
`ACCESS_NETWORK_STATE` lets native WebRTC process connectivity and
interface/address metadata; it does not grant packet-payload access or Wi-Fi
scanning. WebRTC installs a no-op logger at `LS_NONE` so native network, ICE,
candidate, and SDP diagnostics do not enter logcat. Local-network access is
requested contextually only after Connect and is checked again before trust
preflight or transport startup; denial starts no network work, and revocation
tears down an active session and clears pending secrets. The fingerprint
compatibility permission is limited to API 27 and older. No location, storage,
camera, microphone, notification, or background-service permission is declared.

The merged manifest also contains AndroidX's package-scoped
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a `DUMP`-protected exported Profile
Installer receiver, and an unexported Initialization Provider. These are not
user-granted capabilities, but their protection/export state remains part of
the signed merged-manifest review.

## Build and distribution trust

The repository restricts dependency repositories, pins dependency versions,
checks the Gradle distribution hash, and enables strict Gradle verification
metadata. Release builds use R8 and resource shrinking. The local strict build
can generate a CycloneDX SBOM, R8 mapping/usage files, an unsigned APK/AAB, and
versioned Baseline/Startup Profiles. GitHub-hosted CI and public build-artifact
publication are not used as an unattended trust boundary. Maintainers build and
review locally; only the audited production-signing flow in `DISTRIBUTION.md`
may create a public pre-release bundle. Ordinary local outputs, including the
unsigned release, debug-signed benchmark, mapping, and configuration, remain
private evidence and are not public release assets.

Those controls do not make an unsigned artifact a release. A public candidate
is signed through the documented release process and retains source,
licence/notice material, SBOM, dependency-vulnerability review result,
checksums, signing identity, mapping/usage output, and signed/minified smoke
evidence. Reproducibility requires two isolated builds to match after accounting
for the documented signing step. See `DISTRIBUTION.md`.

R8 is an optimization and attack-surface-reduction tool, not an anti-tamper
security boundary for GPL software. Play Integrity, Credential Manager/passkeys,
WebView hardening, deep-link validation, PendingIntent policy, WorkManager, and
foreground-service controls are not applicable to the current local-only
architecture and must be reassessed if that architecture changes.
