# NanoKVM protocol module

This Android/JVM library implements the console-facing NanoKVM application protocol for server
versions 2.3.2 and newer. It contains no UI and does not persist credentials.

## Creating a client

```kotlin
// RFC 5737 documentation-only address; HTTPS is the default.
val endpoint = NanoKvmEndpoint.parse("192.0.2.250")
val tokenStore = InMemorySessionTokenStore()
val client = NanoKvmClient.create(
    endpoint = endpoint,
    tlsMode = TlsMode.SystemTrusted,
    tokenStore = tokenStore,
)

client.api.login("admin", passwordChars)
val info = client.api.vmInfo()
```

`login` performs NanoKVM's CryptoJS/OpenSSL-compatible AES password protection and stores the
returned token. REST calls and WebSocket requests use the server's required
`nano-kvm-token` cookie. `forgetSession()` only clears local state; it deliberately avoids the
server logout operation, which can invalidate other sessions depending on server configuration.

The caller owns password persistence. Persisted session tokens should be encrypted with Android
Keystore-backed storage.

## Self-signed certificates

Use `CertificateInspector.inspect(endpoint)` during onboarding. It performs one isolated TLS
handshake, sends no application data, and returns the leaf fingerprint, identity, SANs, validity,
and a separate hostname-verification result. The inspection-only permissive trust manager is never
exposed or installed globally.

After explicit user approval, reconnect with:

```kotlin
val client = NanoKvmClient.create(
    endpoint,
    TlsMode.PinnedCertificate(inspection.fingerprint),
    tokenStore,
)
```

System trust, exact certificate pinning and a host/port-scoped TOFU primitive are available. There
is intentionally no global `trust all certificates` mode, and the normal hostname verifier remains
enabled for pinned connections.

## Input WebSocket

```kotlin
val input = client.newInputSocket()
input.connect()

input.sendMouse(AbsoluteMouseReport.normalized(x = 0.5f, y = 0.5f))
input.sendCommittedText("hello", KeyboardLayout.UK)
input.sendKeystroke(
    HidKeystroke(
        HidUsage.DELETE_FORWARD,
        setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_ALT),
    ),
)
input.sendMouse(RelativeMouseReport.create(buttons = setOf(MouseButton.BACK)))
input.disconnect()
```

Back and Forward use NanoKVM application 2.3.2+'s HID button 4 and 5 bits respectively, and are
available in both relative and absolute mouse reports. A subsequent empty-button report releases
the navigation button just like Left, Right, and Middle.

The input socket emits a heartbeat every 10 seconds. A graceful disconnect attempts unconditional
keyboard and mouse-button releases before the close handshake. Callers may coalesce motion but must
never drop release reports.

Video WebSockets should share the exact client TLS policy and use the authenticated request helper:

```kotlin
client.transport.newWebSocket(
    client.webSocketRequest("/api/stream/h264/direct"),
    listener,
)
```

## System and serial terminal

NanoKVM 2.4.3 exposes one authenticated root PTY at `GET /api/vm/terminal` upgraded to a
WebSocket. Create an explicit connection with `client.newTerminalSocket()`. It derives `ws` or
`wss` from the configured origin and attaches the current `nano-kvm-token` cookie. Server PTY
output is binary; client raw PTY input is text; terminal resize is binary JSON with unsigned
16-bit `rows` and `cols`.

The terminal socket has no heartbeat, automatic reconnect, replay, or input queue. Each explicit
connection has a new generation, and callbacks from stale sockets are ignored. Incoming frames and
the slow-consumer event buffer are bounded. A graceful close disconnects the WebSocket, which makes
the 2.4.3 server kill the spawned root shell.

Serial terminal uses that same root PTY and starts `picocom`. `NanoKvmSerialConfiguration` accepts
only a full-match `/dev/[A-Za-z0-9._-]+` device and typed allowlists for baud, parity, flow control,
data bits, and stop bits; no caller string is interpolated into the command. The defaults are
`/dev/ttyS1`, 115200, no parity/flow, 8 data bits, and 1 stop bit. `exitSerialAndDisconnect()` sends
Ctrl-A Ctrl-X exactly once, waits 100 ms, and closes only the connection generation on which the
exit sequence was sent.

