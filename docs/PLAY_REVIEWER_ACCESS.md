# Google Play reviewer-access fixture

NanoKVM Mobile cannot demonstrate its core functionality without a reachable
NanoKVM appliance and an appliance login. In Play Console choose **All or some
functionality in my app is restricted** and provide a dedicated review fixture.
Do not put the real password or other reusable secret in this repository.

Google requires review access to remain available, reusable, valid from any
location and free of one-time codes. The fixture is an ongoing publication
dependency, not a one-off launch convenience.

## Fixture requirements

- Use an isolated, Internet-reachable NanoKVM with a stable DNS hostname in
  `{{REVIEW_FIXTURE_HOST}}`; an RFC 1918 address, `.local` hostname, VPN or
  Tailscale-only route is not sufficient for Google reviewers.
- Use HTTPS with a currently valid, publicly trusted certificate matching the
  hostname. Prefer TLS termination on the isolated appliance. Avoid making a
  reviewer interpret a self-signed certificate warning; if a reverse proxy is
  unavoidable, record who operates it, what it can read and retain, and revisit
  the privacy/Data Safety classification before submission.
- Provide a dedicated reusable username and password in Play Console. No OTP,
  two-step prompt, client certificate, IP allowlist, geofence, expiring password
  or approval from another person may block access.
- Keep instructions and credentials in English and verify them from a clean
  device on an unrelated network.
- Attach the NanoKVM to a sacrificial or simulated host containing no personal,
  production, customer or infrastructure data. Assume the reviewer can exercise
  keyboard, mouse, power, reset, virtual-media, terminal and administration UI.
- Restrict or capability-gate unsafe appliance features where possible without
  misrepresenting the submitted app. Keep a recovery image and an out-of-band
  way to restore the fixture.
- Do not expose production credentials, private network routes, reusable API
  keys, real terminal history, personal remote desktop content or sensitive
  virtual-media images.
- Keep the app's supported NanoKVM application version installed. Record the
  exact hardware, image and application versions below.
- Monitor availability without logging reviewer passwords, typed content,
  framebuffer data, session tokens or full network identifiers.
- Re-verify the fixture before every submission and after any certificate,
  password, DNS, firmware, network or appliance change.

## Fixture inventory

| Field | Value |
| --- | --- |
| Public hostname | `{{REVIEW_FIXTURE_HOST}}` |
| HTTPS port | `{{REVIEW_FIXTURE_PORT}}` |
| Certificate issuer / expiry | |
| Review username | `{{REVIEW_USERNAME}}` (secret value belongs only in Play Console/password manager) |
| Password custody and rotation owner | |
| NanoKVM hardware model | |
| NanoKVM image version | |
| NanoKVM application version | |
| Attached harmless host/fixture description | |
| Disabled or unavailable capabilities | |
| Recovery owner and method | |
| Availability monitor | |
| Last clean-device external-network test | |

## Text to adapt for Play Console

Copy only after replacing the fixture fields. Put the password in Play
Console's credential field, not in “Any other instructions” and never here.

> NanoKVM hardware and a NanoKVM login are required to use this app. We provide
> a dedicated isolated review appliance containing no private data.
>
> 1. Open NanoKVM Mobile.
> 2. Choose “Add connection.”
> 3. Display name: `Google Play Review`.
> 4. Host: `{{REVIEW_FIXTURE_HOST}}`.
> 5. Keep HTTPS enabled and enter port `{{REVIEW_FIXTURE_PORT}}`.
> 6. Username: `{{REVIEW_USERNAME}}`.
> 7. Save the profile and choose Connect.
> 8. Enter the reusable password supplied in the password field. Saving it on
>    the review device is optional. No OTP, VPN or location restriction applies.
> 9. A remote test display should appear. The floating buttons open the Android
>    keyboard, reviewed clipboard typing and console controls. “More actions”
>    opens device details and supported appliance tools.
>
> This is an independent, unofficial client and is not affiliated with Sipeed.
> Controls labelled update, install or uninstall operate on the connected
> NanoKVM appliance or an appliance-side component. They do not install or
> update the Android app; Google Play delivers Android app updates.
>
> If access fails, contact `{{SUPPORT_EMAIL}}` and include the review time and
> visible error only. Do not send the password in email.

The custom Play variant deliberately disables PicoClaw. After the delivered
artifact passes its entry-point and background-probe tests, add: “PicoClaw is
not included in this Google Play build.”

## Safe reviewer journey

The fixture should support this reproducible, non-destructive route:

1. Add and connect the supplied HTTPS profile.
2. Observe the harmless remote display using Auto video.
3. Open console controls; switch between Direct and Trackpad input.
4. Open the Android keyboard and type into a designated disposable text field.
5. Change Fit/1:1 scaling and video quality.
6. Open Device details and other read-only supported pages.
7. Review, but do not require, confirmation-gated hardware actions to understand
   the app. If policy review may exercise them, the attached host must tolerate it.
8. Disconnect and reconnect with the same credentials.

Optional WebRTC must also be testable during internal/closed QA because it
affects Data Safety analysis. It need not be the reviewer's primary path. Record
the fixture's real ICE servers and endpoint operators in `PLAY_DATA_SAFETY.md`.

## Availability and incident log

| Date/time UTC | Check or incident | Result | Remediation / owner |
| --- | --- | --- | --- |
| | External clean install and login | | |
| | DNS/TLS expiry check | | |
| | NanoKVM/video health check | | |

If credentials are rotated or the fixture moves, update Play Console before
submitting another review. Rotate any credential exposed through a support
ticket and restore a valid replacement immediately.

## Official reference

See Google's [requirements for sign-in details for review](https://support.google.com/googleplay/android-developer/answer/15748846).
Requirements were checked on 2026-08-02 and must be rechecked before submission.
