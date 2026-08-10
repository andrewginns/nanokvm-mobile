## Summary

Describe the outcome and why the change is needed.

## Scope and risk

- Affected Android versions/devices:
- Affected NanoKVM hardware/application versions:
- Highest risk: read-only, reversible, persistent, or disruptive/destructive:
- Recovery behavior after cancellation, disconnect, or an ambiguous response:

For protocol or capability work, identify the stable upstream contract and
update the [parity ledger](../blob/main/docs/WEBUI_PARITY.md) and applicable
[appliance case](../blob/main/docs/APPLIANCE_TEST_PLAN.md).

## Verification

- [ ] Relevant tests and strict dependency verification passed.
- [ ] Changed UI or device/appliance behavior was exercised, or the untested
      scope is listed below.
- [ ] No secret, private endpoint, signing key, fingerprint, or unredacted
      console data is included.
- [ ] User-visible, privacy/security, dependency, or compatibility docs were
      updated when affected.
- [ ] Any APK actually shared or published uses a new version code for changed
      bytes and an explicitly verified signer.

List exact commands, targets, results, and redacted evidence:

## Open scope

State what was not tested or remains follow-up work. Do not present source or a
happy-path test as broader device, appliance, accessibility, or release proof.
