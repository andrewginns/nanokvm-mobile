# Technical security model

This document defines NanoKVM Mobile's implemented security boundaries, threat
assumptions, accepted risks, and verification expectations. Vulnerability
reporting and supported-version policy are in the repository's root
[`SECURITY.md`](../SECURITY.md).

## Scope and assurance boundary

NanoKVM Mobile is a foreground-only Android client that connects directly to a
NanoKVM selected by the user. It has no developer-operated backend, central
account, analytics, advertising, telemetry, or automatic crash reporting.

The app is designed to protect:

- NanoKVM passwords and authenticated session tokens;
- saved profiles and per-profile certificate decisions;
- remote framebuffer content and Android clipboard/share text;
- keyboard, pointer, GPIO, power, update, network, terminal, script, virtual
  media, automation, and PicoClaw actions; and
- the relationship between public source and a signed release artifact.

The trust boundaries are the Android UI and IME, app-private storage and Android
Keystore, the selected HTTPS NanoKVM origin, transport parsers and decoders,
session generations and feature controllers, and source/dependency inputs used
to create a signed APK.

The model does not defend against a rooted or compromised Android device, a
malicious system keyboard or accessibility service, physical control of an
unlocked device, an external camera, or malicious NanoKVM firmware the user has
explicitly trusted. The app cannot replace the appliance's authorization,
rate-limiting, update-integrity, or GPIO safety controls.

## Endpoint trust and authenticated sessions

- A saved profile identifies one canonical HTTPS authority. Authenticated
  application control, signaling, REST, and WebSocket traffic must not use
  cleartext. Initial access-point setup is deliberately outside the app.
- System certificate trust is preferred. For a private or self-signed
  appliance, a short-lived inspection client retrieves certificate metadata
  without sending a password, token, cookie, or application request. It checks
  the requested hostname and certificate validity and is discarded before
  normal traffic begins.
- An explicitly accepted leaf-certificate SHA-256 pin is scoped to one saved
  origin. A changed pin is a hard stop. The user must reject it, connect once
  without replacing the saved pin, or explicitly replace the saved pin after
  comparing the old and new identities. Rotation is never automatic.
- Trust preflight completes before password collection or saved-password
  unlock. The NanoKVM-compatible password cipher is interoperability behavior,
  not a security boundary; HTTPS supplies transport confidentiality and
  integrity.
- The authenticated `nano-kvm-token` cookie is memory-only and origin-scoped.
  Redirects are disabled so credentials, cookies, and mutation bodies cannot
  cross origins. An authentication failure tears down the authenticated
  session rather than leaving feature clients active.
- Every privileged approval, capability snapshot, and feature handle is bound
  to the profile, authority, and session generation. Reconnect invalidates
  prior approvals, catalogs, handles, queued work, and transport callbacks.
- Normal disconnect clears local session state without invoking the NanoKVM
  logout route because supported firmware may rotate a shared server secret
  and disrupt other clients.

## Credentials, local data, and display

Profiles contain connection metadata and an optional public certificate pin,
not passwords or session tokens. They are stored in app-private DataStore and
excluded from Android backup and device transfer.

Saving a password is a separate opt-in operation. The app encrypts it with
AES-256-GCM using a non-exportable Android Keystore key authorized by strong
biometric or device credential. Authenticated additional data binds the record
to the profile ID, scheme, canonical host, explicit port, and username. The
encrypted record lives in `noBackupFilesDir` and a replacement is committed
only after the new login succeeds. A profile's login identity cannot be changed
while a protected password remains attached to it.

Passwords are held in mutable, short-lived operation buffers. Secrets are not
written to saved instance state, diagnostics, notifications, the clipboard, or
source control. Genuine backgrounding cancels connection and non-prompt secret
work. Android's authentication UI may temporarily stop the Activity; a prompt
result received while the app is stopped clears its password buffer and cannot
connect. Success, failure, replacement, disconnect, and teardown also clear the
relevant owners.

Profile deletion verifies removal of the protected credential and reports a
partial outcome if the later profile write fails. Temporary storage failure
offers retry without deleting data. Confirmed corruption requires an explicit
reset whose consequence includes profiles, certificate pins, and protected
credentials.

Non-debug builds set `FLAG_SECURE` and disable Recents previews. This blocks
ordinary screenshots, recordings, and non-secure displays, but not the
out-of-scope device and physical threats above. The app does not persist remote
framebuffer, terminal, chat, script output, or operator history.

