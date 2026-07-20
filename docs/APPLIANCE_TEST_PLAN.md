# NanoKVM appliance verification plan

Use this matrix for retained parity evidence against a real appliance. The
default target is application 2.4.3; repeat capability/fallback cases on the
2.3.2 compatibility floor when a recovery-capable target is available.

Never store credentials, JWTs, clipboard/terminal contents, framebuffer
captures containing private data, provider keys, or unredacted network
configuration in test artifacts.

## Evidence header

Record for every run:

- app commit, dirty-tree status, version, signing-certificate digest, and APK
  SHA-256;
- Android device/model/ABI/API/security patch and orientation/window mode;
- NanoKVM hardware and application/image versions;
- authority form (IP/mDNS), network topology, HTTPS trust mode, and redacted
  certificate fingerprint; NanoKVM origin/control traffic must remain HTTPS,
  with any explicit WebRTC STUN/TURN ICE peers recorded separately;
- case ids, exact command/manual script, timestamps, result, redacted
  log/screenshot/trace paths, and artifact archive location; and
- finding owner, disposition, and rerun link.

## Safe connected smoke tests

These are read-only or ordinary console inputs and may run on a trusted daily
appliance after confirming the controlled host is at a disposable prompt.

| ID | Check | Expected result |
| --- | --- | --- |
| A-S01 | Login, background/foreground, manual reconnect | One session owner; no stale input; video resumes without needing IME side effects. |
| A-S02 | Direct H.264 for at least 30 continuous minutes | First frame, stable FPS/size, no black preview; keyboard/pointer and foreground-cycle checks remain responsive; stall causes a fresh bounded reconnect; memory/network observations are retained. |
| A-S03 | Force/choose MJPEG for at least 30 continuous minutes with frame detection off, then explicitly enable it and restart MJPEG | Fresh fallback surface, correct size/touch mapping; keyboard/pointer and foreground-cycle checks remain responsive; enabled start sends one temporary pause and does not require opening the IME to reveal video; memory/network observations are retained. |
| A-S04 | Keyboard US/UK, AltGr/right modifiers, keypad/edit/media keys, and external mouse five buttons/wheels | Exact single input, no repeat/Compose duplication, and release-all on close/background. |
| A-S05 | Approved paced clipboard text then cancel | Preview matches destination/layout; no auto-type; cancellation stops without later replay. |
| A-S06 | GPIO, version, hardware, capabilities, RTT/backoff | Read-only state is bounded; failed polling backs off and success resets cadence. |
| A-S07 | Virtual-media, WOL, administration and script list reads | Supported surfaces load; unsupported routes explain rather than breaking console. |
| A-S08 | Open Device details and compare version, image, hardware, device key, interfaces, and capability summary | Bounded values match the appliance; unknown capabilities stay visibly unknown and no write probe is emitted. |

## Recovery/disposable-target tests

Run these only with host storage safely unmounted where applicable, a known
recovery path to the appliance, and explicit review of the exact target/value.

| ID | Check | Expected result |
| --- | --- | --- |
| A-R01 | Mount ISO as mass storage and CD-ROM; restore physical media | One dispatch; HID neutral/recycled; authoritative mounted/mode readback. |
| A-R02 | Attempt delete of mounted image, then delete an unmounted test image | Mounted delete blocked locally; exact opaque handle deleted once and list refreshed. |
| A-R03 | Remote test-image download | Validated URL, visible 2.5 s progress, no auto-mount; 2.4.3 cancel/checksum limitation stated. |
| A-R04 | Toggle virtual disk | Pre-read desired-state logic; one toggle only; input channel recycled and state reconciled. |
| A-R05 | Send WOL to a designated test NIC and manage its history | Canonical MAC; no claim that acknowledgement proves target wake; rename/delete exact entry. |
| A-R06 | Change preview/OLED/title, then restore | Explicit desired values, readback, no replay. |
| A-R07 | Change hostname/mDNS/DNS/SSH on recovery-capable LAN | Loss-of-access warning; rediscovery/reconnect guidance; authoritative post-change state. |
| A-R08 | Reboot and online update on a recovery image | One shot, expected disconnect, bounded rediscovery; never repeat after ambiguous EOF. |
| A-R09 | Root terminal and serial loopback | Foreground-only socket, resize, binary PTY output, typed serial allowlists, one-shot close. |
| A-R10 | Upload/run/delete harmless fixture scripts | Opaque latest-list handles, output cap, no retry; document that HTTP cancellation does not stop execution. |
| A-R11 | Change a disposable account password with and without protected saving; separately exercise a definite server rejection | Android authentication precedes a saved-replacement dispatch; acknowledgement updates local identity then disconnects; rejection preserves the old session and saved credential. Restore the original credential manually. |
| A-R12 | Install a known disposable offline-update package from Android's document picker | Exact package/version review, one upload, no persisted URI grant, expected restart/disconnect, and manual version verification after any ambiguous result. |
| A-R13 | Configure a disposable Wi-Fi target from an authenticated session and exercise non-destructive Tailscale state transitions | Manual SSID only, no scan, official HTTPS login handoff, and one dispatch per reviewed command. Initial AP setup remains outside the app. |
| A-R14 | Record/run/delete a harmless HID shortcut and create/update/delete harmless autostart fixtures | Exact reviewed key/content state, final HID release, opaque latest-catalog handles, no replay, and fixtures removed after the run. |

## Capability-gated extension tests

| ID | Check | Expected result |
| --- | --- | --- |
| A-X01 | WebRTC success for 30 minutes, then forced negotiation/ICE/decoder failure | Streaming appears only after a decoded surface frame; heartbeat and frame continuity remain stable; one negotiation generation is torn down; manual mode remains selected; fresh fallback proceeds to direct H.264 then MJPEG without replaying offer/candidates/media. Record first-frame time, PSS, traffic, thermal state and APK/ABI. |
| A-X02 | PicoClaw absent/present explicit entry | No status probe before entry; absent state is nonfatal. |
| A-X03 | PicoClaw chat cancellation and session close | Manual-HID lock is conspicuous; cancel does not claim release; closing/releasing restores manual input. |
| A-X04 | PicoClaw start/stop/install/uninstall on disposable image | At-most-once dispatch and status reconciliation; uninstall requires destructive confirmation. |

Run A-X01 whenever WebRTC is enabled and supported for the release candidate;
otherwise retain the capability-gated exclusion. Apply the same rule to the
explicitly entered PicoClaw cases.

## Android matrix

Retain at least one phone result on API 26, 29, 31, 33, 34, 35, 36, and 37 for
the clipboard/IME/lifecycle flow, following the full required-device matrix in
`RELEASE_CHECKLIST.md`. On API 37 also cover portrait, landscape,
split-screen, keyboard-open viewpad movement/zoom/scroll, system font 200%,
TalkBack traversal, and an external keyboard/mouse. Run long video and WebRTC
performance cases on physical hardware; emulator decoder results are not
release evidence.
