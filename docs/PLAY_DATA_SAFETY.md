# Google Play Data Safety worksheet

Status: **provisional; do not submit until an exact-candidate traffic audit is
complete**.

The absence of a publisher backend does not automatically mean “no data
collected.” Google defines collection to include user data transmitted off the
device by an included library or SDK, even when it goes to a third party.
Google separately excludes data processed only on-device and qualifying
end-to-end-encrypted sender-to-recipient transfers. A user-initiated transfer
may be excluded from “sharing,” but that does not by itself settle whether a
data type is “collected.”

## Provisional top-level answer

Do not select **No data collected or shared** yet. That answer becomes
supportable only if all of the following are demonstrated for the exact Play
artifact:

- every off-device user-data flow is a qualifying direct, encrypted transfer
  between the user and the selected appliance/peer;
- no included SDK or library transmits a Play Data Safety data type to another
  party;
- the actual WebRTC ICE configuration and traffic do not create a declarable
  third-party flow; and
- PicoClaw is absent, or every appliance/provider flow it enables has been
  separately classified and the AI policy gate has also been cleared.

Save the form as a draft while any condition remains unresolved.

The final Data Safety form covers the combined behavior of every version active
on any Play production, open, or closed track, not only the newest upload.
Internal-testing-only versions are treated separately by Google's form rules.
Before submission, deactivate stale non-internal artifacts whose behavior is
not represented here (especially any PicoClaw-enabled build), or expand the
answers and privacy notice to cover the union of all still-distributed versions.

## Current data-flow inventory

| Flow | Destination and trigger | Local handling | Provisional Data Safety treatment | Exact-candidate check |
| --- | --- | --- | --- | --- |
| Profile display name, host, port, username and optional certificate pin | No transmission until the user connects to that profile | App-private DataStore; excluded from backup/transfer | On-device storage is out of collection scope; values used for a direct appliance connection require the encrypted-transfer analysis below | Confirm backup rules and no diagnostics/telemetry |
| Saved password | Selected NanoKVM after explicit connect/unlock | Opt-in AES-256-GCM record bound to Android Keystore authentication; no-backup storage | On-device encrypted record is out of collection scope; appliance login is a direct destination-bound flow if only sender/recipient can read it | Verify HTTPS, logs and teardown on exact build |
| Username, password and session token | User-selected NanoKVM over authenticated HTTPS/WebSocket | Secrets are operation/session memory only except opt-in protected password | Candidate for Google's end-to-end-encrypted sender/recipient exclusion; publisher cannot rely on it without verifying no readable intermediary or developer endpoint | Capture destinations and TLS properties; review any reverse proxy used by the fixture |
| Remote video | Received from the selected NanoKVM | Decoded for display, not saved or uploaded | Receiving content is not collection unless the app forwards it; verify no screen/diagnostic SDK does so | Exercise H.264 and MJPEG while recording all outbound destinations |
| Keyboard, pointer, clipboard/share text, power/GPIO and administration commands | Selected NanoKVM after a user action | Bounded/previewed where applicable; no developer queue | Candidate direct encrypted sender/recipient flow; clipboard/share is also specifically user initiated | Confirm destination binding and no third-party endpoint |
| Public certificate fingerprint copied by the user | Android system clipboard after an explicit copy action | Public value leaves app-owned state; Android/keyboard/clipboard services control later retention | Assess whether the public appliance value maps to any Play user-data type; if it does, the explicit user-initiated sharing exception may apply | Exercise copy and inspect the exact value/receivers; do not confuse it with a private signing key |
| Offline update archive | Streamed once from Android's document picker to the selected NanoKVM | URI/path/permission not persisted; no broad storage access | Candidate direct encrypted user-initiated transfer; do not describe it as Android app installation | Confirm only appliance destination receives bytes |
| Terminal output and scripts | Selected NanoKVM | Memory-only in Android app | Candidate direct encrypted sender/recipient flow | Verify no logging or crash reporting |
| Certificate inspection | Selected host before trust decision | Inspection metadata only; no password/token/application request | Network destination is the user-entered host; no developer collection identified | Verify probe sends no application request and no report |
| Optional WebRTC ICE | Appliance-supplied STUN/STUNS/TURN/TURNS servers and candidate peers, only after the user selects WebRTC | ICE credentials are bounded and not persisted; native logging disabled | **Unresolved.** ICE can disclose public/local address or interface metadata to an appliance-supplied endpoint. Third-party ownership, retention, purpose, encryption and Play data-type mapping must be established | Capture signaling, DNS and network destinations using the fixture's real ICE list; document endpoint owners and terms |
| Optional WebRTC media | Candidate peer or TURN relay | Peer torn down when attempt ends | Media is normally WebRTC-encrypted, but do not assume the ICE metadata shares the same treatment | Verify DTLS-SRTP/relay behaviour and fallback teardown |
| Tailscale login | Short-lived, allowlisted official HTTPS authorisation URL opened only when the user presses the external-login button | App redacts the token-bearing URL from strings/logs and does not control an in-app WebView | Sending the URL to another app is an on-device transfer. Determine whether it maps to a Play user-data type; if it does, document the specific user-initiated sharing exception rather than ignoring the transfer | Verify external browser intent, URL bounds/origin/path, redaction and absence from retained state |
| Remote image/application/Tailscale/PicoClaw package fetch | Fetch occurs on NanoKVM after an explicit appliance action | App sends the reviewed URL or command to the appliance | The Android app does not fetch the package; classify only data actually sent by Android to the appliance | Verify there is no Android download/install path |
| PicoClaw provider key and chat/history | Android app sends values to NanoKVM; NanoKVM may contact the selected provider | Memory-only in Android; remote copies may exist | If present, likely requires classification of credentials and “Other in-app messages,” provider ownership/retention and user-initiated sharing; also triggers the AI policy gate | The custom `play` variant disables this path; prove no UI entry point, background probe or reachable code path in the exact artifact |
| Biometrics/device credential | Android system authentication prompt | App receives success/failure, not biometric templates | No biometric data collection identified | Verify dependencies and logs |
| User-created support report | User leaves the app to submit an issue/email manually | No automatic diagnostic upload | Not automatic app collection; the destination service's terms apply to the user's deliberate submission | Ensure there is no hidden report SDK |

