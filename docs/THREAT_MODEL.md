# Threat model and mobile-security verification

## Scope and assurance target

NanoKVM Mobile is a foreground-only, direct-LAN controller for a user-owned
NanoKVM. It protects credentials, session tokens, profile/trust data, remote
screen contents, and remote-control actions from ordinary local apps,
passive/active network observers, accidental disclosure, stale lifecycle work,
and a faulty or unexpectedly hostile configured endpoint.

It does not attempt to defend against a rooted or compromised Android device, a
malicious system keyboard/accessibility service, an external camera, physical
control of an unlocked device, or malicious firmware the user has explicitly
trusted. There is no developer backend, payment, entitlement, or central account.

## Assets, purpose, retention, and recipients

| Asset | Source and purpose | Retention/deletion | Recipient/control |
| --- | --- | --- | --- |
| NanoKVM password | User or local encrypted vault; authenticate one connection | Mutable attempt buffer; optional encrypted record until explicit removal/reset/uninstall | Selected appliance only, after trust preflight; Keystore/no-backup/no log/state |
| Session token | Appliance; authorize active REST/video/input session | Memory-only until 401, disconnect, background teardown, or close | Exact selected HTTPS origin only; no cross-origin redirect |
| Profile and public leaf pin | User; locate and identify an appliance | Private DataStore until deletion/reset/uninstall | Local app only; backup disabled; unavailable/corrupt states block mutation |
| Remote framebuffer | Selected appliance; display console | Active transport/decoder buffers only; no app screenshot/recording store | Device Surface; HTTPS and secure display |
| Keyboard/pointer/paste | User/IME; immediate remote control | Not persisted or replayed | Selected appliance; ordered transitions and lifecycle release |
| Android shared text | Explicit `text/plain` share target; stage remote typing | Memory-only preview, bounded and never auto-sent | Selected appliance only after destination/layout review and confirmation |
| GPIO/power/reset | User; immediate hardware action | Not persisted, retried, or replayed | Selected appliance; consequence confirmation and serialized generation-scoped execution |
| Offline update archive | User-selected document; update appliance software | Transient one-pass stream; no copied file, URI grant, or path retained | Selected appliance after consequence review |
| Terminal, script, automation, and PicoClaw content | User/appliance; explicitly entered operator tools | Owning foreground surface only; no history or diagnostics | Selected appliance and, for PicoClaw, the provider the user configures on that appliance |
| Dependency/build inputs | Maven/Gradle/local build tools; produce app artifacts | Build cache and retained release evidence | Maintainers/users; exact versions, hashes, SBOM, licence/source bundle |

Trust boundaries are UI/IME to app, app-private storage to Android Keystore,
app to the selected HTTPS origin, OkHttp to application parsers, parsers to
decoder/control state, lifecycle generations to backend ownership, and
source/dependency inputs to a signed distribution artifact.

## Threats, implemented controls, and residual risk

### Network impersonation or observation

- The NanoKVM origin, authenticated signaling, and application control traffic
  require HTTPS, and Android cleartext support is disabled. Initial NanoKVM
  access-point setup is outside the app. Explicit WebRTC ICE traffic is the
  limited exception described below.
- On Android 17 and later, local-network access is requested only after an
  explicit Connect action. The permission is checked before trust preflight,
  before credentials are collected or unlocked, and again before transport
  startup; denial starts no network operation, while revocation disconnects the
  active session and clears pending password material.
- System trust is preferred. For a private/self-signed endpoint, an ephemeral
  inspection client retrieves only the leaf certificate. It sends no password,
  token, cookie, or application request, independently checks hostname/date,
  returns metadata, and is discarded before normal traffic.
- Explicit leaf-pin acceptance is scoped to one canonical origin. Every client
  disables redirects, so cookies, credentials, and mutation bodies never follow
  any 3xx response.
- A saved-pin mismatch is a hard stop. Rotation is not automatic. The review
  compares the stored and presented fingerprints and requires the user to
  reject, connect once while preserving the saved pin, or explicitly replace
  it. Decisions are bound to the inspected origin and generation.
- Explicit WebRTC mode may contact appliance-supplied STUN/TURN servers for ICE
  negotiation. Those peers can observe ordinary connection metadata. WebRTC
  receives video only; the app requests no camera or microphone permission and
  does not add an audio transceiver.
- The NanoKVM browser-compatible password cipher is public interoperability
  behavior. TLS, not that cipher, is the confidentiality/integrity boundary.

### Credential or local-data disclosure

- Saved credentials use randomized AES-256-GCM, profile-identity AAD,
  non-exportable Keystore keys, recent strong-biometric/device-credential
  authorization, atomic `noBackupFilesDir` storage, and verified deletion.
