# Architecture

NanoKVM Mobile uses three production Android library/application modules and one
test-only performance module. The boundaries follow ownership and delivery
needs; there is no ceremonial domain or per-screen module layer.

```text
Compose screens
  | immutable AppUiState + durable app actions
  v
AppViewModel -----------------------------------------------+
  | profile/credential repositories                         |
  | ConsoleBackend state + typed ConsoleFeatureBundle       |
  +---- RemoteInputSink (non-blocking high-frequency input) |
  +---- VideoSurfaceSink (caller-owned Surface lifecycle)   |
  v                                                         |
app runtime adapter                                         |
  |                              |                          |
  v                              v                          |
protocol module                 video module                |
REST, TLS, input WebSocket      H.264/MJPEG, MediaCodec     |
  \______________________________/                          |
                |                                           |
                v                                           |
       selected NanoKVM HTTPS origin                        |
                                                            |
macrobenchmark module: out-of-process tests and profile generation
```

## Modules and owners

| Module | Shipped | Responsibility |
| --- | --- | --- |
| `app` | Yes | Compose UI, `AppViewModel`, application container, profile and credential persistence, gesture/IME mapping, and the runtime adapter |
| `protocol` | Yes | Endpoint normalization, trust inspection/pinning, NanoKVM login/REST models, origin-scoped session token, HID reports, and input WebSocket |
| `video` | Yes | Direct H.264 and MJPEG transports, access-unit/frame parsing, bounded decoder queues, watchdogs, and MediaCodec rendering |
| `macrobenchmark` | No | Baseline/Startup Profile generation and out-of-process startup/frame measurement of the minified profileable target |

`NanoKvmApplication` owns an `AppContainer`. The container supplies repository
and backend factories to the session-level `AppViewModel`; Activity and
composables do not reach through a global backend singleton.

## State ownership and data flow

- Separate DataStore files are the sources of truth for non-secret connection
  profiles and device-wide comfort settings such as scroll-pad sensitivity.
- The no-backup credential store is the source of truth for opt-in encrypted
  passwords. A profile only records whether it has a corresponding credential
  through a derived ID set; it never embeds the password.
- `ConsoleBackend.session` is the source of truth for transient connection,
  video, reconnect, and release-generation state. Connection/video status is
  latest-wins; user-initiated action feedback carries a monotonically sequenced
  revision so routine streaming progress cannot erase the last action outcome.
- `AppViewModel` combines those sources into immutable `AppUiState`, accepts
  durable UI actions, and owns connection attempts, certificate-review intent,
  and pending secret actions.
- Transient app notices are semantic `PendingAppNotice` values in a
  ViewModel-owned FIFO. Each has a process-local identity; only acknowledgement
  of the current head removes it. Lifecycle or navigation changes cannot clear
  the wrong notice, and transient notices are deliberately not restored after
  process replacement.
- Low-rate runtime actions are grouped into focused core, virtual-media/WOL,
  administration, operator, and PicoClaw contracts. `ConsoleFeatureBundle`
  makes unavailable surfaces explicit; production actions have no default
  no-op implementation. The concrete backend remains one staged adapter, while
  each UI surface receives only the contract it uses.
- The session-bound automation dialog has a closeable state owner. It owns its
  refreshes, mutation serialization, one-use approvals, editor state, and
  foreground lease; Compose renders its immutable `StateFlow` and launches the
  document picker. A ViewModel-owned `ConsoleSessionDraftOwner` retains that
  controller and the bounded operator transcript/editor across configuration
  recreation for one exact profile, authority, and session generation. It
  clears them on dismissal, destination/generation change, genuine background,
  disconnect, local-network revocation, or ViewModel teardown. Wi-Fi passwords,
  API keys/chat, terminal input, virtual-media URLs, approvals, and other
  secrets are never retained as drafts or written to saved instance state.
