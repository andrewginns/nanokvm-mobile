# One Android codebase for NanoKVM Mobile and Comet Mobile

Use `andrewginns/nanokvm-mobile` as the source repository. Give the existing Android application two Gradle product flavors, `nano` and `comet`, in a `device` dimension. Each flavor produces an independently versioned APK with its existing application ID. A shared keyboard or viewport fix lands once. A maintainer can ship it to either product when that product passes its checks.

## Starting points

These are the baselines inspected on 28 November 2026. Refresh the release records and retained test APKs when implementation begins.

| Installation | Verified baseline | First combined-build target |
| --- | --- | --- |
| NanoKVM production, `org.nanokvm.mobile` | Published `0.3.17`, code 27. Drafts extend through `0.3.20`, code 30. | Reserve the next unused version and code. Today that would be `0.3.21`, code 31. Retain the production signer. |
| NanoKVM Mobile Dev, `org.nanokvm.mobile.development` | Retained `0.3.13-dev`, code 20, signed by the established NanoKVM development key. The dedicated build type is in the local working tree. | Preserve its `.development` ID, `-dev` suffix and non-debuggable build type. Verify the update against the actual preceding Dev APK. Preserve the legacy same-package debug update path for its own signer as well. |
| Comet phone test, `org.cometkvm.mobile` | Retained `0.1.0`, code 1. Its certificate is `CN=Comet Mobile Device Test`, SHA-256 `649643c659dbfcb0c1d31fecd1e986eed30840a6dcac54beb980f80ab6e566e0`. | Candidate `0.1.1`, code 2, subject to the same inventory check. Prove an in-place phone-test update using the corresponding external test key and preceding APK. |
| Comet production, `org.cometkvm.mobile` | The fork has no GitHub release. Its first-production record names `0.1.0`/1 and has no signer digest. | Select the first public version and record its independent production signer before signing. If choosing `0.1.1`/2, update the unused first-production record explicitly. A different production signer requires a clean install on a separate test device or emulator for this check. |