- `AppViewModel` owns pending secret actions and mutable password buffers. The
  Activity-retained coordinator contains only request/host IDs, prompt kind,
  and display name; it retains no secret or UI callback.
- Genuine backgrounding cancels connection and non-prompt secret work. A system
  authentication prompt may retain its action until the terminal callback, but
  a callback received while stopped clears the mutable buffer and cannot
  connect. Replacement, failure, cancellation, and teardown also clear owners.
  Secrets never enter saved instance state.
- Backup/device transfer is disabled. Non-debug builds disable Recents previews
  and use `FLAG_SECURE`.
- The app has no analytics, automatic crash reporting, or application diagnostic
  logger. Device/release checks must still inspect filesystem, Keystore, logcat,
  backup/restore, screenshots, and traffic.
- Live typing is owned by the user-selected Android IME. NanoKVM Mobile does not
  request microphone permission or receive/retain voice audio; dictation and
  personalization follow that IME's permissions and privacy settings. Only
  committed text crosses the app's IME bridge, and the app does not persist it.

Residual risks: Java/Android APIs may create short-lived immutable copies while
encoding text or crypto input; wiping a mutable owner cannot guarantee erasure
of every runtime/OS copy. A compromised device, IME, or accessibility service is
out of scope.

### Malformed or resource-exhausting endpoint

- REST, credential records, endpoint fields, certificate pins, session tokens,
  paste input, GPIO duration, video settings, H.264 payloads, input messages,
  and MJPEG headers/frames have application-level limits and boundary tests.
  Clipboard/share text above 1,024 normalized UTF-8 bytes is rejected before
  retention; a session token above 2,048 characters is rejected before use.
- Video queues and latest-frame dispatch are bounded. Stale video frames may be
  dropped; key/button transitions are not dropped.
- Timeouts, watchdogs, coroutine cancellation, session generations, and
  deterministic ownership limit stale work.

The client removes WebSocket compression negotiation and rejects unsolicited
`permessage-deflate`, preventing compressed-payload amplification before a
callback. Oversized direct H.264 cancels immediately and drives normal fallback;
input stops command acceptance, queues releases, and has a bounded close
handshake. Residual risk remains: OkHttp allocates a complete uncompressed
WebSocket `ByteString` before the H.264, terminal, or input callback can reject
it. Slow-fragment tests confirm cumulative pre-callback buffering. Treat the
first uncompressed allocation as open availability hardening; do not claim a
pre-allocation or end-to-end WebSocket memory bound without physical-device
measurement and an explicit disposition.

### Stale lifecycle or command execution

- Authentication outcomes carry request IDs and host generations; neither the
  coordinator nor ViewModel retains an Activity/composable callback.
- Configuration recreation preserves explicit non-secret screen state. Process
  death resets authentication and session state.
- Foreground reconnect is bounded to transient transport failures. Trust,
  authentication, firmware, and terminal protocol errors stop it.
- HID/paste/GPIO/power/reset, update, network-administration, terminal, script,
  automation, and PicoClaw mutations are never persisted or replayed.
- Destructive controls require consequence confirmation. The runtime rejects a
  duplicate command key, serializes REST controls through a mutex, and checks a
  session-generation lease immediately before execution. Generation changes
  invalidate queued leases; controls are not automatically retried. The UI also
  clears its ephemeral confirmation on destination, connection, or generation
  change, so an old dialog cannot be retargeted to a replacement session.

Deterministic tests now cover cancellation-ignoring input/video callbacks and
repeated background/reconnect/foreground/close cycles. An API 37 out-of-process
test covers non-secret profile-draft restoration. Residual gaps are process
replacement across credential, console, sharing, and Surface/HID states, real
network suspension, and signed-candidate execution on a physical device and
appliance.

### Supply-chain or distribution compromise

- Repositories are restricted; versions are exact; the Gradle distribution is
  checksummed; dependency artifacts use strict verification metadata; release
  uses R8/resource shrinking.
- The build defines a canonical CycloneDX SBOM, versioned Baseline/Startup
  Profiles, unsigned APK/AAB, and R8 mapping/usage outputs.

These are build capabilities, not proof of a production release. GitHub-hosted
CI and public build-artifact publication are intentionally disabled during
active development. Local outputs and logs are ignored working evidence, not a
durable signed-candidate record. A production release must separately retain
the source archive, canonical SBOM, licence material, checksums/provenance,
mapping/configuration custody, two-build reproducibility, signed/minified device
smoke, and security results required by `RELEASE_CHECKLIST.md`.

