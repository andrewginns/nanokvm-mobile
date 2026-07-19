# PicoClaw protocol pin: NanoKVM 2.4.3

This protocol slice is pinned to the official Sipeed NanoKVM `2.4.3` tag at commit
[`3b2ba7c`](https://github.com/sipeed/NanoKVM/tree/3b2ba7c0c1214f44da9d328f90bbdd025fac0413).
The implementation follows the public/frontend routes registered in
[`server/router/picoclaw.go`](https://github.com/sipeed/NanoKVM/blob/3b2ba7c0c1214f44da9d328f90bbdd025fac0413/server/router/picoclaw.go),
the runtime and history response types in
[`server/service/picoclaw`](https://github.com/sipeed/NanoKVM/tree/3b2ba7c0c1214f44da9d328f90bbdd025fac0413/server/service/picoclaw),
and the browser gateway frames in
[`web/src/lib/picoclaw-gateway.ts`](https://github.com/sipeed/NanoKVM/blob/3b2ba7c0c1214f44da9d328f90bbdd025fac0413/web/src/lib/picoclaw-gateway.ts).

The supported authenticated surface is deliberately closed:

- model configuration and the `default` / `kvm` agent profiles;
- runtime status, install, uninstall, start, and stop;
- history list, detail, and delete through server-issued opaque handles;
- the public gateway WebSocket and runtime-session release DELETE.

The loopback-only screenshot, action, MCP, load-image, runtime-session read, and internal-token
contracts are not represented. Callers cannot supply an arbitrary PicoClaw route.

Entering the feature performs no network request. This matters because the official runtime-status
GET starts a process-wide probe loop and may initialize PicoClaw configuration. Status is called
only after an explicit feature-entry action on NanoKVM application 2.4.0 or newer.

All writes are at-most-once. The REST transport has connection-failure retry disabled, and the
gateway has no reconnect, replay queue, or pending-message buffer. Provider keys use a single-use
mutable wrapper which clears the supplied `CharArray` immediately after request serialization and
redacts its string form. Uninstall and history deletion require explicit, strongly named approval
objects; history detail/delete additionally require identity-bound handles from the latest page.

The gateway accepts at most 1 MiB per frame. `message.send` is restricted to 1–50 steps and a
1-second to 30-minute runtime. A gateway instance is bound to one authenticated authority,
connection generation, and canonical UUID session; it can connect only once. Manual HID is exposed
as a separate lock state because NanoKVM blocks ordinary keyboard and mouse input for the entire
gateway session, not merely while the assistant reports itself busy. Close code `4001`–`4005`
mapping follows the official relay.

Closing the WebSocket asks the server relay to release the lock. The stronger `closeAndRelease`
path also sends the official one-shot `DELETE /api/picoclaw/runtime/session` with
`X-PicoClaw-Session-ID`. An ambiguous release is reported as `ReleaseUncertain`; it is never retried
or represented as proof that manual control has returned.
