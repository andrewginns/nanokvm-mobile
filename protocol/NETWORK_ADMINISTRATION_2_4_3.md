# Network administration contract (NanoKVM 2.4.3)

This contract is pinned to the official NanoKVM 2.4.3 server/router, service, proto, and WebUI
sources. It adds no inferred endpoint and performs no real-appliance mutation during tests.

## Wi-Fi

| Operation | Route | Authentication | Important behavior |
| --- | --- | --- | --- |
| Information | `GET /api/network/wifi` | NanoKVM session | Returns `supported`, `apMode`, `connected`, and `ssid` |
| Connect | `POST /api/network/wifi/connect` | NanoKVM session | Manual `ssid` and `password`; waits for connection |
| Disconnect | `POST /api/network/wifi/disconnect` | NanoKVM session | Stops Wi-Fi and deletes the saved SSID/password |

There is **no Wi-Fi scan or network-list endpoint** in the pinned source. Both WebUI paths use a
manually entered SSID. The protocol therefore omits scan instead of inventing one.

`NanoKvmWifiCredentials` takes ownership of a mutable password and clears it after its only JSON
serialization. Its string form is redacted, and typed Wi-Fi failures discard server message/body
text.

SSID input is limited to the IEEE 32-byte maximum. Passwords are nonempty, bounded, valid UTF-16
without control characters. These are local memory/request-safety limits, not a claim that every
accepted value is valid for every wireless security mode. The server accepts only a nonempty
password and does not expose an open-network variant.

The pinned server also has unauthenticated AP-password verification and AP-mode connect routes.
This client intentionally does not expose them: every supported Wi-Fi mutation requires an
authenticated HTTPS session.

Connect/disconnect can move the appliance or terminate the current route. Every mutation is one
HTTP request with transport replay disabled. After a timeout or disconnect, rediscover and read
state; never repeat from an ambiguous result.

Capability floors from the pinned changelog are:

- manual PCIe Wi-Fi configuration: application 2.1.2, still runtime/hardware-gated by `supported`.

## Tailscale extension

The stable extension prefix first appears at application 2.1.6. `GET
/api/extensions/tailscale/status` is safe even without installed binaries and reports one of
`notInstall`, `notRunning`, `notLogin`, `stopped`, or `running`. A bounded future value is preserved
as `Other` for display but authorizes no write.

Each command requires a single-use user approval bound to the latest status object returned by the
same `NanoKvmApi`. The snapshot is consumed before dispatch. A new status read and new approval are
therefore mandatory after every result, including a lost or malformed response.

| Command | Exact route | Accepted observed state | Server-side consequence |
| --- | --- | --- | --- |
| `INSTALL` | `POST /api/extensions/tailscale/install` | `notInstall` | Downloads official stable RISC-V binaries and starts tailscaled |
| `UNINSTALL` | `POST /api/extensions/tailscale/uninstall` | any known installed state | Stops service and removes binaries; it does **not** promise account/state-data erasure |
| `START` | `POST /api/extensions/tailscale/start` | `notRunning` | Restores boot script, starts daemon, and sets 75 MB `GOMEMLIMIT` if absent |
| `STOP` | `POST /api/extensions/tailscale/stop` | `notLogin`, `stopped`, `running` | Stops daemon, removes boot script and removes `GOMEMLIMIT`; it does not call logout |
| `RESTART` | `POST /api/extensions/tailscale/restart` | `notLogin`, `stopped`, `running` | Restores boot script and restarts daemon |
| `UP` | `POST /api/extensions/tailscale/up` | `stopped` | Runs `tailscale up --accept-dns=false` |
| `DOWN` | `POST /api/extensions/tailscale/down` | `running` | Runs `tailscale down` while retaining daemon/account |
| `LOGIN` | `POST /api/extensions/tailscale/login` | `notLogin` | May start daemon and returns a short-lived external authorization URL |
| `LOGOUT` | `POST /api/extensions/tailscale/logout` | `stopped`, `running` | Logs the appliance out of its Tailscale account |

The install handler downloads `tailscale_latest_riscv64.tgz` on the appliance. The Android client
cannot verify that server-side download and must disclose the executable-code/network side effect.
Login URLs are accepted only on `https://login.tailscale.com:443/a/<safe-token>` with no user info,
query, or fragment; URL string output is redacted. Unexpected origins/paths remain visible as an
invalid-response state and are never opened by the protocol layer.

All command routes have empty request bodies and authenticated origin-scoped cookies. Transport
retries remain disabled. API/HTTP failure wrappers retain only command and numeric status/code,
never server text which could contain account or authorization material.

## Verification

`NanoKvmNetworkAdministrationApiTest` provides MockWebServer goldens for every supported route,
cookie/body boundary, mutable-secret clearing, redaction, URL allowlists, bounded unknown reads,
latest-status identity, legal-state gates, and post-dispatch no-replay behavior. Capability tests
pin the 2.1.2 and 2.1.6 floors.