## Input, clipboard, lifecycle, and command safety

Android clipboard and share-target text is typed as USB HID input. It is not a
shared host clipboard and the app cannot read the controlled host's clipboard.
Only direct plain text is accepted; provider-backed, URI, intent, and rich
content is rejected rather than dereferenced. Normalized input above 1,024 UTF-8
bytes is rejected before retention. Accepted text is previewed with its target
and keyboard layout and is never sent automatically.

Paced typing and held HID state have one owner and an explicit cancellation
path. Backgrounding, disconnect, reconnect, destination change, transport
replacement, or a conflicting input/control action cancels the operation and
releases neutral keyboard and mouse reports. Interrupted input is never resumed
or replayed in a later session.

Persistent, disruptive, destructive, root-equivalent, and externally writing
operations follow these rules:

- show the exact destination, value, or opaque catalog item and explain the
  consequence before dispatch;
- bind approval to the latest snapshot and session generation;
- serialize conflicting mutations and dispatch each approved operation at most
  once;
- do not persist, retry, or replay a mutation after timeout, EOF, reconnect,
  process recreation, or an ambiguous acknowledgement; and
- reconcile with a fresh authoritative read when possible, otherwise report
  that the result is unknown and require a new decision.

These rules apply to HID paste and shortcuts, power/reset, virtual media,
Wake-on-LAN history, password and appliance configuration, updates, terminal
and serial bytes, scripts and autostart, network and Tailscale changes, and
PicoClaw operations. TLS disable is outside the app's security policy.

Virtual-media actions use opaque handles from the latest appliance catalog;
arbitrary server paths are not accepted. Remote image URLs are validated,
credential-free HTTP(S) URLs, and the appliance - not the phone - fetches the
image. The app does not claim cancellation, checksum verification, or success
when the appliance protocol cannot provide it.

System and serial terminals are root-equivalent appliance sessions. They are
foreground-only, do not reconnect automatically, and retain no command history.
Script names and serial options come from bounded catalogs or typed allowlists;
user text is not interpolated into a shell command.

## Video transports and optional network peers

Direct H.264 and MJPEG remain inside the selected HTTPS origin. Explicit
WebRTC mode adds one documented exception: native ICE negotiation may contact
bounded, validated STUN, STUNS, TURN, or TURNS URLs supplied by the trusted
NanoKVM. Those peers can observe ordinary connection metadata. The app receives
video only and requests no camera or microphone capability.

WebRTC offers, answers, candidates, credentials, URLs, interface details, and
SDP must not enter app logs. Negotiation belongs to one session generation and
is never replayed. Failure tears down that generation before a fresh fallback
attempt proceeds to direct H.264 and then MJPEG.

PicoClaw is an optional appliance extension with broad filesystem, command, and
host-HID authority. It is probed only after explicit feature entry. Provider
keys remain memory-only on Android and are sent only through the trusted
NanoKVM session. A PicoClaw manual-HID lock remains visible until the session is
explicitly released; cancelling chat does not imply that the lock was released.

## Malformed and resource-exhausting endpoints

Application-level bounds cover REST bodies, credentials, session tokens,
certificate display fields, clipboard text, H.264 payloads, input messages,
MJPEG headers and frames, terminal/script content, GPIO duration, and video
settings. Video queues and latest-frame dispatch are bounded; stale frames may
be dropped, but ordered key and button transitions are not.

WebSocket compression is removed from the handshake and unsolicited
`permessage-deflate` negotiation is rejected. Oversized H.264 or input messages
terminate their transport and enter the normal release or fallback path.

One residual availability risk remains: OkHttp materializes a complete
uncompressed WebSocket message before the application callback can apply its
size limit. The app bounds downstream copies, decoder work, and post-rejection
lifetime, but cannot claim a pre-allocation or end-to-end WebSocket memory bound.
A release must either retain measured acceptance of that risk on representative
hardware or introduce a transport that caps allocation before callback delivery.

## Android platform surface

The app exports one Activity for the launcher and a deliberately narrow
`ACTION_SEND` `text/plain` share target. Shared text always enters the bounded
preview flow. The app owns no deep links, WebViews, PendingIntents, services,
content providers, background workers, or notifications.

The user-relevant permissions are Internet access, connectivity state, Android
17 local-network access, and biometric/device-credential prompting. Local-
network permission is requested only after Connect; denial starts no network
work and revocation tears down the session and pending secrets. WebRTC uses
connectivity and interface metadata but adds no location, storage, camera,
microphone, notification, or background-service permission.