## REST surface

`NanoKvmApi` exposes login, VM info, hardware version, stream settings, GPIO status/actions, HID
reset, server-side batch paste, storage images, HID mode, virtual devices, remote-image transfer and
Wake-on-LAN. This surface follows the stable NanoKVM 2.4.3 wire contract. Successful calls return
decoded models. HTTP 401, non-successful HTTP status, nonzero NanoKVM envelopes and malformed or
out-of-policy response fields use distinct typed exceptions.

The additional stable routes are:

| Area | Read | Mutation |
| --- | --- | --- |
| Images | `GET /api/storage/image`, `/api/storage/image/mounted`, `/api/storage/cdrom` | `POST /api/storage/image/mount`, `/api/storage/image/delete` |
| HID | `GET /api/hid/mode`, saved shortcuts and leader key | HID mode, saved shortcut add/delete, leader-key set/disable |
| Virtual devices | `GET /api/vm/device/virtual` | `POST /api/vm/device/virtual` (toggle) |
| Remote image | `GET /api/download/image/enabled`, `/api/download/image/status` | `POST /api/download/image` |
| Wake-on-LAN | `GET /api/network/wol/mac` | `POST /api/network/wol`, `/api/network/wol/mac/name`; `DELETE /api/network/wol/mac` with JSON body |
| Account | `GET /api/auth/account`, `/api/auth/password` | `POST /api/auth/password` |
| Application | `GET /api/application/version`, `/api/application/preview` | `POST /api/application/preview`, `/api/application/update`, streaming multipart `POST /api/application/update/offline` |
| Appliance | `GET /api/vm/oled`, `/api/vm/ssh`, `/api/vm/hostname`, `/api/vm/mdns`, `/api/vm/web-title` | OLED and hostname setters; explicit SSH/mDNS enable/disable; title set/reset; `POST /api/vm/system/reboot` |
| DNS | `GET /api/network/dns` | `POST /api/network/dns` with typed `manual` or `dhcp` mode |
| Wi-Fi | `GET /api/network/wifi` (manual SSID; no scan route) | authenticated connect/disconnect; public AP verify/connect with `X-AP-Key` and no account cookie |
| Tailscale | `GET /api/extensions/tailscale/status` | typed install/uninstall, service start/stop/restart, tailnet up/down, login/logout |
| Scripts | `GET /api/vm/script` | multipart `POST /api/vm/script/upload`; JSON `POST /api/vm/script/run`; JSON-body `DELETE /api/vm/script` |
| Autostart scripts | `GET /api/vm/autostart`, `GET /api/vm/autostart/:name` | JSON `POST /api/vm/autostart/:name`; bodyless `DELETE /api/vm/autostart/:name` |
| HDMI | `GET /api/vm/hdmi` | explicit enable/disable and one-shot reset endpoints |
| Device resources | memory limit, swap, mouse-jiggler state | typed WebUI allowlists; no arbitrary numeric/mode writes |
| TLS | no 2.4.3 state GET | enable only; disabling is intentionally absent |

Image paths returned by the appliance are bounded, canonical `/data/...` `.iso`/`.img` paths.
Mount and delete accept only the exact opaque `NanoKvmImage` handle from the supplied immutable
`NanoKvmImageCatalog`; callers cannot submit arbitrary appliance filesystem paths. Restoring media
sends the stable empty-file mount request. Image-list count/path, HID mode, transfer fields, WOL
history/name and MAC inputs all have explicit limits.

Remote transfers currently accept only a `NanoKvmRemoteImageUrl`: HTTP or HTTPS, no embedded
credentials or fragment, and a conservative `.iso`/`.img` basename. The 2.4.3 request contains
only `file`. Cancellation, preview/current-main checksum headers or fields, and SHA state are
intentionally absent. Unknown bounded transfer status and HID-mode values are preserved as
`Other`, so a newer appliance does not make a safe read undecodable.

