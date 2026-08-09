# Publisher-owned Google Play actions

Repository work can prepare artifacts and evidence, but the following actions
require the account owner, verified identity, private signing custody, physical
hardware, or an explicit commercial/publication choice. Do not send identity
documents, passwords, payment details or private keys through repository issues.

## 1. Establish the publisher

- [ ] Choose **Personal** or **Organization** account type. A hobbyist publishing
  personally normally chooses Personal; an incorporated/business publisher
  chooses Organization and needs a matching D-U-N-S record.
- [ ] Create or verify the Play Console account, pay Google's one-time US$25
  registration fee if opening it, enable two-step verification, and keep the
  owner account recovery information current.
- [ ] Complete identity, email and phone verification. For a new Personal
  account, the account owner must also use the Play Console mobile app while
  signed into the same Google account to verify a non-rooted physical Android
  10+ phone or tablet.
- [ ] Approve the exact public developer name `{{PUBLISHER_NAME}}`. Ensure any
  public legal/contact information Google previews is acceptable before launch.
- [ ] Provide and monitor `{{SUPPORT_EMAIL}}`; approve the prepared support
  website `https://github.com/andrewginns/nanokvm-mobile`.

## 2. Register the app identity

- [ ] Create the Play app with package `org.nanokvm.mobile`, default language,
  “App,” and “Free.” A free app cannot later be changed to paid under the same
  package, so confirm that choice.
- [ ] Complete Android developer identity verification and check the package
  registration status. All Play packages must be registered by 30 September
  2026; register this package using the established production signing key when
  the Console asks for ownership proof.
- [ ] Confirm nobody else has already registered the package or trademark in a
  way that blocks the planned title. Respond to any ownership request through
  Play Console rather than changing the application ID casually.

## 3. Make the signing decision

This is the highest-risk one-time action.

- [ ] In the first Play App Signing flow, choose to provide the existing
  protected production app-signing key through PEPK. Verify that Play preserves
  the legacy/classical certificate SHA-256
  `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD`
  for Android 16 and earlier before accepting. Record the additional RSA-4096
  and ML-DSA-65 fingerprints Play creates for Android 17+ hybrid signing, and
  test delivery/update behavior on both OS generations.
- [ ] Create and register a different Play upload key outside the repository.
  Use `scripts/new-play-upload-keystore.ps1` from the protected signing account
  if desired; inspect its public certificate before registering it.
- [ ] Personally enter private-key credentials only in the protected signing
  workflow. Never provide them to Codex, Play listing text, source control,
  Gradle properties, CI logs or release attachments.
- [ ] Confirm encrypted backup/recovery and who is authorised to use each key.

Using a new Play app-signing identity would break direct-v0.3.6-to-Play updates.
Uninstalling is not a workaround: it removes profiles, certificate pins and
protected credentials.

## 4. Supply public policy and market choices

- [ ] Reconcile the tracked `PRIVACY.md` with the verified publisher identity
  and exact Play behavior **before** the final build, because that file is also
  bundled into the app's About surface. Publish the same approved notice at a
  stable, public, non-geofenced, non-editable HTTPS URL: `{{PRIVACY_URL}}` (not
  a PDF). It must name the app/publisher and provide a privacy contact.
- [ ] Open the privacy notice from the Play-delivered app and its public URL,
  verify both copies agree, and retain screenshots/URL checks with the release
  record.
- [ ] Approve the en-GB/en-US listing copy and non-affiliation wording under
  `store/google-play/listings/`.
- [ ] Approve the default locale and **Tools** category. Visually approve the
  icon, generated feature graphic and four current-source 1080 x 1920
  screenshots. Their generation/capture provenance and privacy review are in
  `store/google-play/assets/README.md`; repeat the controlled `FLAG_SECURE`-safe
  capture if visible source changes.
- [ ] Confirm that using the third-party `NanoKVM` name in the title/listing is
  acceptable nominative use in every launch market. The unofficial/non-
  affiliation disclaimer reduces confusion but is not itself trademark
  permission; change the public title if the publisher's rights review requires
  it.
- [ ] Choose the explicit launch-country list `{{LAUNCH_COUNTRIES}}`, then
  consider export, sanctions, consumer-support and reviewer-fixture reachability
  for those countries.
- [ ] Approve the target audience. The current recommendation is **18 and
  over**, not designed for children, because the app exposes specialist power,
  reset, administration and root-terminal controls.
