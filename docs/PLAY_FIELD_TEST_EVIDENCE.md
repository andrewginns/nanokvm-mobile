# Google Play field-test evidence

This file separates useful prior-version experience from proof about the exact
bytes submitted to Google Play. Owner reports inform risk and test priorities;
they do not close exact-candidate, Play-delivery or closed-test gates.

## Owner-reported prior-version experience

| Field | Recorded statement |
| --- | --- |
| Report date | 2026-08-02 |
| Reporter | Repository/app owner (name not recorded here) |
| Build identified by reporter | The last release, identified for this record as v0.3.6 / version code 13 |
| Usage period | Owner reports regular use since that release, described as “a few weeks”; exact start/end dates have not been independently recorded |
| Environment | Owner described real-device testing; exact Android device/OS, whether the sessions used physical NanoKVM hardware, appliance hardware/image/application, and networks remain to be recorded |
| Outcome | Owner reports the app has been “rock solid” in normal use and did not identify a blocking defect in that report |
| Artifact identity verified? | No APK SHA-256, package dump or installed signing-certificate capture was supplied with the report |
| Evidentiary use | Prior-version confidence and test prioritisation only |

This report must not be represented as:

- proof that the next source revision or Play AAB was tested;
- proof that Google Play's generated split APKs work;
- a cross-store signature/update test;
- a complete device/Android/NanoKVM compatibility matrix;
- the mandatory 12-tester/14-day closed Play test; or
- verification of destructive, accessibility, performance, privacy or policy
  paths not specifically recorded.

If the owner still has the installed build, strengthen this prior evidence by
recording package, version, signer, device/OS, appliance versions, approximate
dates, typical session duration, networks, feature frequency, failures and any
workarounds. Preserve the distinction from the exact candidate.

## Prior-version detail supplement

| Field | Value |
| --- | --- |
| Physical device model | |
| Android version / build | |
| Installed package/version | |
| Installed certificate SHA-256 | |
| APK/source provenance | |
| NanoKVM hardware | |
| NanoKVM image/application version | |
| First/last use dates | |
| Approximate sessions and longest session | |
| Networks exercised (LAN/Wi-Fi/remote) | |
| Input devices and keyboard/IME | |
| Video modes exercised | |
| Features used regularly | |
| Failures, crashes, ANRs, reconnects or workarounds | |
| Supporting evidence | |

## Exact Play-candidate identity

Complete this section anew for every candidate. All evidence below must name the
same bytes or a traceable Play-generated derivative.

| Field | Value |
| --- | --- |
| Source tag / commit | |
| Clean worktree evidence | |
| Version name/code | `0.3.7` / `14` |
| Upload AAB SHA-256 / size | |
| Upload certificate SHA-256 | `{{UPLOAD_CERT_SHA256}}` |
| Play bundle explorer version / processing date | |
| Legacy/classical app-signing certificate SHA-256 | `B8:C5:6C:A6:A2:29:C8:5C:D8:29:DA:21:CF:69:72:19:E2:D1:A1:D5:F9:4D:65:87:19:EB:FA:9E:90:90:75:FD` |
| Play Android 17+ hybrid RSA certificate SHA-256 | `{{PLAY_HYBRID_RSA_CERT_SHA256}}` |
| Play Android 17+ ML-DSA-65 fingerprint | `{{PLAY_HYBRID_MLDSA_FINGERPRINT}}` |
| Delivered APK/APKS SHA-256 or install provenance | |
| Track and tester account | Internal / Closed / other: |
| Candidate QA owner / date | |

## Exact-candidate physical-device matrix

Add a row per meaningfully different device, Android version, input setup,
network or NanoKVM appliance version.

| Device / Android | Install path | NanoKVM hardware + image/app | Network | Session duration | Journeys exercised | Result | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | Play fresh install | | | | | | |
| | v0.3.6 production APK → Play update | | | | | | |
| | Android 17+ Play hybrid-signed install/update | | | | | | |

## Critical journey record

Use Pass, Fail, Blocked or Not run. “Pass” requires a named device/candidate and
supporting observation; ordinary prior use alone is not a result here.

| Journey | Result | Device/date | Notes / evidence |
| --- | --- | --- | --- |
| Fresh install, launch and local-network permission | | | |
| Add/edit/remove HTTPS profile | | | |
| System-trusted and private-certificate review paths | | | |
| Login, optional protected password, background/foreground | | | |
| Direct H.264, fallback to MJPEG, reconnect and disconnect | | | |
| Explicit WebRTC, actual ICE servers, fallback and teardown | | | |
| Direct touch, trackpad and external pointer | | | |
| Android keyboard, special keys and bounded clipboard/share typing | | | |
| Rotation, portrait/landscape, Fit/1:1, zoom and pan | | | |
| Read-only device details and capability gating | | | |
| Guarded power/reset/GPIO actions on sacrificial hardware | | | |
| Virtual media and Wake-on-LAN | | | |
| Appliance update/install wording and guarded administration | | | |
| Operator terminal/scripts/shortcuts/autostart | | | |
| PicoClaw absent from Play build, including no background probe | | | |
| Disconnect/reconnect after process death or network loss | | | |
| No automatic analytics, telemetry, crash report or unexplained endpoint | | | |
| Accessibility: screen reader, focus, large text and contrast | | | |
| v0.3.6 direct APK to Play-delivered update preserves disposable state | | | |

## Endurance and incident log

| Start/end UTC | Candidate/device/appliance | Activity | Crash/ANR/reconnect/memory/thermal observation | Severity | Evidence / issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Closed Play test evidence

This section is required only when Play Console applies the new-personal-account
production-access gate, but a closed test remains useful for any account.

| Field | Value |
| --- | --- |
| Closed track / release | |
| Opt-in URL | |
| Continuous 14-day window | |
| Eligible opted-in tester count at application time | |
| Device/Android/NanoKVM coverage | |
| Tester instructions and feedback channel | |
| Feedback summary | |
| Defects fixed / deferred with rationale | |
| Play Console eligibility evidence | |
| Production-access answers and date | |

Do not include tester email addresses or fixture passwords in this tracked file.
Retain personal details in an access-controlled record outside the repository.

## Release-readiness conclusion

| Question | Answer / evidence |
| --- | --- |
| Does prior v0.3.6 use increase confidence? | Yes, as owner-reported prior-version experience only |
| Was the exact uploaded AAB reviewed? | |
| Was the Play-delivered build tested on physical hardware? | |
| Did cross-store update compatibility pass? | |
| Was Data Safety reconciled to observed traffic? | |
| Were all blocking failures resolved or explicitly accepted? | |
| QA recommendation / owner / date | |
| Publisher go/no-go / date | |
