# Comet Mobile security

The first console targets stock GL-RM1 firmware 1.10.3 release1. Physical acceptance and distribution signing are pending. Report issues privately through the [Comet repository](https://github.com/andrewginns/comet-kvm-mobile/security/advisories/new).

Each profile selects one HTTPS origin. Compare the leaf SHA-256 fingerprint through an independent trusted channel on first use and after a change. The transport verifies the reviewed certificate and its validity dates on login and on both WebSocket connections. The explicit pin permits an appliance with a localhost-only certificate to be reached at its approved IP or VPN name. Redirects and automatic replay of HTTP requests are disabled.

Passwords can be protected with Android device credentials. TOTP codes are collected for each login and session cookies live in process memory. Console input can affect the attached host. Paste confirmation is bound to the profile, authority and authenticated session generation. On control loss the app invalidates queued input. Host-side cleanup after an abrupt loss depends on firmware and requires a physical acceptance check.

Include the app version, firmware version, Android version, affected entry point and observed behavior in a private report. Keep credentials, session tokens, network addresses and remote screen contents private.
