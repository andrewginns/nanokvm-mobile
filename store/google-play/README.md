# Google Play publication package

This directory contains draft Google Play listing copy, a validated icon and
feature graphic, and four visually reviewed current-source phone screenshots.
The screenshots use real v0.3.7 Compose screens on a controlled API 37/16 KB
emulator and still require final comparison with the signed Play-delivered
candidate. The package contains no credentials or signing material and does not
claim that a release is ready to upload.

The policy worksheets and operator checklists are in:

- [`docs/PLAY_APP_CONTENT.md`](../../docs/PLAY_APP_CONTENT.md)
- [`docs/PLAY_DATA_SAFETY.md`](../../docs/PLAY_DATA_SAFETY.md)
- [`docs/PLAY_REVIEWER_ACCESS.md`](../../docs/PLAY_REVIEWER_ACCESS.md)
- [`docs/PLAY_RELEASE_IDENTITY.md`](../../docs/PLAY_RELEASE_IDENTITY.md)
- [`docs/PLAY_UPLOAD_SIGNING.md`](../../docs/PLAY_UPLOAD_SIGNING.md)
- [`docs/PLAY_PAGE_SIZE.md`](../../docs/PLAY_PAGE_SIZE.md)
- [`docs/PLAY_FIELD_TEST_EVIDENCE.md`](../../docs/PLAY_FIELD_TEST_EVIDENCE.md)
- [`docs/PLAY_PERSONAL_ACTIONS.md`](../../docs/PLAY_PERSONAL_ACTIONS.md)

## Listing inventory

| Locale | Title | Short description | Full description | Release notes |
| --- | --- | --- | --- | --- |
| English (United Kingdom) | `listings/en-GB/title.txt` | `listings/en-GB/short-description.txt` | `listings/en-GB/full-description.txt` | `listings/en-GB/release-notes.txt` |
| English (United States) | `listings/en-US/title.txt` | `listings/en-US/short-description.txt` | `listings/en-US/full-description.txt` | `listings/en-US/release-notes.txt` |

The title is at most 30 characters, the short description is at most 80
characters, and the full description is at most 4,000 characters. Release
notes are kept below Play Console's 500-character per-language limit. Recheck
the counts after every edit; Play Console counts characters, not bytes.

Validate both locales and the intentionally unresolved publisher placeholders:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-play-store-metadata.ps1
```

## Asset inventory

| File | Intended use | Publication status |
| --- | --- | --- |
| `assets/play-icon-512.png` | 512 x 512 Play Store icon | Mechanically validated and visually reviewed; publisher approval remains |
| `assets/feature-graphic-1024x500.png` | 1024 x 500 feature graphic | Mechanically validated and visually reviewed; publisher approval remains |
| `assets/screenshot-phone-01-connections.png` | Phone connection-catalogue screenshot | Current v0.3.7 source UI; validated and visually reviewed |
| `assets/screenshot-phone-02-profile-editor.png` | Phone HTTPS profile-editor screenshot | Current v0.3.7 source UI; validated and visually reviewed |
| `assets/screenshot-phone-03-console.png` | Phone remote-console screenshot | Current v0.3.7 source UI with a generated harmless framebuffer; validated and visually reviewed |
| `assets/screenshot-phone-04-video-settings.png` | Phone Video settings screenshot | Current v0.3.7 source UI with all settings visible; validated and visually reviewed |
| `assets/play-icon-source.svg` | Deterministic icon provenance source | Source only; do not upload to Play Console |
| `assets/feature-graphic-imagegen-source.png` | Image-generation provenance source | Source only; do not upload to Play Console |
| `assets/README.md` | Provenance, privacy gates and validation command | Retain with release preparation records |

Google's current mechanical requirements are:

- Icon: 512 x 512, 32-bit PNG with alpha, at most 1,024 KB.
- Feature graphic: 1024 x 500, JPEG or 24-bit PNG with no alpha.
- Screenshots: JPEG or 24-bit PNG with no alpha, each dimension from 320 to
  3,840 px, with the long edge no more than twice the short edge. At least two
  screenshots across device types are required; up to eight may be supplied per
  device type.
- For stronger large-format eligibility, provide at least four app screenshots
  at 1080 px or greater using 9:16 portrait (at least 1080 x 1920) or 16:9
  landscape (at least 1920 x 1080).
- Give every graphic concise, locale-appropriate alt text (140 characters or
  fewer), and avoid rankings, testimonials, prices, calls to action, store
  badges, unlicensed third-party marks, private data and misleading affiliation.

The four phone screenshots are exact 1080 x 1920, 24-bit RGB PNG files without
device frames. They meet Google's four-image, 9:16, 1080p recommendation.

Production builds deliberately set `FLAG_SECURE`, so ordinary screenshots of
the exact Play build are expected to be black. The repository capture harness
hosts the same real composables in a test-only activity with deterministic state
and a harmless generated framebuffer. It does not weaken `FLAG_SECURE` in a
production artifact. Re-run it and compare the UI manually if candidate source
changes.

Prepared alt text (each under 140 characters):

| Asset | en-GB / en-US alt text |
| --- | --- |
| Play icon | NanoKVM Mobile app icon |
| Feature graphic | NanoKVM Full hardware, a desktop monitor and a phone showing the NanoKVM Mobile console on a dark navy background |
| Connections | Saved NanoKVM connections showing HTTPS and protected-credential status |
| Profile editor | NanoKVM HTTPS connection editor with host, port and username fields |
| Console | Connected NanoKVM remote console displaying a synthetic workstation |
| Video settings | NanoKVM video settings over a connected remote console |

The current screenshots use reserved endpoints, a generic username, no visible
fingerprint or credential values, and a rights-cleared generated framebuffer.
Before every recapture, repeat the scrub for hosts, usernames, certificate
fingerprints, credentials, terminal/chat content, IP/interface/ICE details,
notifications and private framebuffer content.

Reproduce the controlled capture on the dedicated 1080 x 1920 emulator with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-play-store-screenshots.ps1 -Serial emulator-5560
```

