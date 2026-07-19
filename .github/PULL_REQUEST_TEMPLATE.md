## Summary

Describe the user-visible or engineering outcome and the source milestone/version
code affected.

## Scope and risk

- NanoKVM application/hardware capability gate:
- Android/API/device scope:
- Highest risk class (read-only, reversible, persistent, disruptive/destructive):
- Recovery behavior after cancellation, disconnect, or an ambiguous response:

## Verification

- [ ] Strict JVM/lint/release-like build gate passed, or exceptions are listed.
- [ ] Relevant Android emulator/device flow passed, or remains explicitly open.
- [ ] Relevant real-appliance case passed on an appropriate target, or remains explicitly open.
- [ ] New/changed UI was checked for semantics, focus, contrast, large text, RTL, and adaptive widths as applicable.
- [ ] No credential, token, private address, signing key, fingerprint, or unredacted console data is present.

List exact commands, targets, results, and redacted evidence links:

## Protocol and parity checklist

- [ ] Not applicable, or the stable upstream tag/commit and exact WebUI/server contract are identified.
- [ ] `docs/WEBUI_PARITY.md` and the applicable protocol contract note are updated.
- [ ] Capability floor/runtime gate, bounds, malformed/future values, 401 behavior, cancellation, and replay policy are tested.
- [ ] `docs/APPLIANCE_TEST_PLAN.md` contains or references the required real-target case.

## Release and documentation

- [ ] User-facing behavior, navigation, privacy/security disclosure, and known limitations are documented.
- [ ] Changed distributable bytes use a new Android version code.
- [ ] Signing identity is not embedded in source; any shared APK fingerprint is public-only evidence.
- [ ] Changelog entry added when user-visible behavior, compatibility, security, privacy, or upgrade behavior changed.

## Open evidence or follow-up

Distinguish implemented source from locally observed behavior, retained proof,
and work that is still open. Do not mark a partial parity row Supported based
only on a happy-path implementation.
