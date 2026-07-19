# NanoKVM 2.4.3 offline application update contract

This contract is pinned to the official NanoKVM 2.4.3 sources:

- `server/router/application.go`
- `server/service/application/update_offline.go`
- `server/service/application/progress_writer.go`
- `web/src/api/application.ts`
- `web/src/pages/desktop/menu/settings/update/offline.tsx`

The capability was introduced in application 2.3.1. Version at or above that floor is sufficient
to expose the WebUI route; a real update remains a high-risk appliance mutation and is never used
as a capability probe.

## Exact wire contract

The client sends one authenticated `POST /api/application/update/offline` request with
`multipart/form-data`. It contains exactly one part:

| Field | Filename | Part content type | Body |
| --- | --- | --- | --- |
| `file` | `nanokvm_X.Y.Z.tar.gz` | `application/gzip` | streamed package bytes |

The request has a known `Content-Length`; chunked or unknown-length sources are rejected locally.
The filename follows the exact WebUI shape `nanokvm_\d+.\d+.\d+.tar.gz`, additionally bounding
each numeric component and the full ASCII filename. Content must be 1 byte through 256 MiB. The
stream is checked against its declared length: early EOF and trailing bytes both fail locally.

The Go handler clears its update cache, writes the uploaded package, installs it, returns the normal
NanoKVM JSON envelope, waits one second, and restarts NanoKVM services. It exposes no checksum,
signature, dry-run, install-status, rollback, or cancellation API. Its sentinel percentage uses the
whole HTTP content length and is not a stable client progress route. App code must therefore present
local upload progress as bytes sent—not as verified install progress.

## Source and memory boundary

`NanoKvmOfflineUpdatePackage` receives only:

- the validated package filename;
- an exact byte length; and
- a `NanoKvmOfflineUpdateStream` opener.

The protocol module accepts no Android `Uri` and no filesystem path. Platform code owns document
provider access. The request body copies through one 32 KiB buffer and never materializes the whole
archive. Progress contains only transferred and total byte counts. It never retains a path, URI,
cookie, source exception, or server response.

The package is single-use. Its opener is atomically consumed before dispatch and the request body is
also marked and enforced one-shot. The dedicated call disables connection retries and HTTP/HTTPS
redirects, including same-origin redirects. A supplied interceptor cannot serialize the body twice.

## Outcomes and recovery

- HTTP 401 clears the local session and raises `AuthenticationExpiredException`.
- A nonzero API envelope becomes `ApiRejected(code, outcomeUnknown=true)` without retaining the
  server message. Code `-1` covers both preflight and install failures, so it cannot prove that no
  installation work occurred.
- A non-success HTTP response becomes a status-only `HttpRejected`; 408, 425, 429, and 5xx are
  marked outcome-unknown.
- A malformed successful response is outcome-unknown.
- Transport EOF, timeout, or disconnect after dispatch is outcome-unknown.
- A source open/read or declared-length mismatch is a redacted local-source failure.
- Coroutine cancellation cancels the OkHttp call, closes the active provider stream to unblock a
  pending read where the provider supports close cancellation, and propagates cancellation unchanged.

After success or any unknown outcome, reconnect and read `applicationVersions()`. Never reconstruct
and replay the package automatically after timeout, reconnect, process recreation, or workflow
restoration. A second manual attempt requires a newly selected source and a fresh high-risk user
confirmation. No test in this module performs a real appliance update.

## Verification

`NanoKvmOfflineUpdateApiTest` pins the authenticated route, multipart disposition/content type and
boundaries, known request length, byte progress, capability floor, name/size/stream-length rejection,
401 invalidation, cancellation, redirect suppression, one-request disconnect behavior, single-use
consumption, and redacted error mapping with MockWebServer.
