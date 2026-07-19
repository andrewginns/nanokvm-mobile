# NanoKVM 2.4.3 autostart-script contract

This contract is pinned to official NanoKVM tag **2.4.3**, commit
`3b2ba7c0c1214f44da9d328f90bbdd025fac0413`. The relevant sources are
`server/router/vm.go`, `server/service/vm/autostart.go`, `server/proto/vm.go`,
`web/src/api/vm.ts`, and `web/src/pages/desktop/menu/settings/advanced/autostart.tsx`.

The server surface was introduced by upstream commit
`53924dc8efe06ee341cd49101640a5a71e22aaca` and first appears in application tag **2.3.1**.
The changelog does not mention it. The 2.4.3 React component exists, but its entry in the Advanced
settings page is commented out. Capability `AUTOSTART_SCRIPTS` therefore uses the verified 2.3.1
server-history floor; this does not claim that the pinned WebUI exposes the feature.

## Exact server surface

| Operation | Authenticated contract |
| --- | --- |
| List | `GET /api/vm/autostart`; data is `{"files":[basename...]}` |
| Read | `GET /api/vm/autostart/:name`; data is the file-content string |
| Create or replace | JSON `POST /api/vm/autostart/:name` with `{"content":"..."}`; data is the basename |
| Delete | bodyless `DELETE /api/vm/autostart/:name` |

The appliance stores these files under `/etc/kvm/autostart`. A write creates that directory when
needed, truncates or creates the selected file, and opens it with mode `0755`. Content is therefore
root-equivalent executable material. The server provides no distinct create/update route, atomic
replace guarantee, checksum, ordering API, run-now API, execution status, output, rollback, or
transactional recovery.

## Local authority and bounds

- Only conservative ASCII basenames ending in `.sh` or `.py` are accepted. Separators, `..`,
  whitespace, control characters, hidden basenames, other extensions, and names over 255 UTF-8
  bytes are rejected before transport. Arbitrary appliance paths can never be supplied.
- Lists are capped at 512 unique basenames. Content is capped at 256 KiB, must be strict UTF-8, and
  may contain printable text plus tab, carriage return, and newline; NUL, DEL, other controls,
  overlong UTF-8, surrogates, and out-of-range sequences are rejected.
- Read content is returned in a mutable, closeable owned buffer. JSON decoding necessarily creates
  a transient JVM `String`, but public result and write types do not retain immutable script text.
- `NanoKvmAutostartWriteContent.takeOwnership` immediately clears the caller's byte array, owns a
  private copy, serializes it once, and clears retained content and the JSON request buffer.
- Create requires a basename proven absent from the latest exact catalog. Update and delete require
  an exact opaque script handle from that catalog. Every mutation consumes the catalog before
  dispatch. This turns the server's overwrite-capable POST into explicit create and update APIs.
- Starting another list, logging in, forgetting the session, authentication expiry, or any mutation
  invalidates previous authority. List again after every mutation outcome.

Autostart failures expose only operation plus API code, HTTP status, invalid-response, or transport
category. Server messages, HTTP previews, decoder causes, filenames, and content are not retained.
Authentication expiry and coroutine cancellation still propagate in their normal typed forms.

## Replay and verification

The shared transport disables connection-failure retries. Each create, update, or delete therefore
issues at most one application request. A disconnect or malformed acknowledgement can leave the
outcome unknown; reconcile with a fresh list and content read rather than replaying a consumed
request. Protocol tests use MockWebServer only and never mutate an appliance.

`NanoKvmAutostartApiTest` pins route/method/body goldens, JSON escaping, mutable-buffer clearing,
strict filename/content validation, bounded and unique server data, latest-snapshot identity,
explicit non-overwriting create, update/delete consumption, redacted diagnostics, and one-request
ambiguous failure. `NanoKvmCapabilitiesTest` pins the 2.3.1 floor.
