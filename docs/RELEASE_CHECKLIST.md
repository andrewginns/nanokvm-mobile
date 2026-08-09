# Release checklist

Current status: **v0.3.6/code 13 is the stable, production-signed GitHub
release**. It was promoted after several weeks of owner-reported use with
NanoKVM application 2.4.3 without a reported issue. Release verification is
local; hosted build/test automation is not part of the process. Stable status
does not turn unchecked future coverage into a compatibility claim; new
distributable bytes must satisfy the applicable gates below and use a higher
Android version code.

The non-waivable minimum for a public testing APK is the
[public pre-release candidate lane](DISTRIBUTION.md#public-pre-release-candidate-lane).
Stable publication additionally requires the
[stable direct-GitHub release lane](DISTRIBUTION.md#stable-direct-github-release-lane).
Dynamic hashes, signature verification, exact-byte smoke results, asset
inventory, scope disclosure, and release-owner approval are retained with the
GitHub release. The checklist below remains the gate for new stable releases.

## v0.3.6 stable-promotion record

| Field | Value |
| --- | --- |
| Version name/code | `0.3.6` / `13` |
| Source tag and commit | `v0.3.6` / `266bfb0ced4f5cf43b10cd8ca8a85564759aea88` |
| Signed APK SHA-256 | `a255cb7a432dd418cc21dfcc86017c5d46b8c322c820c5345c11d40fbba79387` |
| Signing certificate SHA-256 | `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD` |
| Retained evidence | [GitHub release assets](https://github.com/andrewginns/nanokvm-mobile/releases/tag/v0.3.6) |
| Field evidence | Owner-reported use for several weeks with NanoKVM application 2.4.3 without a reported issue |
| Promotion decision | Promoted unchanged to stable and **Latest** on 9 August 2026 |
| Scope disposition | Compatibility limits remain documented in the release notes; no universal device or operation coverage is claimed |

## Next stable-release candidate template

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
  mapping/seeds/usage/configuration/resources are retained from the exact
  commit in the candidate record. An exact-source archive and unsigned SHA-256
  evidence manifest are generated and independently checked before signing.
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
- [ ] A voice-capable installed IME stays out of app-forced incognito mode and
  commits one dictated phrase exactly once to the intended remote host; keyboard
  close, reconnect, and foreground loss leave no replayable text.
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

- [ ] Traffic contains only the selected HTTPS origin; HTTP profiles are
  rejected, merged manifest/Network Security Config deny cleartext, and
  certificate inspection sends no credentials, tokens, or application data.
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
- [ ] Field health-metrics review is attached when the chosen distribution
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
