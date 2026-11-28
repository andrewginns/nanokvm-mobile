# NanoKVM Mobile repository instructions

## Development APK signing and update compatibility

- Never hand off an APK built with Gradle's ambient JVM-home debug keystore.
  Sandboxed builds can silently select a different identity from Android Studio
  or the interactive Windows account.
- Previously shared development APKs for `org.nanokvm.mobile` use certificate
  SHA-256
  `7F2E5128EB089159536803992E381AA830D0E7A2D9601FAC0048E3821EA02746`.
  Configure its private keystore outside the repository; never commit or copy
  the keystore into the workspace.
- The dedicated `development` variant uses package
  `org.nanokvm.mobile.development`, launcher name `NanoKVM Mobile Dev`, a `-dev`
  version suffix, and the same established development certificate. It is
  release-derived and non-debuggable. It installs alongside the original app
  with its own profiles, certificate pins, and protected credentials.
- Build a shareable development update with
  `scripts/build-development-update.ps1 -Variant Development`. It requires the
  actual preceding APK, explicitly supplies the local key, and runs
  `scripts/verify-apk-upgrade.ps1` against that artifact. Its default `Debug`
  variant remains available for legacy `org.nanokvm.mobile` development updates.
- Only the dedicated development package may use `-FirstInstall` to establish
  its initial `0.3.13-dev`/code `20` baseline at the canonical output path without
  a preceding APK. This is a separate installation, not an upgrade or signing-key
  migration of the original app. Retain the handed-off
  baseline APK; subsequent development updates require it as `-PreviousApk`.
- The builder must reject keystores inside the repository and must not overwrite
  an existing versioned output with different bytes. Changed distributable bytes
  require a higher `versionCode` and a new output path.
- Before handoff, verify all of the following: package is
  `org.nanokvm.mobile.development` for the dedicated variant or
  `org.nanokvm.mobile` for legacy debug updates, the signer is the established
  development certificate, and the candidate `versionCode` is strictly greater
  than the preceding APK (except an explicit dedicated-package first install).
- Any ambient or sandbox-generated debug certificate that does not match the
  established SHA-256 above is not update-compatible and must not be used for a
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
