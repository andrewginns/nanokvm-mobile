# Release checklist

Current status: **source is public for active development; no binary is approved
for public distribution**. Hosted build/test automation and release artifact
uploads are intentionally disabled. This checklist is a template for a named
production candidate. Unchecked items remain open; source implementation or a
debug/emulator result does not close a signed-candidate or manual gate.

## Candidate record

| Field | Value |
| --- | --- |
| Version name/code | _required_ |
| Source tag and commit | _required_ |
| Worktree clean | _required_ |
| Signed APK/AAB SHA-256 | _required_ |
| Signing certificate SHA-256 | _required_ |
| Build environment/toolchain | _required_ |
| NanoKVM hardware/app versions | _required_ |
| Upstream parity reference tag/commit | _required_ |
| Parity ledger and appliance-case disposition | _required_ |
| Evidence archive location | _required_ |
| Release, QA, security, performance owners | _required_ |

## Source and strict build

- [ ] Source is frozen at the recorded tag/commit and contains no credentials,
  console captures, signing material, or private topology.
- [ ] JDK 21 and the recorded Android SDK/toolchain are used.
- [ ] The strict command in `BUILD_VERIFICATION.md` passes from a clean checkout.
- [ ] JVM results, `lintRelease`, unsigned APK/AAB, benchmark APK, profile
  verification, normalized SBOM, merged manifest/NSC, dependency graph, and R8
  mapping/seeds/usage/configuration are retained from the exact commit in the
  candidate record. An exact-source archive and unsigned SHA-256 evidence
  manifest are generated and independently checked before signing.
- [ ] Dependency verification metadata, build scripts, dependency inventory,
  licences, and vulnerability-review dispositions are reviewed. If hosted
  automation is introduced for release preparation, every third-party action is
  pinned and reviewed before it becomes a gate.
- [ ] The exact WebRTC/native dependency notice bundle and resolved-runtime
  licence report are retained with provenance/hashes and packaged beside any
  binary; the SBOM and an external notice link alone are not treated as the
  complete binary-distribution notice set.
- [ ] Versioned Baseline and Startup Profile sources were regenerated when their
  CUJs changed and are packaged within the size limit.
- [ ] Every `WEBUI_PARITY.md` row and applicable `APPLIANCE_TEST_PLAN.md` case has
  a retained result, named owner/disposition, or explicit release exclusion.
- [ ] The 2.3.2 compatibility floor and 2.4.3 reference target both complete the
  capability/fallback journey without a later-only feature breaking the console.

## Signed production candidate

- [ ] The release environment signs the candidate with the approved protected
  key; signature verification and public certificate digest are retained.
- [ ] Final post-signing checksums are recorded, and the tested bytes are the
  bytes selected for publication.
- [ ] The signed/minified candidate installs, upgrades, launches, and uninstalls
  on supported devices without relying on a debug key or profileable flag.
- [ ] Package name, monotonically increasing `versionCode`, `versionName`, and
  signing lineage are verified against the most recent published artifact; an
  update is exercised without clearing profiles, pins, or protected credentials.
- [ ] Trust review, login, first frame, H.264/MJPEG selection, keyboard, direct
  touch, trackpad, pan/zoom, reconnect, credential save/unlock/remove, and every
  destructive confirmation pass on the signed candidate.

## Android and device matrix

- [ ] API 26 current-commit instrumentation result is retained.
- [ ] API 29 clipboard/share-target/IME/lifecycle result is retained.
- [ ] API 31 clipboard/share-target/IME/lifecycle result is retained.
- [ ] API 33 clipboard/share-target/IME/lifecycle result is retained.
- [ ] API 34 clipboard/share-target/IME/lifecycle result is retained.
- [ ] API 35 / Android 15 current-commit local instrumentation result and
  signed-candidate critical-journey result are retained.
- [ ] API 36 / Android 16 current-commit instrumentation result is retained.
- [ ] API 37 instrumentation, generated-profile/package, and Macrobenchmark
  artifacts are retained.
- [ ] A representative physical ARM phone runs the signed candidate, including
  real Keystore, hardware input, video, lifecycle, memory, thermal, and OEM
  behavior. Emulator-only evidence does not close this item.
- [ ] Gesture and three-button navigation, portrait/landscape, resizable or
  split-screen windows, IME open/closed, and compact/medium/expanded widths pass.

## Real NanoKVM endurance

- [ ] Direct H.264 runs continuously for at least 30 minutes on a real appliance
  with visible frame progress, keyboard/pointer activity, foreground cycles,
  reconnect, no stuck HID state, and retained memory/network observations.
- [ ] Forced or fallback MJPEG runs continuously for at least 30 minutes with
  the same checks.
- [ ] If WebRTC is enabled and supported for this candidate, it runs for at least
  30 minutes and forced negotiation/ICE/decoder failures produce a fresh
  WebRTC-to-H.264-to-MJPEG fallback. Otherwise, its capability-gated exclusion
  is recorded in the candidate evidence.