- Compose state uses `collectAsStateWithLifecycle()`. Replay-free operator
  output and notice presentation are collected only while the surface lifecycle
  is at least `STARTED`. Feature/runtime messages cross into Compose as closed
  semantic types and map exhaustively to Android string resources.
- High-rate pointer/key operations and Surface callbacks use narrow synchronous
  ports whose implementation enqueues work; they are intentionally not routed
  through `StateFlow` or a durable action queue.

The profile catalog starts empty and unresolved. Editing and connecting are
blocked until DataStore emits one of three terminal states:

- `Ready` supplies the actionable profiles;
- `Unavailable` is treated as transient and exposes retry without deletion;
- `Corrupted` blocks writes and requires an explicitly destructive reset.

The profile DataStore corruption handler writes a separate corruption sentinel;
the repository then blocks mutations until the user explicitly resets it rather
than silently treating malformed bytes as an empty catalog. Resetting corrupt
storage removes all user-saved profile records, pins, and protected credentials,
then returns to an empty connection catalog. The UI states and confirms that
consequence. Repository and credential file/Keystore work run on an injected I/O
dispatcher. An Android device test exercises real DataStore corruption and
reset; real Keystore invalidation remains a release evidence gate.

Settings remain DataStore-authoritative. A transient collection failure keeps
the last successful value and retries with bounded backoff until collection can
resume; it does not publish a speculative write as authoritative state.

## Connection and trust lifecycle

1. Normalize the supplied host to an HTTPS origin. Cleartext profiles cannot
   connect.
2. Run a TLS-only trust preflight before collecting or unlocking a password.
3. Prefer Android system trust. If trust fails for a private/self-signed leaf,
   an ephemeral inspection client retrieves certificate metadata without
   sending credentials, tokens, cookies, or application requests. Hostname and
   validity are checked before review is offered. Subject/issuer/SAN display
   metadata is Unicode-neutralized and bounded; the verified identity retains a
   slot, the complete fingerprint remains available, and the UI discloses any
   shortening.
4. Require explicit acceptance of the leaf SHA-256 fingerprint for that origin,
   either once or persisted in the profile.
5. Encrypt the password in the NanoKVM web-client-compatible format and call
   `/api/auth/login`.
6. Keep the returned JWT in memory and send it only as the `nano-kvm-token`
   cookie to the exact origin. Reject a token longer than 2,048 characters
   before storing or using it.
7. Read `/api/vm/info`; reject NanoKVM application versions older than 2.3.2.
8. Open input plus the selected video transport. `Auto` tries direct H.264 and
   then MJPEG. The native WebRTC runtime is not constructed for `Auto`, H.264,
   or MJPEG; explicit `WebRTC` resolves it lazily, then falls back through a
   fresh direct H.264 and MJPEG transport when necessary.
9. On lifecycle loss, disconnect, or cancellation, release all HID state and
   invalidate work owned by the old session generation before transports close.

A stored pin mismatch is a hard failure and never updates trust automatically.
The certificate-review flow shows the stored and presented fingerprints. The
user may reject the endpoint, connect once with the presented certificate while
preserving the stored pin, or explicitly replace the stored pin. Each action is
bound to the inspected origin and certificate generation so a stale review
cannot authorize a different connection attempt.

## Credential and authentication ownership

Saving a password adds a second trust layer; it does not change NanoKVM login.
The `AppViewModel` owns the mutable password, pending save/unlock action, staged
encrypted envelope, and their cleanup. It commits a staged replacement only
after NanoKVM login succeeds.

The Activity-retained `CredentialAuthenticationCoordinator` contains only
non-secret prompt routing metadata: request ID, prompt kind, profile display
name, and host-generation token. It never holds a password, address, Activity,
Fragment, Context, `BiometricPrompt`, or UI callback. Each Activity instance
supplies its own authenticator host. Results carry the request ID, and stale
host/request results are rejected.

