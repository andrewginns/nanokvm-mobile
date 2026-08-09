# Google Play App Content worksheet

Status: **draft; blocked on the exact built Play artifact and publisher inputs**.

This worksheet converts the current repository behaviour into proposed Play
Console answers. It is not a substitute for reviewing the questions displayed
by Play Console for the uploaded bundle. Record the final answer and reviewer
beside each row; do not silently treat a proposal as submitted fact.

## Submission assumptions

The proposed answer set assumes that the Play artifact is produced by the
custom `play` build type (`:app:bundlePlay`) and:

- has package `org.nanokvm.mobile`;
- contains no ads, analytics, telemetry, automatic crash reporting,
  developer-operated account, subscription, billing, or developer cloud;
- keeps the current HTTPS-only authenticated appliance transport;
- retains optional, explicitly selected WebRTC; and
- **does not expose or probe PicoClaw**.

The repository's direct `release` build retains its text-to-text PicoClaw chat
surface. The custom `play` build sets `PICOCLAW_ENABLED=false`, omits the
PicoClaw bundle and gateway from the production backend, rejects surface/action
resolution, and therefore has no Play-wired entry point, HID-lock surface or
PicoClaw protocol request. This is implemented policy separation, but it still
needs build/test evidence from the exact AAB. Never upload the generic
`bundleRelease` output using this answer set.

If PicoClaw is included in a future Play build, stop: Google's
AI-generated-content policy requires an in-app way to report or flag offensive
output without leaving the app, plus an operational moderation response. The
publisher must implement and operate those controls and revise the declarations
before submission.

## Store and contact details

| Console field | Proposed value | Final value / reviewer |
| --- | --- | --- |
| Default language | English (United Kingdom), unless the publisher chooses en-US | |
| App or game | App | |
| Free or paid | Free | |
| Category | Tools | |
| App title | `NanoKVM Mobile (Unofficial)` | |
| Developer name | `{{PUBLISHER_NAME}}` | |
| User-visible support email | `{{SUPPORT_EMAIL}}` | |
| Website | `https://github.com/andrewginns/nanokvm-mobile` | |
| Privacy policy | `{{PRIVACY_URL}}` | |
| Launch countries/regions | `{{LAUNCH_COUNTRIES}}` | |

The title and description deliberately identify the app as unofficial. Keep the
full-listing statement that NanoKVM Mobile is independent of Sipeed and is not
produced, endorsed, supported by, or affiliated with Sipeed.

## App Content declarations

| Declaration | Proposed Console answer | Evidence or remaining action |
| --- | --- | --- |
| Privacy policy | Public HTTPS URL supplied | Host a publisher-approved policy at `{{PRIVACY_URL}}`; it must match the exact artifact and name `{{PUBLISHER_NAME}}` plus a contact method |
| Ads | No | No ad SDK or ad surface is described by the current source; reconfirm the release dependency graph |
| App access | All or some functionality is restricted | A reachable NanoKVM and valid appliance credentials are necessary; use `PLAY_REVIEWER_ACCESS.md` |
| Target audience | 18 and over | Recommended for a specialist hardware-administration tool with power, reset and root-terminal controls; publisher must approve |
| Designed for children | No | Do not use child-directed listing imagery or copy; do not claim Families compliance |
| Content rating | Complete IARC questionnaire from the exact candidate | Proposed observations are below; accept the rating IARC generates rather than selecting one in advance |
| News app | No | The app is a hardware client, not a news product |
| Health app / health features | No | No health feature or health data access identified |
| COVID-19 contact tracing or status | No | No contact-tracing, exposure-notification, vaccination, testing or health-status feature exists |
| Financial features | No | No financial product, payment or financial data access identified |
| Government app | No | Independent open-source hardware client |
| Account creation | No | The app creates no publisher account; a NanoKVM appliance login is not a developer-operated app account |
| Account deletion URL | Not applicable if Console confirms no account creation | Users can remove local profiles/credentials or clear app data; this is distinct from deleting an appliance login |
| Data Safety | Do not finalise here | Complete `PLAY_DATA_SAFETY.md` after exact-candidate network review |
| Permissions declaration | None expected, subject to uploaded-bundle review | Current app requests Internet, local-network access, biometric/fingerprint compatibility, and merged WebRTC network state; it does not request SMS, call log, location, storage, camera or microphone |
| Advertising ID | No use identified | Reconfirm the merged manifest and dependencies do not include `AD_ID` |

Play Console can add or change declarations based on the uploaded AAB, account,
countries and current policy. Clear every item that Console marks “Needs
attention,” even when it is not listed above.

## IARC/content-rating observations

Use these as facts to interpret the live questionnaire, not as a memorised
answer sequence; IARC wording and branching can change.

- The publisher does not supply violence, sexual content, gambling, controlled
  substances, profanity, horror, or discriminatory content.
- Users do not publish content to other app users and cannot communicate with
  one another through the app.
- The remote framebuffer can display whatever is already present on the
  computer attached to the user's own appliance. The app does not provide a
  catalogue or social feed and does not operate that content source.
- Keyboard, pointer, clipboard typing and terminal tools can send user-entered
  material to the user's selected appliance and attached computer.
- The app exposes real power/reset controls and, on supported appliances, a
  guarded root terminal. Describe those capabilities accurately if a question
  asks about unrestricted access, dangerous activities, or user-created input.
- With PicoClaw absent, the Play variant does not generate AI content. If that
  statement changes, repeat both the policy and rating reviews.

## Appliance actions versus Android app actions

Use the following clarification consistently in listing copy and review notes:

> Controls labelled update, install or uninstall act on the connected NanoKVM
> appliance or an appliance-side component. They do not install or update the
> Android app. Android app updates are delivered by Google Play.

This distinction matters because the administration surface can update the
NanoKVM application, upload an offline appliance package, fetch a remote image,
or install appliance-side components. It does not justify declaring Android
package installation behaviour that is not present.

## Final review record

| Item | Value |
| --- | --- |
| AAB SHA-256 reviewed | |
| Version name/code | `0.3.7` / `14` |
| PicoClaw absent test reference, or AI compliance approval | |
| Merged-manifest review | |
| Dependency/SDK review | |
| Publisher approval | |
| Submission date and Console export/screenshots | |

## Official references

- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Target audience and content](https://support.google.com/googleplay/android-developer/answer/9867159)
- [Content-rating policy](https://support.google.com/googleplay/android-developer/answer/9898843)
- [AI-generated-content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
- [Play Console requirements](https://support.google.com/googleplay/android-developer/answer/10788890)

References and proposals were reviewed on 2026-08-02. Current Console wording
and policy take precedence at submission time.
