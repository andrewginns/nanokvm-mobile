# Comet Mobile repository instructions

## Development APK signing and update compatibility

- Never hand off an APK built with Gradle's ambient JVM-home debug keystore.
  Sandboxed builds can silently select a different identity from Android Studio
  or the interactive Windows account.
- Comet uses `org.cometkvm.mobile` and an independent signing lineage.
  Set `COMET_DEVELOPMENT_KEYSTORE` and `COMET_DEVELOPMENT_SIGNER_SHA256`
  only after provisioning the external Comet key. Never reuse upstream
  NanoKVM signing keys or copy private material into the checkout.
- Build a shareable development update with
  `scripts/build-development-update.ps1`. It requires the actual preceding APK,
  explicitly supplies the local key, and runs `scripts/verify-apk-upgrade.ps1`
  against that artifact.
- The builder must reject keystores inside the repository and must not overwrite
  an existing versioned output with different bytes. Changed distributable bytes
  require a higher `versionCode` and a new output path.
- Before handoff, verify all of the following: package is
  `org.cometkvm.mobile`, the signer is the configured Comet development certificate,
  and the candidate `versionCode` is strictly greater than the preceding APK.
- Any ambient or sandbox-generated debug certificate that does not match the
  configured Comet identity is not update-compatible and must not be used for a
  handoff artifact.
- Do not suggest uninstalling to bypass a signature mismatch without clearly
  stating that uninstalling removes profiles, certificate pins, and protected
  credentials. There is no Android manifest or Gradle workaround for a lost
  signing key.
- This development lineage is not a production signing identity. A public
  binary release still requires the protected release-signing and provenance
  process in `docs/DISTRIBUTION.md` and `docs/RELEASE_CHECKLIST.md`.
- Never commit or expose a `.jks`, `.keystore`, password, token, private key, or
  private release evidence. Guarded helpers may create ignored evidence under
  `dist/`; keep it private and out of commits and public release assets.
