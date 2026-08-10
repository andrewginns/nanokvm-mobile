# Contributing

Issues and pull requests are welcome. NanoKVM Mobile controls real keyboard,
mouse, power, network, update, and root-level appliance functions, so changes
that affect trust, credentials, remote input, or destructive operations need
extra care.

## Set up the project

Use JDK 21, Android SDK platform 37, and the checked-in Gradle wrapper. The
project emits Java 17 bytecode. Follow the build steps in the [README](README.md)
and run Gradle with strict dependency verification.

Before starting a substantial UX, protocol, storage, security, or distribution
change, open an issue describing the intended behavior and compatibility scope.

Never commit credentials, signing keys, private addresses, certificate
fingerprints, unredacted logs, or another person's console capture. Examples
and screenshots must use reserved addresses and synthetic data.

## Design and safety boundaries

Start with the [architecture](docs/ARCHITECTURE.md),
[security design](docs/SECURITY.md), and
[NanoKVM parity ledger](docs/WEBUI_PARITY.md). In particular:

- trust inspection must complete before a password is collected or unlocked;
- authenticated application traffic remains HTTPS-only, apart from the
  documented WebRTC ICE path;
- every held HID key or mouse button must be released after cancellation,
  lifecycle loss, reconnect, disconnect, and shutdown;
- remote input and appliance mutations are never automatically replayed after
  an ambiguous response; and
- persistent, disruptive, or destructive actions require an explicit review
  of their target and consequence.

Protocol changes must cite a stable upstream NanoKVM tag or commit and update
the relevant contract note, parity row, tests, and appliance case. Capability
discovery uses safe reads; never invoke a destructive write to discover support.

UI changes must keep visible text in Android resources and preserve semantic
labels, keyboard focus, minimum target sizes, contrast, RTL behavior, large
text, and compact/expanded layouts.

## Verify the change

Run the smallest relevant tests while developing. Before requesting review,
run the full local gate:

```powershell
.\gradlew.bat --no-problems-report --no-configuration-cache --dependency-verification=strict test lintRelease assembleRelease bundleRelease assembleBenchmark :app:verifyReleaseProfiles :macrobenchmark:assembleBenchmark :app:verifyReproducibleSbomMetadata
```

The full gate disables the configuration cache because its metadata checks read
and compare complete generated documents. The repository intentionally uses
local verification rather than hosted CI. Record the source commit, exact
commands, and device or NanoKVM coverage you actually ran. See
[Testing](docs/TESTING.md) for instrumentation, profile, benchmark, and
appliance commands.

Dependency updates must include a reviewed
`gradle/verification-metadata.xml` diff and an updated
[dependency inventory](docs/DEPENDENCIES.md). Do not generate or trust new
checksums implicitly.

Only an APK that is actually shared or published needs a new Android
`versionCode` for changed bytes. A normal source change on `main` does not need
an immediate version bump. Never relabel different bytes with an already
distributed version/code.

Complete the pull-request template and distinguish implemented source,
locally observed behavior, retained evidence, and anything still untested.
Release-specific checks apply only when preparing a public APK; they are in the
[release checklist](docs/RELEASE_CHECKLIST.md).

By contributing, you agree that your contribution is licensed under
GPL-3.0-or-later, the same terms as the project.
