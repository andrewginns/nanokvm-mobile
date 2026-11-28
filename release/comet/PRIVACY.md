# Comet Mobile privacy notice

Comet Mobile connects to the GL.iNet Comet address selected by the operator. Login, control, pointer-mode selection, and Direct H.264 use the same HTTPS origin. Local connections and connections over an existing VPN use the same trust checks.

## Profiles and sign-in

The app stores profile names, addresses, ports, usernames, the verification-code preference, and approved certificate fingerprints in its private Android DataStore. It shows the leaf certificate before first login. Trusting a certificate for a profile allows that exact leaf at that endpoint, including stock certificates that name localhost. A change requires fresh review.

Saving a password is optional. Android Keystore protects it with an authentication-bound AES-GCM key, and the encrypted record is bound to the profile and login identity. Enter the current six-digit TOTP separately when enabled. Codes and session cookies stay in memory. Android text fields, the IME, and HTTP serialization can create transient copies of entered text.

Profiles, pins and encrypted passwords are excluded from Android backup. Removing a profile also removes its protected password. Clearing app data or uninstalling removes the installation’s private records. Comet Mobile and NanoKVM Mobile have distinct Android application IDs.

## Console and shared text

The app decodes remote video for display. Keyboard, pointer and approved text input go to the selected appliance. Clipboard reads are initiated by the user. Android shared text and clipboard text open a local editor for preview and confirmation. The app keeps pending text in memory and discards it when the target or session changes. Disconnecting ends the local transport and forgets its cookie. The appliance controls its server-side session lifetime.

Android keyboards and accessibility services process interactions under their own settings. Screenshots and screen sharing can include the remote console. On Android 13 and later the app disables saved Recents snapshots when it stops.

The app has no developer-operated cloud service, analytics or automatic crash reporting. Redact hosts, certificate identifiers, credentials, typed text and remote screen contents from voluntary issue reports.
