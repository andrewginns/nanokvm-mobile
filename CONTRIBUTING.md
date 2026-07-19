# Contributing

Thank you for helping improve NanoKVM Mobile.

## Before changing code

1. Discuss substantial UX, protocol, trust, storage, or distribution changes in
   an issue first.
2. Use JDK 21 and the checked-in Gradle wrapper. The project emits Java 17
   bytecode, but JDK 17 is not the supported build runtime.
3. Keep secrets and appliance data out of source, fixtures, screenshots, traces,
   and build logs. Never commit passwords, JWTs, private keys, signing keys,
   private network inventories, or another person's console capture.
4. Treat `main` as the 0.3.0 development milestone until a named, signed release
   is approved. Changed distributable bytes require a new Android version code;
   do not rename an old-version APK and present it as an update.

## Engineering invariants

- Every held HID key or mouse button must have a guaranteed release on gesture
  cancellation, lifecycle loss, reconnect, disconnect, and backend shutdown.
- Trust inspection happens before password collection or saved-password unlock.
  The inspection-only TLS client must never carry credentials, tokens, or
  application traffic.
- Authenticated profiles are HTTPS-only. Cleartext is allowed only inside the
  explicit pre-authentication AP-onboarding flow; it must stay user-entered,
  cookie-free, non-persistent, and isolated from saved account credentials.
- WebRTC signaling remains bound to the authenticated NanoKVM origin. Treat
  appliance-supplied ICE server URLs and credentials as untrusted bounded input,
  and document any resulting STUN/TURN network disclosure.
- Remote input and destructive controls are never persisted or replayed.
  GPIO/power/reset calls remain confirmed, serialized, generation-scoped, and
  non-retrying.
- Mutable password buffers and staged credentials have one explicit owner.
  Genuine backgrounding cancels non-prompt secret work; a system-prompt result
  received while stopped must clear its buffer and must never connect.
- New endpoint parsers require boundary, oversize, cancellation, and slow-input
  tests. Document transport-layer allocation limits that occur before parsing.
- Root terminal, serial, script, update, network, Tailscale, virtual-media, and
  PicoClaw writes require the same latest-snapshot or session-generation binding,
  explicit consequence review, secret redaction, and no-replay discipline as
  existing destructive controls.

## Protocol and parity changes

Protocol work must cite a stable upstream NanoKVM tag/commit and the exact WebUI
client and server route used as the contract. Do not treat upstream `main`, a
single appliance response, or a write probe as a stable capability contract.

For every added or changed endpoint:

- update the applicable protocol contract note and
  [parity ledger](docs/WEBUI_PARITY.md), including firmware floor, hardware or
  runtime gate, risk class, and open evidence;
- add request/response goldens, bounds, malformed/future-value behavior,
  authentication expiry, cancellation, and retry/replay tests as applicable;
- use safe reads and version evidence for discovery—never invoke a destructive
  write merely to find out whether it exists; and
- add or update the named appliance case in
  [docs/APPLIANCE_TEST_PLAN.md](docs/APPLIANCE_TEST_PLAN.md).

UI work must keep visible text in Android string resources and preserve semantic
labels, keyboard focus, minimum target sizes, contrast, RTL, large-text, and
compact/expanded behavior. A screenshot is useful review context but does not
replace Compose semantics, contrast, and device-flow checks.

## Verification

Run the strict repository gate before opening a pull request:

```powershell
.\gradlew.bat --no-problems-report --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:reproducibleSbom
```

When a booted emulator or device is available, run the applicable device and
Macrobenchmark commands in [docs/TESTING.md](docs/TESTING.md). Do not refresh
`gradle/verification-metadata.xml` implicitly. Dependency changes must include a
reviewed verification-metadata diff and an updated
[docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).

An ordinary debug APK is signed with the build account's local Android debug
key. It cannot update an installation signed by a different key. Record the
public certificate fingerprint for any APK used as shared test evidence; never
copy the private key into the repository or attach it to an issue or pull
request.

Changes to startup or profile-catalog code must regenerate the versioned
Baseline and Startup Profiles using the procedure in
[docs/BUILD_VERIFICATION.md](docs/BUILD_VERIFICATION.md). Do not hand-edit the
generated profile rules.

Changes affecting a release gate must update the evidence status in
[docs/MODERNIZATION_AUDIT.md](docs/MODERNIZATION_AUDIT.md) and
[docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md). A refactor or a test
definition is not a passing result until the corresponding evidence has been
run and retained.

Before requesting review, complete the pull-request template. Clearly separate
implemented source, locally observed behavior, retained appliance/device proof,
and work that remains open. Never mark a partial parity row Supported solely
because its happy-path UI exists.

By contributing, you agree that your contribution is licensed under
GPL-3.0-or-later, the same terms as the project.