- [ ] Certificate first trust, reconnect with saved pin, rejected pin mismatch,
  manual trust recovery, failed authentication, firmware rejection, and
  H.264-to-MJPEG fallback behave as documented.
- [ ] GPIO/power/reset behavior is either tested on a disposable target with an
  explicit safety decision or documented as excluded from that release's
  hardware test; confirmations and no-duplicate/no-replay tests still pass.

## Accessibility and adaptive behavior

- [ ] TalkBack labels, state, actions, reading order, dialogs, errors, keyboard
  entry, pan/zoom alternatives, and destructive confirmations pass manually.
- [ ] Switch Access or an equivalent switch-style traversal passes.
- [ ] Hardware keyboard focus order/activation and mouse/trackpad scroll pass.
- [ ] Accessibility Scanner or Compose UI checks are reviewed and dispositions
  retained.
- [ ] 200% font/display scale, long strings, RTL, dark/light theme, target size,
  focus visibility, contrast, and breakpoint-edge layouts remain operable.

## Security and privacy

- [ ] Authenticated traffic contains only the selected HTTPS origin; HTTP
  profiles are rejected and certificate inspection sends no credentials,
  tokens, or application data. The sole cleartext exception is an explicitly
  started, cookie-suppressed AP bootstrap to the user-entered endpoint; verify
  that no authenticated token or persisted password enters that flow.
- [ ] Self-signed trust, saved-pin mismatch, redirect/cookie origin, invalid/
  expired hostname, and certificate recovery tests pass.
- [ ] Real Keystore success, cancellation, authorization expiry, invalidation,
  deletion, rotation, foreground loss, and process death pass.
- [ ] Profile DataStore unavailable/corrupt/reset and partial deletion outcomes
  are tested; destructive reset explicitly states that user-saved records, pins,
  and credentials are removed before the catalog returns empty.
- [ ] Backup/device-transfer, filesystem, Keystore, logcat, screenshot/recording,
  Recents, exported-component, and permission checks pass against the signed
  candidate.
- [ ] WebRTC creation/teardown logcat contains no interface identifiers,
  addresses, SSID/BSSID/MAC data, ICE credentials/candidates, STUN/TURN URLs,
  or SDP; the no-op native logging threshold remains effective after dependency
  updates and minification.
- [ ] Applicable MASTG results and dispositions from `THREAT_MODEL.md` are
  retained. The post-allocation OkHttp WebSocket limitation is acknowledged as
  residual risk or mitigated before approval.
- [ ] The signed merged manifest contains the expected user-relevant Internet,
  connectivity-state, Android 17 local-network, and biometric/fingerprint
  prompt permissions; local-network denial/revocation is verified, WebRTC's
  `ACCESS_NETWORK_STATE` use is reconciled with privacy documentation, and the
  compatibility fingerprint permission remains limited to API 27 and older.
  AndroidX's package-scoped dynamic-receiver permission, DUMP-protected Profile
  Installer receiver, and unexported startup provider are inventoried with no
  unexpected exported component.
- [ ] `PRIVACY.md`, manifest, dependency inventory, observed traffic, release
  notes, and channel Data Safety disclosure (if applicable) agree.

## Performance and reliability

- [ ] A truthful fully-drawn boundary and cold/warm/hot startup results exist.
- [ ] Connect-to-first-frame, 30-second console, reconnect/foreground recovery,
  H.264-to-MJPEG fallback, and—when enabled—WebRTC-to-H.264-to-MJPEG fallback
  CUJs have traces and named budgets.
- [ ] Physical ARM startup/frame P50/P90/P95/P99, memory/PSS/leak, network,
  power/thermal, and long-session evidence is retained.
- [ ] Three stable reference runs establish noise and thresholds before the
  performance gate becomes blocking; regressions have owners/dispositions.
- [ ] Field Android Vitals review is attached when the chosen distribution
  channel supplies it. Until a release has field exposure, this remains “not
  available,” not “passing.”

## Distribution and sign-off

- [ ] The in-app About surface exposes the GPL terms, complete third-party
  notices, privacy/security documents, exact release source URL, version/code,
  and signing identity without relying on an unavailable development checkout.
- [ ] The public bundle follows `DISTRIBUTION.md`: exact corresponding source,
  licence/notice, dependency inventory, SBOM, signed binaries, post-signing
  checksums, signing identity, verification instructions, and release notes.
- [ ] Two isolated unsigned builds were compared before making any
  reproducibility claim; differences and signing boundary are documented.
- [ ] Upgrade, rollback/withdrawal, security-contact, known-limitations, and
  support instructions are published.
- [ ] Release owner, security reviewer, QA owner, and performance owner sign the
  same candidate record. Any rebuilt byte invalidates sign-off.