- [ ] Approve the implemented first-release PicoClaw exclusion and verify it in
  the exact custom `play` artifact. Reversing that decision requires an in-app
  report/flag feature, a moderation operation and revised disclosures.
- [ ] Review and submit every App Content and Data Safety answer personally;
  Google holds the publisher responsible for their accuracy.

## 5. Operate reviewer access

- [ ] Provision the isolated public NanoKVM described in
  `PLAY_REVIEWER_ACCESS.md`.
- [ ] Put its reusable credentials in Play Console only. Keep it globally
  reachable, English-accessible and free of VPN, OTP, geofence and expiring
  credentials.
- [ ] Attach only a sacrificial/simulated host with no private data, and maintain
  out-of-band recovery because reviewers can reach real control surfaces.
- [ ] Test the instructions from a clean physical device and unrelated network
  before every submission.
- [ ] Monitor `{{SUPPORT_EMAIL}}` and fixture availability throughout review and
  after publication; update Console before rotating access.

## 6. Run Play testing and approve release

- [ ] Review and approve this repository diff, commit it, publish the exact
  source, and create the annotated `v0.3.7` tag without changing candidate
  bytes afterward. The public open-source/GPL claims require the distributed
  binary's exact corresponding source to remain available.
- [ ] From that clean tag, generate the schema-v2 unsigned evidence with
  `-IncludePlayBundle` and review its hashes, tests, lint, SBOM and retained
  Play R8 outputs.
- [ ] After the reviewed clean-tag evidence is generated, run the exact
  evidence-bound `playBundle` through the reviewed 16 KB page-size gate in
  `PLAY_PAGE_SIZE.md`; retain its immutable JSON and hash before signing.
- [ ] Sign only those reviewed AAB bytes with the registered upload key using
  `PLAY_UPLOAD_SIGNING.md`; verify the output certificate and hashes.
- [ ] Upload the reviewed AAB to Internal testing and personally install the
  Play-delivered build.
- [ ] Verify fresh install and a real in-place update from the production-signed
  v0.3.6 APK, including preservation of a disposable profile, pin and protected
  credential.
- [ ] Complete the exact-candidate device/fixture record in
  `PLAY_FIELD_TEST_EVIDENCE.md`.
- [ ] If the account is a Personal account created after 13 November 2023,
  recruit at least 12 suitable testers and keep all 12 continuously opted in to
  the **closed Play test** for at least 14 days. Outside-Play installs and the
  owner's prior weeks of v0.3.6 use do not satisfy this Console requirement.
- [ ] Give closed testers meaningful NanoKVM journeys and a feedback channel;
  retain feedback and fixes for the production-access application.
- [ ] Apply for production access when Console confirms eligibility, answer its
  testing/readiness questions truthfully, then review countries, release notes,
  declarations and managed-publishing settings.
- [ ] Personally press **Send for review** and, once approved, the final managed
  publishing/rollout control.

## Information to provide to complete the package

| Needed decision/input | Value |
| --- | --- |
| Public publisher name | `{{PUBLISHER_NAME}}` |
| Support email | `{{SUPPORT_EMAIL}}` |
| Support website | Prepared as `https://github.com/andrewginns/nanokvm-mobile`; publisher approval: |
| Privacy-policy URL | `{{PRIVACY_URL}}` |
| Launch countries | `{{LAUNCH_COUNTRIES}}` |
| Account type and creation date | |
| Default locale / category | Proposed: en-GB / Tools; publisher approval: |
| PicoClaw Play decision | Implemented as excluded in custom `play`; publisher approval: |
| Review-fixture owner, hostname and HTTPS port | `{{REVIEW_FIXTURE_HOST}}` / `{{REVIEW_FIXTURE_PORT}}` |
| Review username / secret custody | `{{REVIEW_USERNAME}}`; password remains outside the repository |
| Fixture certificate / NanoKVM versions | |
| Store graphics and rights approval | Four validated screenshots, icon and feature graphic are prepared; publisher approval: |
| Final Play version name/code | Prepared as `0.3.7` / `14`; publisher approval: |

## Official references

- [Create a Play developer account](https://support.google.com/googleplay/android-developer/answer/6112435)
- [Account information and account types](https://support.google.com/googleplay/android-developer/answer/10840893)
- [Developer identity and physical-device verification](https://support.google.com/googleplay/android-developer/answer/10841920)
- [Register Play package names](https://support.google.com/googleplay/android-developer/answer/16984799)
- [Testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)

Requirements were reviewed on 2026-08-03. Play Console's current requirements
and the account-specific tasks it displays take precedence.