NanoKVM `main` is at `a4e829b`. The newer NanoKVM console is at `969ad74` (the `v0.3.20` draft tag). Comet `main` at `3f2785d` includes the reconnect fix that drains a pending mouse-mode request. Use all three references when reconciling the code. [NanoKVM main](https://github.com/andrewginns/nanokvm-mobile/commit/a4e829b6888d622de4d5ec1f3012a26c402d5b2a) · [NanoKVM adaptive console](https://github.com/andrewginns/nanokvm-mobile/commit/969ad7445d6929d5a53d73964816cea85142a9ff) · [Merged Comet port](https://github.com/andrewginns/comet-kvm-mobile/commit/3f2785db83718e16c6c3e37e14c27bf5c910dd56)

## Implementation sequence

### 1. Establish target builds and preserve NanoKVM upgrades

- Start an isolated integration worktree from the reviewed NanoKVM console revision. Stack the integration on the adaptive work while its PRs are open. Reconcile the dedicated Dev build, signing guards and any relevant uncommitted changes with their owner before merging. Preserve the existing worktree, draft tags and handed-off APKs.
- Add the two flavors to `:app`. Keep the Kotlin namespace and Android component class names stable. Put each product's application ID, version name and version code in one checked-in target record consumed by Gradle and release scripts. Keep signer history in a separate lineage file per product. Store private keys outside the checkout.
- Keep `debug`, `release` and the existing benchmark tooling. Enable the dedicated `development` build type for NanoKVM. Wire each shareable test build to its own external key explicitly and verify the final APK signer. The first-build deliverable is a NanoKVM APK that upgrades its predecessor with profiles, pins, settings and saved-password unlock intact. [Android build variants](https://developer.android.com/build/build-variants) · [Android versioning](https://developer.android.com/studio/publish/versioning)

### 2. Bring Comet into the shared console

| Location | Responsibility |
| --- | --- |
| `app/src/main` | Keep one Compose console, viewport, input gestures, Android keyboard and shortcuts, clipboard/share confirmation, reconnect UI, profile editor and credential storage implementation. Preserve each installation's existing preference names, serialized defaults, credential binding and Keystore alias prefix. Comet already stores profiles under `nanokvm_profiles`. |
| `app/src/nano` and `app/src/comet` | Put each concrete backend and its factory binding in its flavor. Keep one shared `AppContainer` that calls the selected binding. Supply target resources, launcher identity, profile defaults, About links and capability setup. Keep Comet's optional TOTP prompt and fresh-code handling with its authentication flow. |
| Existing `:protocol` and `:video` modules | Share endpoint and certificate utilities where their trust rules match, plus H.264 frame queuing and Android decoding. Retain device-specific login, HID encoding, mouse-mode selection, WebSocket framing and reconnect behavior. Start with the existing modules. Account for the WebRTC dependency currently declared by `:video` in each delivered APK's dependency inventory. |

Bring over Comet's optional appliance control boundary and register only the features each backend implements. Keep NanoKVM's existing appliance tools available in its build. Comet's console covers video, input, clipboard and recovery. Its tests should confirm that ISO mounting, mouse jiggler and administration actions are absent. Import its fixture and device code, then apply its console changes to the newer NanoKVM layout. Verify keyboard capitalization and focus across rotation on both devices, and Comet relative mouse mode across reconnect. [Backend contract](https://github.com/andrewginns/comet-kvm-mobile/blob/3f2785db83718e16c6c3e37e14c27bf5c910dd56/app/src/main/java/org/nanokvm/mobile/runtime/ConsoleBackend.kt)

### 3. Make building and releasing one target independent

| Action | NanoKVM | Comet |
| --- | --- | --- |
| Assemble release input | `:app:assembleNanoRelease` | `:app:assembleCometRelease` |
| Run app unit tests | `:app:testNanoDebugUnitTest` | `:app:testCometDebugUnitTest` |
| Tag a release | Keep existing `vX.Y.Z` tags and links | Start `comet-vX.Y.Z` tags in the combined repository |
| Name APKs | `NanoKVM-Mobile-<version>-v<code>.apk` | `Comet-Mobile-<version>-v<code>.apk` |

- Add a target argument to the existing build, evidence, signing, upgrade and reproducibility helpers. Keep existing NanoKVM invocations working through a NanoKVM default. Resolve the variant, version, package, signer, preceding APK and output directory from the chosen target. Generate evidence under `dist/<target>/<version>/` and retain compatibility with existing NanoKVM artifact paths used by callers.
- Use the local Windows account's DPAPI enrollment and the release helper's `-Unattended` option for local signing. Select the credential beside the chosen target's keystore. The existing Comet enrollment opens the key used for its phone-test APK. Adopting that identity for production still requires the explicit Comet lineage decision. [Local signing setup](DISTRIBUTION.md#unattended-signing-on-the-local-windows-account)
- Replace the existing global project version, single `releaseRuntimeClasspath`, unflavored APK/AAB paths and tag regex in release tooling. Select the matching variant for lint, dependency reports, SBOM, source archive, About assets, baseline profiles and macrobenchmark package selectors. Validate the final manifest, evidence, tag and signer against the selected target. Exercise rejection with a predecessor, evidence bundle or signing identity from the other target.
- A shared change may be released for one app first. Increase only the version being distributed. A later release of the other app takes the same fix with its own version and predecessor. Preserve immutable artifacts and historical tags. Give each product its own changelog section and download link, and keep Comet releases from taking over NanoKVM's existing GitHub `releases/latest` link.

### 4. Prove both products and switch development over

1. Give contributors one verification command that builds both release variants, runs the shared protocol/video suites once, and runs app unit tests and lint for each flavor. Run the shared interaction tests on both variants. Move tests that instantiate a concrete backend to the corresponding flavor test source set and run each device's integration fixture. From the same clean commit, verify branding, application ID, version, selected backend and feature visibility in both packaged apps.
2. Install both on the same Android phone. Save different profiles and pins. Upgrade each in place with the appropriate signer, leave the other installed, and check that both retain their own profiles, trust decisions and protected credentials. Cover the NanoKVM Dev baseline separately. If the historical Comet test key is unavailable, record that specific upgrade as unverified and use a fresh installation to test the public lineage.
3. Exercise a real NanoKVM and the owner's Comet, recording model and firmware. Check video, tap and drag, relative pointer, physical and software keyboards, shortcuts, paste confirmation, rotation, HDMI loss and reconnect. Test NanoKVM transport fallback and appliance controls, and Comet's optional TOTP, certificate review and mouse-mode recovery. Record which Comet models actually pass.
4. Rehearse two releases from the shared tree. Change only Comet's version and prove NanoKVM retains its identity and version. Repeat in reverse. Sign and verify candidates through the existing release process. Publish each product when its own checks pass. After both upgrade paths and release procedures are accounted for, point the Comet fork's README and contribution links to the combined repository and retain its history. If a candidate fails, keep serving the previous artifact and correct the affected product in a higher-code build.

The migration is complete when each APK can ship on its own schedule, both can coexist and upgrade through their supported signing identities, and one shared console fix passes against both device backends.
