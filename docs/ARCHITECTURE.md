# Architecture

NanoKVM Mobile uses three production Android library/application modules and one
test-only performance module. The boundaries follow ownership and delivery
needs; there is no ceremonial domain or per-screen module layer.

```text
Compose screens
  | immutable AppUiState + user actions
  v
AppViewModel -----------------------------------------------+
  | profile/credential repositories                         |
  | ConsoleBackend state + low-frequency commands           |
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
  video, reconnect, and release-generation state.
- `AppViewModel` combines those sources into immutable `AppUiState`, accepts
  durable UI actions, and owns connection attempts, certificate-review intent,
  and pending secret actions.
- Compose collects state with `collectAsStateWithLifecycle()`.
- High-rate pointer/key operations and Surface callbacks use narrow synchronous
  ports whose implementation enqueues work; they are intentionally not routed
  through `StateFlow` or a durable action queue.

The profile catalog starts empty and unresolved. Editing and connecting are
blocked until DataStore emits one of three terminal states:

- `Ready` supplies the actionable profiles;
- `Unavailable` is treated as transient and exposes retry without deletion;
- `Corrupted` blocks writes and requires an explicitly destructive reset.

Resetting corrupt storage removes all user-saved profile records, pins, and
protected credentials, then returns to an empty connection catalog. The UI and
release test must state and confirm that consequence. Repository and credential
file/Keystore work run on an injected I/O dispatcher. Android device tests for
DataStore I/O/corruption and real Keystore invalidation are still release
evidence gates.

## Connection and trust lifecycle

1. Normalize the supplied host to an HTTPS origin. Cleartext profiles cannot
   connect.
2. Run a TLS-only trust preflight before collecting or unlocking a password.
3. Prefer Android system trust. If trust fails for a private/self-signed leaf,
   an ephemeral inspection client retrieves certificate metadata without
   sending credentials, tokens, cookies, or application requests. Hostname and
   validity are checked before review is offered.
4. Require explicit acceptance of the leaf SHA-256 fingerprint for that origin,
   either once or persisted in the profile.
5. Encrypt the password in the NanoKVM web-client-compatible format and call
   `/api/auth/login`.
6. Keep the returned JWT in memory and send it only as the `nano-kvm-token`
   cookie to the exact origin.
7. Read `/api/vm/info`; reject NanoKVM application versions older than 2.3.2.
8. Open input plus the selected video transport. `Auto` tries direct H.264 and
   then MJPEG. Explicit `WebRTC` tries WebRTC, direct H.264, then MJPEG, always
   with a fresh fallback transport instance.
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

## Viewport, IME, and video

Gestures map through the inverse viewport transform, including fit scale, user
zoom, translation, and letterboxing. The remote `TextureView` and input layer
fill the viewport. A measured pan/zoom overlay has an independent vertical
handle; keyboard visibility temporarily docks it above the IME and closing the
IME restores its prior position.

Direct H.264 messages contain a keyframe flag, an unsigned little-endian
microsecond timestamp, and one access unit. The decoder waits for a keyframe and
uses a bounded queue; stale delta frames may be discarded under pressure. MJPEG
is parsed incrementally from `multipart/x-mixed-replace` and only a bounded
latest frame is handed to decoding.

Application parsers reject oversized H.264 and input WebSocket messages, but
OkHttp has already materialized each complete WebSocket message before those
checks run. Parser copies and decoder queues are bounded; peak transport
allocation from a hostile configured endpoint is not yet bounded by the app.
This residual availability risk is tracked in the threat model and audit.

## Shutdown and performance evidence

The backend owns its coroutine scopes, input heartbeat executor, video callback
executor, codec thread, watchdogs, and transports. `closeAndAwait()` is the
deterministic completion contract; lifecycle/race tests must prove that an old
backend cannot publish into a replacement session.

Generated Baseline and Startup Profile rules are versioned under
`app/src/main/generated/baselineProfiles/`. The `macrobenchmark` module defines
cold startup runs with no compilation and with the packaged Baseline Profile,
plus frame timing for cold startup without compilation and cold/warm/hot startup
with the packaged Baseline Profile. Compose reports fully drawn after the
profile catalog reaches a renderable terminal state. Current source-matched API
37 x86_64 cold/warm/hot, fully-drawn, and frame traces are diagnostic only;
first-frame/console CUJs, physical ARM measurements, and stable trend thresholds
remain open release evidence.
