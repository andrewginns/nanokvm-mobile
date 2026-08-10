# Security policy

This file covers supported versions and vulnerability reporting. Maintainer
security boundaries and verification guidance are in the
[technical security model](docs/SECURITY.md).

## Supported versions

NanoKVM Mobile 0.3.6 is the current stable, production-signed GitHub release.
Security fixes are developed on the current 0.3.x source milestone. Earlier
development APKs use a different debug signing identity and are not a supported
public distribution channel.

| Version | Security status |
| --- | --- |
| 0.3.6 stable GitHub release | Receives critical fixes until superseded |
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

The app treats each NanoKVM as a separate trust domain, requires HTTPS, and
scopes certificate decisions and optional protected credentials to one saved
connection. Changed pins fail closed. Root-equivalent, persistent, destructive,
and unintended-input paths require explicit user intent and are release
blockers when their trust or recovery behavior is uncertain. The maintained
threat boundaries, data flows, mitigations, and verification rules are in the
[technical security model](docs/SECURITY.md).
