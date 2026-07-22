# Security policy

## Supported versions

NanoKVM Mobile 0.3.6 is distributed as a production-signed GitHub pre-release
candidate, not as an approved stable production release. Security fixes are
developed on the current 0.3.x source milestone. The earlier development APKs
use a different debug signing identity and are not a supported public
distribution channel.

| Version | Security status |
| --- | --- |
| 0.3.6 signed pre-release | Receives critical fixes until superseded; wider release gates remain open |
| `main` / 0.3.x development | Receives fixes |
| Debug-signed development snapshots | Superseded; no production update compatibility |
| Older snapshots | Unsupported |

## Report a vulnerability privately

Use [GitHub private vulnerability reporting](https://github.com/andrewginns/nanokvm-mobile/security/advisories/new)
for this repository. Do not open a public issue or discussion for an
undisclosed security problem. If GitHub does not show that action, contact the
owner through the repository profile without including exploit details and ask
for a private reporting channel.

Include the affected source commit and app version/code, Android version and
device type, NanoKVM hardware/application version, configured video transport,
reproduction steps, observed impact, and whether the issue can emit HID, GPIO,
terminal, script, update, network, or PicoClaw actions without clear user
intent. Redact passwords, tokens, private hosts, certificate fingerprints,
terminal contents, and remote framebuffer data. Maintainers will coordinate
validation, remediation, credit, and disclosure in the private advisory.

## Security boundaries

The app treats each NanoKVM as a separate trust domain. Certificate acceptance
is explicit and scoped to one saved connection. A changed fingerprint blocks
ordinary connection and shows the stored and presented identities. The user
must explicitly reject it, connect once while preserving the saved pin, or
replace the saved pin. Rotation is never automatic. Passwords and session
tokens must not appear in logs, crash messages, screenshots, exports, or source
control.

Password saving is opt-in. Saved values are AES-GCM encrypted with a
non-exportable, authentication-bound Android Keystore key and stored only in the
app's no-backup directory. A candidate replacement is not committed until the
NanoKVM login succeeds. Profile deletion first verifies removal of the saved
credential and reports any partial outcome instead of claiming full success.

Changing the host, port, protocol, or username is blocked while a protected
password exists; the user must explicitly remove it before changing that login
identity. Power, reset, long-press power, and Ctrl-Alt-Delete are intentionally
guarded actions. Security fixes for unintended input, TLS bypass, credential
exposure, or guard bypasses should be treated as release blockers.

The NanoKVM origin, authenticated signaling, and application control traffic
require HTTPS, and Android cleartext transport is disabled in both the manifest
and Network Security Config. Initial access-point Wi-Fi setup is deliberately
outside the app. Private and self-signed appliance certificates remain supported
through the inspection and pinning flow above. Explicit WebRTC mode has the
limited ICE-network exception described below.

Optional WebRTC signaling uses the authenticated NanoKVM origin, but native ICE
negotiation may contact STUN/TURN URLs supplied by the appliance. Those values
are bounded and validated as ICE URL schemes; users should enable WebRTC only
for an appliance they trust to choose those network peers.

System and serial terminals are root-equivalent appliance sessions. Script and
autostart management, offline/online updates, virtual media, DNS/Wi-Fi/Tailscale,
TLS enablement, and PicoClaw can persist changes or disrupt access. Their
authorities are foreground/session scoped, mutations are never automatically
replayed, and disruptive actions require explicit review. A response loss after
a mutation is indeterminate and must be reconciled with a fresh read rather than
blindly retried.