`toggleVirtualDevice` represents the server's non-idempotent toggle and returns the observed `on`
value; callers should read `virtualDevices()` before deciding to invoke it. The read preserves
network, media, and disk. The pinned handler accepts mutations only for network and disk, so media
is visible but read-only and can never be submitted as a toggle. WOL MAC addresses are
canonical uppercase colon-separated values and saved names are whitespace-normalized. Stable
versions through 2.4.1 used API code `-2`/`open file error` for an absent first-use WOL history
file. `wakeOnLanHistory(version)` treats only that exact result, and only with matching version
context, as an empty list.

Account password changes reuse the login cipher and accept only a mutable `CharArray`; the caller
must clear it after the call. The change-password surface is capped at 256 characters and 256
UTF-8 bytes without first materializing an immutable plaintext `String`, and mirrors the four
characters rejected by the 2.4.3 WebUI. OLED writes accept only the WebUI's exact sleep allowlist.
SSH and mDNS writes use explicit enable/disable endpoints rather than ambiguous toggles. A custom
web title is distinct from `resetWebTitle()`. On reads, only the exact 2.4.3 `-1` / `read web title
failed` absent-file result maps to the default `NanoKVM` title; every other API error propagates.

DNS reads preserve a bounded unknown mode as `Other`. Addresses are validated and canonicalized as
IP literals without accepting hostnames or scoped IPv6 values. Manual writes require one to six
unique servers; DHCP writes always send an empty server list. Response lists and network metadata
are bounded independently so a future but well-formed response remains readable without allowing
unbounded allocation.

Wi-Fi/AP onboarding and Tailscale extension semantics are specified in
[NETWORK_ADMINISTRATION_2_4_3.md](NETWORK_ADMINISTRATION_2_4_3.md). Wi-Fi passwords and AP keys use
single-use mutable storage with redacted failures; public AP requests explicitly omit the account
cookie. Tailscale writes require a single-use approval bound to the latest known status, and a
bounded future state is read-only. All nine exact extension commands are distinct and non-replayed.

HDMI, mouse-jiggler, memory-limit, swap, TLS-enable, and virtual-device semantics are specified in
[DEVICE_CONTROLS_2_4_3.md](DEVICE_CONTROLS_2_4_3.md). Writes use exact pinned-WebUI values: jiggler
relative/absolute, memory limit 75 MB, and swap 0/64/128/256/512 MB. Bounded future jiggler modes
and memory/swap values remain readable but cannot be written. TLS is enable-only because changing
back to cleartext is outside the app's security policy.

Saved keyboard shortcuts and the leader key are specified in
[HID_SHORTCUTS_2_4_3.md](HID_SHORTCUTS_2_4_3.md). The server stores and deletes definitions but has
no run endpoint: `sendSavedHidShortcut` reproduces the official WebUI's incremental HID WebSocket
reports and final release. Saved-shortcut and leader-key capability floors are 2.3.2 and 2.3.4
respectively. Bounded future key codes remain readable but are not writable or runnable.

Root-equivalent boot scripts are specified in
[AUTOSTART_2_4_3.md](AUTOSTART_2_4_3.md). Reads and writes use bounded strict UTF-8 buffers; callers
cannot submit arbitrary appliance paths. Create is locally guarded against overwrite, while update
and delete require exact handles from the latest catalog. Every write consumes both its authority
and content before dispatch, redacts server diagnostics, and is never automatically replayed. The
protocol was introduced in 2.3.1, although the 2.4.3 Advanced-settings entry remains commented out.

The client disables OkHttp connection-failure retries on both owned and supplied transports.
This makes ambiguous connection failures visible to the caller instead of risking a replay of a
toggle, GPIO action, mount, delete or WOL command. Callers may retry a proven-safe read explicitly,
but must reconcile mutation state before issuing another write.

The administration setters, password change, online update, and reboot follow the same no-replay
rule. Online update has a fifteen-minute call timeout because the 2.4.3 handler can run to
completion before responding. Update and reboot can intentionally drop the session or restart
services; callers must rediscover/reconnect and read the resulting state. An ambiguous disconnect
is not permission to repeat either one-shot request.