R8 is an optimization and surface-reduction tool, not an anti-tamper boundary
for an open-source GPL application.

## Misuse and failure cases

| Case | Required result |
| --- | --- |
| User enters an HTTP profile | Connection is blocked; no password is sent |
| Self-signed leaf before approval | Metadata-only inspection; no credential/token/application data |
| Saved pin changes | Hard failure; no automatic acceptance or login |
| Auth prompt returns after rotation/background/replacement | Stale result rejected; secret owner cleared as applicable |
| DataStore read is temporarily unavailable | Retry path; no reset or credential deletion |
| DataStore content is corrupt | Writes blocked; explicit reset consequence includes all credentials |
| Duplicate or queued power/reset command | Duplicate rejected; generation change invalidates old lease; no retry/replay |
| Input/video endpoint sends oversized WebSocket message | Compression is refused; parser rejects downstream work and transport terminates, but transient first uncompressed OkHttp allocation remains possible |
| App backgrounds with held input or pending password | HID release; connection/non-prompt secret cancellation; stopped prompt callback clears without connecting; no background reconnect/control |
| Decoder/input callback arrives from old session | Generation/ownership checks prevent new-session mutation |

## Accepted and not-applicable risks

- The Keystore authorization window is ten seconds and permits strong biometric
  or device credential. Per-operation biometric-only authorization and
  power-action step-up are not current requirements.
- Exact leaf pinning is renewal-sensitive by design. System-trusted,
  user-selected endpoints are not developer-pinned.
- Firmware ultimately controls authorization, JWT expiry/revocation, rate
  limiting, and GPIO semantics. The app fails closed where observable but cannot
  repair a malicious appliance.
- Remote attestation, Credential Manager/passkeys, deep-link/WebView/PendingIntent
  hardening, WorkManager, foreground services, and anti-tamper controls are not
  applicable without corresponding product components. Reassess on architecture
  change.

## OWASP MASTG v2 evidence map

The IDs below identify applicable tests or dispositions. “Implemented” means a
control/test exists in source; it does not mean the release evidence has been
run. Manual results remain open until attached to the release record.

| Control | MASTG tests/disposition | Repository evidence | Required retained release evidence |
| --- | --- | --- | --- |
| Cleartext, TLS, trust, pin, redirect | 0235, 0236, 0282, 0234, 0283, 0285, 0286, 0217, 0218; 0244 contextual | Manifest/NSC, `CertificateInspector`, `EndpointTrustPreflight`, protocol tests | Merged signed-release manifest/NSC, observed traffic, self-signed/pin-mismatch/cross-origin tests |
| Local storage, backup, logs, display | 0207, 0200, 0262, 0216, 0231, 0203, 0258, 0289 | backup rules, DataStore/no-backup store, `FLAG_SECURE` | Physical-device filesystem/Keystore/logcat, backup-transfer, screenshot/Recents results |
| Crypto and device authentication | 0212, 0205, 0208, 0309, 0310, 0232, 0350, 0326–0330 | AES-GCM/AAD code, request-ID coordinator tests | Real Keystore auth expiry/invalidation/deletion, callback bypass, process death, secret lifetime |
| Components and platform surface | 0364–0366, 0357, 0381, 0394, 0340 | source manifest and absence search | Merged signed-release component/permission inventory; IME/secure-display/device result |
| Supply chain and privacy | 0272, 0274, 0206 | verification metadata, dependency inventory, SBOM task, privacy notice | SBOM/licence/vulnerability results, source bundle, checksums/signing identity, traffic/privacy reconciliation |

MASTG 0282 may flag the inspection-only trust manager; evidence must demonstrate
its data-free, ephemeral use and independent hostname/date checks. MASTG 0212
may flag the public compatibility passphrase; HTTPS is the actual boundary.
MASTG 0368 obfuscation is not treated as a trust control.

## Evidence links

- `../app/src/main/AndroidManifest.xml` and
  `../app/src/main/res/xml/network_security_config.xml`
- `../protocol/src/main/java/org/nanokvm/protocol/CertificateInspector.kt`
  and `EndpointTrustPreflight.kt`
- `../app/src/main/java/org/nanokvm/mobile/security/SavedCredentialStore.kt`
  and `CredentialAuthenticationCoordinator.kt`
- `../app/src/main/java/org/nanokvm/mobile/runtime/ControlCommandGate.kt`
- `../gradle/verification-metadata.xml`, `DEPENDENCIES.md`,
  `BUILD_VERIFICATION.md`, `DISTRIBUTION.md`, and `RELEASE_CHECKLIST.md`

Open findings, scores, owners, and closure evidence are tracked in
`MODERNIZATION_AUDIT.md`.