On a genuine background transition, connection work and non-prompt secret work
are cancelled and cleared. Android's authentication UI can itself stop the
Activity, so a prompt-owned pending action may remain only until the prompt's
terminal callback. If that callback arrives while stopped, the ViewModel clears
the buffer and refuses to connect. Configuration recreation can rebind a
still-valid non-secret prompt request without retaining the old Activity.
Process death restores only the explicit non-secret screen model and resets
session/authentication state.

## Input and command safety

The input WebSocket carries:

- heartbeat: `[0]` every ten seconds;
- keyboard: `[1]` followed by an eight-byte USB boot-keyboard report;
- relative mouse: `[2, buttons, dx, dy, wheel]`;
- absolute mouse: `[2, buttons, xLo, xHi, yLo, yHi, wheel]`.

Motion may be coalesced, but button/key transitions remain ordered. All-zero
keyboard and mouse reports release held state. Foreground reconnect is bounded
to transient failures and never persists or replays HID, paste, GPIO, power, or
reset actions.

The NanoKVM mouse report exposes one wheel axis. The dedicated scroll pad sends
up/down through that native wheel and implements left/right as a documented
Shift+wheel compatibility gesture. Each horizontal step is serialized as a
temporary Shift report, wheel report, and restoration of the user's current
keyboard report, preventing synthetic Shift from leaking into concurrent IME
or hardware-key input.

GPIO/power/reset operations require UI consequence confirmation. The runtime
claims a command key to reject duplicates, serializes execution with a mutex,
and binds the lease to the current session generation. Disconnect/reconnect
invalidates old leases. REST controls are not automatically retried.

All REST execution, body reads, and JSON decoding dispatch below the caller on
the protocol I/O dispatcher. OkHttp connection retries and HTTP/HTTPS redirects
are disabled so credentials, tokens, and one-shot request bodies are never
replayed to an unreviewed destination. Cancellation cancels the active call;
certificate-inspection cancellation also closes both the connecting and TLS
sockets so blocking handshakes cannot outlive their owner.

Feature catalogs expose feature-specific, latest-snapshot handles rather than
`Any` tokens or server paths. Identity-bound lookup rejects stale, foreign, and
lookalike handles without putting their content in diagnostics. Input WebSocket
server messages are bounded and discarded because the client has no consumer;
there is no unused event flow, replay cache, or ambiguous loss policy.

Tailscale login navigation is a memory-only, generation-bound state handoff,
not an ephemeral event. Android opens only an HTTPS URL after an explicit tap;
the backend clears the request only after acknowledgement for its exact request
ID and destination, and retains retry guidance when URI handling fails.

## Viewport, IME, and video

Gestures map through the inverse viewport transform, including fit scale, user
zoom, translation, and letterboxing. The remote `TextureView` and input layer
fill the viewport. A measured pan/zoom overlay has an independent vertical
handle; keyboard visibility temporarily docks it above the IME and closing the
IME restores its prior position. Saveable viewport state includes the measured
viewport dimensions as well as zoom/pan, and a handled Fit request is restored
with its owner so recreation cannot replay it over a later transform.

Large virtual-media, Wake-on-LAN, HID, autostart, and script catalogs use one
stable-keyed lazy list per surface. Operator output is retained in a bounded
incremental buffer and published at most once per display frame; editor and
paste validation count bounded UTF-8 without allocating encoded copies during
ordinary input changes.

Direct H.264 messages contain a keyframe flag, an unsigned little-endian
microsecond timestamp, and one access unit. The decoder waits for a keyframe and
uses a bounded queue; stale delta frames may be discarded under pressure. MJPEG
is parsed incrementally from `multipart/x-mixed-replace` and only a bounded
latest frame is handed to decoding.

Application parsers reject oversized H.264, terminal, and input WebSocket
messages. The transport strips `permessage-deflate` negotiation and rejects an
unsolicited compression response, so compressed amplification is not accepted.
Direct H.264 cancels immediately on oversize and enters normal fallback; input
stops command acceptance and queues releases before its bounded close.