## WebRTC/STUN/TURN decision record

For each ICE URL observed from the release review fixture, record:

| Field | Value |
| --- | --- |
| Exact AAB/APK SHA-256 | |
| User action that initiates ICE | |
| ICE URL scheme and redacted host | |
| Endpoint owner/operator | |
| Is it controlled by the user, publisher, appliance vendor, or another party? | |
| Data visible to endpoint (source/public IP, local candidate, credentials, identifiers) | |
| Does the endpoint infer approximate/precise location from an IP address? | |
| Does it receive a stable device or other identifier? | |
| Storage/retention beyond real-time request | |
| Purpose | |
| Transport protection for each declarable data type | |
| Can the user use core app functionality without WebRTC? | Yes; Auto uses direct H.264 then MJPEG, subject to exact-candidate verification |
| User-initiated sharing exemption rationale, if claimed | |
| Evidence link and reviewer | |

Do not map an IP address to **Approximate location** merely because IP traffic
exists; Google requires inferred physical location to be declared in that
category. Conversely, do not omit it if an endpoint actually uses the IP to
infer location. Do not select **Device or other IDs** unless a qualifying
device/browser/app identifier is actually transmitted. Obtain the endpoint's
retention and purpose rather than guessing.

If a declarable data type is sent only during the optional WebRTC action, it is
normally **optional**. Mark it **ephemeral** only when it is held solely in
memory and no longer than necessary to serve that real-time request; endpoint
logs defeat that rationale. Purpose would ordinarily be **App functionality**.
If declarable data travels through `stun:` or `turn:` rather than an encrypted
scheme, do not claim that all collected data is encrypted in transit without a
specific protocol-level basis.

## Form completion branches

### Branch A: audit supports no collection/sharing

- Collects or shares required user-data types: **No**.
- Privacy policy: `{{PRIVACY_URL}}`.
- Explain consistently in the policy that data stays on-device or goes directly
  to the user's chosen appliance/peer and that the publisher has no service,
  analytics, ads or telemetry.
- Attach the destination inventory and packet-capture review to release evidence.

### Branch B: audit finds a declarable WebRTC or provider flow

- Collects or shares required user-data types: **Yes**.
- Select only the data types actually evidenced. For each, record collection,
  sharing, required/optional, ephemeral handling, purpose and retention.
- Use the user-initiated-action exception only for the **sharing** question and
  only when the transfer is specific, expected and documented.
- Answer “all data encrypted in transit” only if true for every declared type.
- Do not claim a publisher deletion-request mechanism for data retained by an
  independent endpoint unless one genuinely exists and is operated.
- Update the public privacy policy and listing before the same release is sent
  for review.

## Exact-candidate audit checklist

- [ ] Record AAB SHA-256, delivered APK SHA-256, version, package and signer.
- [ ] Review the merged manifest and complete release dependency/SDK inventory.
- [ ] Fresh install; remain disconnected and confirm no unexplained outbound
  traffic.
- [ ] Add a review profile, inspect its certificate and connect over HTTPS.
- [ ] Exercise direct H.264 and MJPEG; inventory DNS names, IPs and protocols.
- [ ] Select WebRTC explicitly; capture signaling, ICE servers, candidate/relay
  traffic and fallback teardown.
- [ ] Exercise clipboard/share and offline update with harmless synthetic data.
- [ ] Confirm browser launches are external and appliance-side package fetches
  do not originate on Android.
- [ ] Confirm PicoClaw is absent from the Play variant or fully classify it.
- [ ] Search logs and app-private storage for secrets, content and destination
  metadata after backgrounding/disconnect.
- [ ] Reconcile observations with the public privacy policy.
- [ ] Have the publisher approve the final Console answers and retain a Console
  export or screenshots with release evidence.

## Approval record

| Item | Value |
| --- | --- |
| Version name/code | `0.3.7` / `14` |
| Exact AAB SHA-256 | |
| Delivered APK set / device | |
| Traffic evidence | |
| Final top-level collection answer | |
| Declared data types and purposes, if any | |
| Encryption-in-transit answer and basis | |
| Deletion answer and basis | |
| Privacy-policy revision/URL | `{{PRIVACY_URL}}` |
| Technical reviewer/date | |
| Publisher approval/date | |

## Official reference

Google's [Data Safety form guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
defines collection, sharing, on-device and end-to-end-encryption exclusions,
ephemeral processing, data types and purposes. Requirements were reviewed on
2026-08-02; the live form and current policy take precedence.