Offline application update is specified in
[OFFLINE_UPDATE_2_4_3.md](OFFLINE_UPDATE_2_4_3.md). It accepts only an exact
`nanokvm_X.Y.Z.tar.gz` filename, a known 1-byte-to-256-MiB length and a one-shot stream opener—not a
path or Android URI. The archive is copied through a 32 KiB buffer; the progress callback contains
byte counts only. Redirects and retries are disabled, the body rejects a second serialization, and
the source is consumed before dispatch. Safe failures never retain a provider path or server body.
Success, malformed acknowledgement, timeout and connection loss require reconnecting and reading
the installed application version; none authorizes automatic replay.

Script discovery returns only bounded, safe `.sh` and `.py` basename handles. Run and delete
require exact object identity from the latest successful `NanoKvmScriptCatalog`; a newer list,
upload, delete, logout, or authentication expiry invalidates older handles. Upload has a 512 KiB
local byte cap, uses only a sanitized basename in multipart field `file`, and returns a receipt—not
an executable handle. List again after upload.

Foreground script execution retains at most 256 KiB of the combined output returned by the
appliance. NanoKVM 2.4.3 provides no server-side timeout or cancellation, and cancelling the HTTP
request does not prove the process stopped. A nonzero foreground exit is reported as an API error
without its command output. Background execution returns no PID, status, output stream, or cancel
operation. Upload can overwrite an existing basename. Consequently upload, run, and delete are
one-shot mutations with no automatic retry; reconcile with a fresh list after an ambiguous result.
Known script errors and bounded future error codes/messages are exposed through
`NanoKvmScriptOperationException`.

After authentication, `probeCapabilities()` reads only VM info, hardware info and GPIO status. It
returns `Supported`, `Unsupported` or `Unknown` for every optional read and an immutable capability
snapshot. Documented application-version floors are used only when the version alone is sufficient;
hardware- and runtime-dependent features remain `Unknown` until a dedicated probe can prove them.
An unavailable optional endpoint therefore cannot turn a successful login into a failed session,
while authentication expiry still propagates normally.

The 2.3.2 application floor is recorded for virtual-media mounting and Wake-on-LAN, but version
evidence alone deliberately leaves both `RUNTIME_PROBE_REQUIRED`; it never authorizes a write.

## Verification

Run `:protocol:testDebugUnitTest`. Tests cover the fixed OpenSSL encryption vector, REST envelopes
and host-scoped cookies, redirect leakage, WebSocket cookie/releases, HID golden bytes, US/UK
mapping, endpoint normalization, certificate fingerprints, TOFU state and one-shot self-signed
certificate inspection. Phase-3 tests additionally cover stable route/method/body goldens,
snapshot-bound image handles, DELETE bodies, field/list bounds, URL/MAC/name normalization,
forward-compatible read values, legacy WOL behavior and disabled transport replay.
Administration tests cover authenticated route/method/body goldens, password/title edge contracts,
explicit service setters, the OLED allowlist, typed/canonical DNS and request/response bounds.
Operator tests cover terminal cookie/path and frame goldens, generation/lifecycle isolation,
protocol-size closes, no queue/reconnect/replay, every 2.4.3 serial allowlist, exact picocom exit,
script multipart/JSON/DELETE goldens, traversal and injection rejection, latest-snapshot handles,
upload/output/error bounds, and forward-compatible structured script failures.
HID-shortcut tests cover the exact 190-code 2.4.3 key allowlist, REST route/method/body goldens,
snapshot-bound deletion, strict response bounds, forward-compatible unknown reads, capability
floors, incremental WebSocket run frames, preflight rejection, safety release, and no replay.
Network-administration tests cover manual-only Wi-Fi, authenticated/AP cookie boundaries,
single-use mutable secrets, every Tailscale route, official login-URL allowlists, known-state/latest
snapshot gates, redacted failures, capability floors, and no replay.
Offline-update tests cover multipart boundaries and known length, authenticated one-shot streaming,
byte-only progress, filename/size/source-length bounds, capability floor, 401 invalidation,
cancellation, redirect suppression, ambiguous disconnect, consumption, and redacted failures.
Autostart tests cover exact route/method/body contracts, JSON escaping, strict basename and UTF-8
bounds, mutable-buffer clearing, latest-snapshot identity, guarded create versus explicit update,
bodyless delete, redacted diagnostics, the 2.3.1 capability floor, and no replay.
