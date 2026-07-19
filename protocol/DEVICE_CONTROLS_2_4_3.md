# NanoKVM 2.4.3 device-control contract

This contract is pinned to official NanoKVM application **2.4.3**, commit
`3b2ba7c0c1214f44da9d328f90bbdd025fac0413`. The primary references are:

- `server/router/vm.go`
- `server/proto/vm.go`
- `server/service/vm/{hdmi,memory,mouse_jiggler,swap,tls,virtual-device}.go`
- `server/service/vm/jiggler/jiggler.go`
- `web/src/api/{vm,virtual-device}.ts`
- the corresponding 2.4.3 settings components under `web/src/pages/desktop/menu/settings`

All routes require the authenticated `nano-kvm-token` cookie. Each public mutation method sends
exactly one HTTP request. The client transport has connection-failure retries disabled; a timeout or
disconnect after dispatch is an indeterminate result, not permission to replay a write.

## Routes and typed values

| Area | Read | Mutation written by this library |
| --- | --- | --- |
| HDMI | `GET /api/vm/hdmi` | empty `POST /api/vm/hdmi/enable`, `/disable`, or `/reset` |
| Mouse jiggler | `GET /api/vm/mouse-jiggler` | `POST /api/vm/mouse-jiggler/` with `relative` or `absolute`; disable sends `relative` |
| Go memory limit | `GET /api/vm/memory/limit` | `POST /api/vm/memory/limit`; 75 MB or disabled/0 only |
| Swap file | `GET /api/vm/swap` | `POST /api/vm/swap`; 0, 64, 128, 256, or 512 MB only |
| TLS | no state GET in 2.4.3 | `POST /api/vm/tls` with `enabled: true` only |
| Virtual USB devices | `GET /api/vm/device/virtual` | `POST /api/vm/device/virtual` with `network` or `disk` only |

The memory and swap read models retain a non-WebUI value when it is in the defensive
0..1,048,576 MB response bound, but expose no way to write it. Mouse-jiggler modes are limited to
32 UTF-8 bytes with no control characters. A bounded unknown mode is represented by `Other` and is
also read-only. This lets a newer server remain inspectable without silently sending an unreviewed
new value.

## Stateful and disruptive behavior

HDMI control is PCIe-specific. Disable interrupts the video path; reset disables capture for one
second and then enables it, persisting the enabled state. The application must confirm the exact
target and reconcile the state/stream after any ambiguous result.

Mouse-jiggler enable writes `/etc/kvm/mouse-jiggler`. Disable removes that file and can fail if it
is already absent. Memory-limit disable similarly removes `/etc/kvm/GOMEMLIMIT` and can fail if it
is already absent. Callers therefore read first and do not treat either disable operation as a safe
idempotent retry. The memory write surface deliberately mirrors the pinned WebUI's 75 MB Tailscale
preset rather than exposing the server's otherwise unbounded integer.

Swap changes allocate/remove `/swapfile`, run `mkswap`/`swapon` or `swapoff`, and update
`/etc/inittab`. Only the pinned WebUI allowlist is writable. This is storage- and memory-affecting
administration and needs explicit consequence confirmation plus a fresh read after dispatch.

TLS enable generates a self-signed certificate, rewrites the appliance protocol configuration to
HTTPS, and restarts the NanoKVM service. The library intentionally has no TLS-disable method. After
dispatch, reconnect to HTTPS and perform the normal certificate inspection/pinning flow; do not
reissue enable merely because the original HTTP connection disappeared.

## Virtual-device truth table

The 2.4.3 response struct serializes all three booleans: `network`, `media`, and `disk`. The handler
populates network and disk. `media` remains its false zero value and is preserved as a visible,
read-only field for wire compatibility.

The mutation handler accepts only `network` and `disk`, and each request **toggles** the current
state while rebuilding the USB gadget. Sending `media` returns an API error, so
`NanoKvmVirtualDevice` intentionally contains only `NETWORK` and `DISK`. Desired-state behavior is
implemented above this primitive: read the three-field state, skip if already satisfied, release
active HID input, send at most one toggle, recreate the HID connection generation, and read back.
The protocol method itself never hides those steps or performs multiple requests.

## Capability floors

Capability mapping follows the pinned upstream changelog:

| Capability | Minimum application | Result at/above floor |
| --- | --- | --- |
| Memory-limit configuration | 2.1.4 | supported by version |
| PCIe HDMI reset | 2.1.5 | runtime/hardware probe required |
| Mouse jiggler | 2.2.6 | supported by version |
| Swap configuration | 2.2.6 | supported by version |
| TLS enable | 2.2.7 | supported by version |
| PCIe HDMI enable/disable | 2.2.8 | runtime/hardware probe required |
| Virtual USB device configuration | conservative app floor 2.3.2 | runtime probe required |

Version evidence never proves PCIe hardware or that a disruptive endpoint will succeed. The
virtual-device rule uses this app's compatibility floor because the changelog does not provide a
reliable endpoint-introduction version; it deliberately remains `RUNTIME_PROBE_REQUIRED`.