Release review must inspect the merged manifest because AndroidX contributes a
package-scoped dynamic-receiver permission, a `DUMP`-protected Profile Installer
receiver, and an unexported startup provider. New exported components, inbound
links, WebViews, background execution, or data recipients require this threat
model to be revisited.

## Dependency and distribution trust

Dependency repositories are restricted, versions are explicit, the Gradle
wrapper distribution is checksummed, and artifacts use strict Gradle dependency
verification metadata. Dependency changes require a reviewed resolved-graph and
verification-metadata diff, licence and vulnerability review, and an updated
[`DEPENDENCIES.md`](DEPENDENCIES.md).

Local debug, unsigned release, and benchmark outputs are engineering artifacts,
not public releases. A public APK must follow [`DISTRIBUTION.md`](DISTRIBUTION.md)
and [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md): protected signing identity,
monotonically increasing version code, exact corresponding source, checksum and
signer evidence, complete licence/notices, SBOM, and smoke testing of the exact
published bytes. R8 reduces shipped surface but is not an anti-tamper or trust
boundary for GPL software.

## Accepted and not-applicable risks

- Exact leaf pinning is deliberately renewal-sensitive. The user must review a
  changed identity; automatic rotation would weaken the trust boundary.
- Android Keystore authorization uses a short reusable authorization window and
  permits strong biometric or device credential. Per-operation biometric-only
  authorization and power-action step-up are not product requirements.
- Java, Android, crypto, and encoding APIs may create short-lived immutable
  copies. Clearing mutable owners cannot guarantee erasure of every runtime or
  operating-system copy.
- A trusted appliance controls authorization semantics, token lifetime,
  firmware behavior, GPIO results, script execution, and optional third-party
  peers. The app fails closed where it can observe a violation but cannot make
  trusted malicious firmware safe.
- Remote attestation, passkeys, WebView/deep-link hardening, PendingIntent
  policy, WorkManager, foreground-service policy, and anti-tamper controls are
  not applicable while the corresponding components do not exist.

## Verification guidance

Source tests demonstrate implementation behavior; they do not substitute for
testing the signed APK, Android platform, physical input hardware, or a real
NanoKVM. Follow [`TESTING.md`](TESTING.md) and retain the exact source commit,
APK hash and signing digest, Android device/API, NanoKVM hardware/application
version, commands or manual steps, redacted evidence, and result.

At minimum, affected changes and releases should verify:

| Area | Required verification |
| --- | --- |
| Network trust | Cleartext rejection, data-free inspection, hostname/date checks, self-signed acceptance, pin mismatch and recovery, redirect/cookie origin, and authentication teardown |
| Credentials and storage | Real Keystore success/cancel/expiry/invalidation/deletion, failed replacement, background and process death, backup exclusion, storage failure/corruption/reset, and partial deletion |
| Input and lifecycle | HID release on cancellation/background/disconnect, no stale callbacks, bounded reconnect, no paste or mutation replay, and ambiguity recovery |
| Platform privacy | Merged permissions/components, local-network denial/revocation, filesystem and backup behavior, Recents/screenshot blocking, logcat review, and observed traffic |
| Video and extensions | Parser bounds, transport teardown, WebRTC peer/logging disclosure, fresh fallback, and capability-gated real-appliance tests for disruptive features |
| Release artifact | Package/version identity, signing lineage, upgrade without clearing data, exact-byte install/launch, source/SBOM/licence/checksum bundle, and dependency vulnerability disposition |

Automated security scanners may flag the inspection-only trust manager or the
public NanoKVM compatibility cipher. Review those findings against the actual
data flow: the inspection client is ephemeral and credential-free, and HTTPS -
not the compatibility cipher - is the confidentiality and integrity boundary.

Primary implementation evidence includes:

- [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) and
  [`network_security_config.xml`](../app/src/main/res/xml/network_security_config.xml);
- `CertificateInspector`, `EndpointTrustPreflight`, `SavedCredentialStore`, and
  `CredentialAuthenticationCoordinator` in the app and protocol modules;
- the runtime control gate and generation-bound feature contracts;
- [`verification-metadata.xml`](../gradle/verification-metadata.xml),
  [`DEPENDENCIES.md`](DEPENDENCIES.md), and the release documentation above.