The runner refuses physical devices and any AVD other than
`NanoKVM_Play_Screenshots_API_37_16K`, requires API 37 and 16 KB pages, verifies
the established development signer before installation, and normalises only
that dedicated AVD's density, font, rotation and animation settings.

Validate the image formats, dimensions, alpha modes and expected filenames:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-play-store-assets.ps1
```

## Publication inputs still owned by the publisher

Replace every token below before copying material into Play Console. Keep the
double braces until a value is final so unfinished inputs remain searchable.

| Token | Required value |
| --- | --- |
| `{{PUBLISHER_NAME}}` | Verified public developer/publisher name shown by Google Play |
| `{{SUPPORT_EMAIL}}` | Monitored public support email |
| Support website | `https://github.com/andrewginns/nanokvm-mobile` |
| `{{PRIVACY_URL}}` | Public, stable, non-geofenced HTTPS privacy-policy page |
| `{{LAUNCH_COUNTRIES}}` | Explicit launch-country list, not a shorthand such as “worldwide” |
| Play version name | `0.3.7` for this candidate |
| Play version code | `14`; monotonically increased from 13 and not yet used in a Play track |
| `{{UPLOAD_CERT_SHA256}}` | Public SHA-256 of the separate Play upload certificate |
| Legacy/classical app-signing certificate | Confirm Play preserves `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD` for Android 16 and earlier/direct-update continuity |
| `{{PLAY_HYBRID_RSA_CERT_SHA256}}` | Play-displayed RSA certificate/fingerprint for Android 17+ hybrid signing |
| `{{PLAY_HYBRID_MLDSA_FINGERPRINT}}` | Play-displayed ML-DSA-65 key/certificate fingerprint for Android 17+ hybrid signing |
| `{{REVIEW_FIXTURE_HOST}}` | Publicly reachable review-appliance hostname; never its password |
| `{{REVIEW_FIXTURE_PORT}}` | Public HTTPS port for the review appliance |
| `{{REVIEW_USERNAME}}` | Dedicated reusable review username; the password stays only in Play Console/password manager |

Find every unresolved publication input with:

```powershell
rg -n '\{\{[A-Z0-9_]+\}\}' store/google-play docs -g 'PLAY_*.md'
```

## Rules before copying the drafts

1. Freeze the exact custom `play` variant and verify its implemented PicoClaw
   exclusion against the built artifact. If PicoClaw is present, the AI-content
   policy gate in `docs/PLAY_APP_CONTENT.md` must be resolved before submission
   and the listing must be updated to describe it.
2. Reconcile the Data Safety worksheet with a traffic capture from that exact
   minified candidate, including an explicit WebRTC attempt using the review
   fixture's actual ICE configuration.
3. Replace placeholders, recalculate listing lengths, and have the publisher
   approve the public name, contact details, privacy URL, countries, and copy.
4. Re-run the asset validator and capture harness after any visible source
   change. The publisher must approve the icon, feature graphic and screenshots,
   then compare their UI manually with the exact Play-delivered candidate.
5. Keep NanoKVM appliance operations distinct from Android app installation:
   update/install controls in the app act on the connected appliance. Google
   Play updates the Android app.

## Official references

- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Store-listing best practices and character limits](https://support.google.com/googleplay/android-developer/answer/13393723)
- [Preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Prepare an app for review](https://support.google.com/googleplay/android-developer/answer/9859455)

Requirements were last checked against the linked Google documentation on
2026-08-03. Recheck them in Play Console immediately before submission.
