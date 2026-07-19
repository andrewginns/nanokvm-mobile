# HID shortcuts and leader key: NanoKVM 2.4.3

This contract is pinned to the official NanoKVM **2.4.3** tag, commit
`3b2ba7c0c1214f44da9d328f90bbdd025fac0413`. Its sources are
`server/router/hid.go`, `server/service/hid/shortcut.go`,
`server/service/hid/leader_key.go`, `server/proto/hid.go`, and the WebUI
keyboard shortcut implementation under `web/src/pages/desktop`.

## Actual server surface

| Operation | Contract |
| --- | --- |
| List saved shortcuts | authenticated `GET /api/hid/shortcuts` |
| Add saved shortcut | authenticated JSON `POST /api/hid/shortcut` with `keys[{code,label}]` |
| Delete saved shortcut | authenticated JSON-body `DELETE /api/hid/shortcut` with `id` |
| Read leader key | authenticated `GET /api/hid/shortcut/leader-key` |
| Set/disable leader key | authenticated JSON `POST /api/hid/shortcut/leader-key`; an empty `key` disables it |

There is no server-side “run shortcut” endpoint, ID lookup, rename, update, reorder, or add response
containing the created ID. The official WebUI runs an entry locally through `/api/ws`: it creates a
fresh keyboard report, sends one incremental complete report after every stored key code, then
sends one empty report to release everything. `sendSavedHidShortcut` reproduces that sequence. It
does not replay failed presses and attempts only an all-keys safety release after a send failure.

## Bounds and write policy

- Local recording accepts one to six distinct codes, matching the WebUI recorder.
- Write codes come only from the exact 2.4.3 `ModifierMap`/`KeycodeMap` allowlist. Labels are
  derived locally; callers cannot submit arbitrary label text.
- Reads accept bounded unknown future codes and expose them with `knownCode == null`; such entries
  remain visible but cannot be written or run.
- A list is capped at 512 shortcuts, each server entry at 64 keys, IDs at 128 UTF-8 bytes, key
  codes at 64 bytes, and labels at 128 bytes. Blank/duplicate IDs, empty entries, control text, and
  larger responses are rejected as invalid appliance data.
- Delete requires an exact handle from the latest list snapshot. Add and delete consume that
  snapshot before dispatch. A new list is required after success or an ambiguous transport result.
- OkHttp connection-failure retries remain disabled, so each REST mutation produces at most one
  application request.

Capability floors come from the pinned changelog: saved shortcuts require application **2.3.2**;
the leader key requires **2.3.4**. Callers should gate each UI independently with
`SAVED_HID_SHORTCUTS` and `HID_LEADER_KEY`.