OkHttp nevertheless materializes each complete uncompressed WebSocket message
before those checks run. A raw slow-fragment fixture proves cumulative buffering
before listener delivery and deliberately trips when the pinned OkHttp internals
change. Parser copies, decoder queues, and post-rejection lifetime are bounded;
peak first-message allocation from a hostile configured endpoint is not. This
residual availability risk remains in the [technical security model](SECURITY.md).

## Presentation, adaptive layout, and accessibility

Material 3 is the local application shell, not a decorative layer over the
remote display. Profile, trust, and settings surfaces follow the saved System,
Light, or Dark appearance and may use Android wallpaper-derived colour. The
live console uses fixed neutral semantic tokens for its canvas, controls,
status, warnings, errors, and text so wallpaper colour cannot tint the remote
image or change the meaning of console state.

Console controls derive their presentation from
`currentWindowAdaptiveInfo()` and the current constraints rather than device
labels, orientation locks, or cached display metrics:

| Current window | Controls presentation | Console invariant |
| --- | --- | --- |
| Compact portrait, at least 480dp high | Modal bottom sheet | The stream remains full size behind transient controls, and the navigation pad docks above an open IME. |
| Compact landscape, under 480dp high | Dismissible side overlay | Vertical space is preserved, with a labelled scrim and explicit close action. |
| Medium width | Dismissible side overlay | View and scroll controls use the available console width. |
| Expanded width, 840dp or wider | Supporting pane | Controls may remain alongside the console. |

The video keeps a permanent composition parent in the main pane while those
presentations change. Opening, closing, or moving controls must not recreate
the decoder Surface or reconnect the transport. Editor and catalogue reading
widths are capped; the remote viewport consumes the main-pane space it is
assigned.

Colour is never the only status signal; text, shape, iconography, or an
accessibility state carries the same meaning. Material controls and custom
gesture alternatives provide at least 48dp targets. Pan, zoom, and
four-direction scrolling expose accessibility actions in addition to gestures.
Static labels, content descriptions, and runtime or feature notices live in
Android resources behind exhaustive semantic mappings.

Non-secret layout and viewport context may survive recreation. Passwords,
staged credentials, session tokens, certificate decisions, destructive
approvals, and other secrets must never enter `rememberSaveable`,
`SavedStateHandle`, or another restorable UI state owner. Automated layout,
contrast, semantics, and lifecycle tests support these rules, but do not replace
the physical-device, assistive-technology, long-string, RTL, or real-appliance
coverage defined in [Testing](TESTING.md) and the
[release checklist](RELEASE_CHECKLIST.md).

## Shutdown and performance evidence

The backend owns its coroutine scopes, input heartbeat executor, video callback
executor, codec thread, watchdogs, and transports. `close()` synchronously
rejects new commands, detaches input/Surface ownership, releases HID/input, and
publishes a fresh disconnected session before asynchronous transport/video
cleanup continues. `closeAndAwait()` is the deterministic full-completion
contract. Focused tests guard cancellation-ignoring late input/video callbacks,
replacement sessions, stale authentication/reconnect publication, and 64
background/reconnect/foreground/close cycles. Real physical transports,
decoders, and long-running shutdown remain integration gates.

Generated Baseline and Startup Profile rules are versioned under
`app/src/main/generated/baselineProfiles/`. The `macrobenchmark` module defines
cold startup runs with no compilation and with the packaged Baseline Profile,
plus frame timing for cold startup without compilation and cold/warm/hot startup
with the packaged Baseline Profile. Compose reports fully drawn after the
profile catalog reaches a renderable terminal state. Regenerate and package-
verify the profiles when those journeys change. Treat emulator traces as
diagnostic; performance claims require current-source measurements on a named
physical ARM device with repeatable first-frame or console journeys and recorded
thresholds.
