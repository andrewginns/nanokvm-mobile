# NanoKVM WebUI parity ledger

This ledger tracks functional parity between NanoKVM Mobile and the official
NanoKVM Cube/PCIe WebUI. It is a delivery ledger, not a promise that every
browser-specific presentation detail will be copied into the native app.

## Baseline and maintenance rules

- Reference inventory: Cube/PCIe application **2.4.3** WebUI and server API,
  tag commit `3b2ba7c0c1214f44da9d328f90bbdd025fac0413` (2026-06-09).
- Compatibility floor: application **2.3.2**. A feature introduced later must
  be hidden or disabled with an explanation; it must not make the console fail.
- Primary sources are the upstream
  [WebUI API clients](https://github.com/sipeed/NanoKVM/tree/main/web/src/api),
  [WebUI desktop controls](https://github.com/sipeed/NanoKVM/tree/main/web/src/pages/desktop),
  [server routes](https://github.com/sipeed/NanoKVM/tree/main/server/router), and
  [Cube user guide](https://wiki.sipeed.com/hardware/en/kvm/NanoKVM/user_guide.html).
- Re-audit this ledger when the reference application version changes. Record
  the upstream tag/commit in the release evidence; `main` is discovery input,
  not a stable compatibility contract.
- Capability discovery may make safe authenticated `GET` requests. Never probe
  a write-only capability by invoking it. Use application version, hardware
  information, and previously observed response fields for those gates.
- A row moves to **Supported** only when its user flow, failure state, capability
  gate, lifecycle cancellation, and required verification are implemented.

### State, phase, and risk notation

| Code | Meaning |
| --- | --- |
| S | Supported in the Android app. |
| P | Partial: some protocol/runtime/UI support exists, but the flow or evidence is incomplete. |
| N | Planned and absent from the current app. |
| X | Deliberately excluded or replaced by a native equivalent. |

Phases are: **P0** session/capability foundation, **P1** clipboard/status/input
quick wins, **P2** console parity, **P3** virtual media and Wake-on-LAN,
**P4** appliance administration, **P5** terminal/scripts, and **P6** optional
transports/extensions.

| Risk | Default interaction policy |
| --- | --- |
| R0 | Read-only; no confirmation. |
| R1 | Session-scoped/reversible action; explicit control is sufficient. Remote paste also requires preview and confirmation. |
| R2 | Persistent configuration; review the target and new value. Confirm changes that can break access. |
| R3 | Destructive, disruptive, or externally writing; consequence confirmation, session-generation binding, no retry/replay. |

Evidence abbreviations are **C** source/contract review, **U** automated unit or
UI test, **E** emulator/device UI flow, **A** real NanoKVM appliance, and **R**
signed release candidate. `open` is required work, not a passing result.

## Foundation and console

| ID | Capability | State | Firmware/capability gate | Hardware dependency | Risk / confirmation | Phase | Evidence / exit proof |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-01 | Profile login, authenticated REST/WebSockets | S | base `>=2.3.2` | Cube/PCIe | R1; explicit Connect | P0 | C/U: protocol auth, cookie, redirect and reconnect tests; E/A/R open |
| F-02 | HTTPS system trust, self-signed leaf review and saved pin | S | endpoint TLS, independent of app version | HTTPS endpoint | R2; explicit trust review | P0 | C/U: trust preflight and pin tests; E/A/R open |
| F-03 | Saved password with biometric/device-credential unlock | S | Android capability gate | Android secure lock screen | R2; OS authentication | P0 | C/U: Keystore/coordinator tests; E/R open |
| F-04 | Authenticated session owner shared by feature APIs | P | base; one origin and session generation | none | R1-R3 policies inherit owner | P0 | C/U: every feature is generation-bound; a classified 401 closes command acceptance, releases input, invalidates feature gateways, and clears token/transports exactly once; A open |
| F-05 | Capability snapshot and graceful unsupported states | P | version + safe endpoint/field probes | reported hardware | R0 | P0 | C/U: SemVer floors, partial/404/auth probes, and a tri-state native capability sheet; A on 2.3.2 + 2.4.3 open |
| F-06 | Certificate rotation comparison flow | P | saved-pin mismatch | HTTPS endpoint | R2; old/new identity review | P0 | C/U: mismatch now shows old/new fingerprints; connect-once preserves the old pin and replace is explicit; open: E/A/R |
| C-01 | H.264 direct console with MJPEG fallback | S | base stream endpoints | HDMI capture | R0 | P2 | C/U: transport/parser/reconnect tests; E/A/R open |
| C-02 | Resolution, maximum FPS, H.264 bitrate/MJPEG quality | S | base `/api/vm/screen` | HDMI capture | R1; explicit Apply | P2 | C/U: screen wire/range tests; A/R open |
| C-03 | GOP/keyframe interval | P | field/setting probe; present in 2.4.3 reference | H.264 capture | R1 | P1 | C/U: session model, 10/30/50/100-frame UI presets, exact server write and range tests; A open |
| C-04 | Frame-difference detection and temporary wake | P | write routes in 2.4.3 reference; no authoritative state GET | MJPEG capture | R1 | P2 | C/U: off-by-default persisted preference, changed-only write, generation-bound cancellation, and one coalesced 10-second pause per MJPEG start/fallback; A open |
| C-05 | HDMI capture state, enable/disable, and reset | P | safe state GET; control runtime-gated from `2.2.8`, reset from `2.1.5` | HDMI capture | R3 for disable/reset; consequence confirmation | P4 | C/U: one-shot read-before-write/readback gateway and confirmed UI; open: E/A on disposable target |
| C-06 | Fit, pan, zoom, over-pan and movable/docked viewpad | S | native app capability | touch display | R0 | P2 | C/U: viewport and Compose tests; E accessibility/keyboard matrix open |
| C-07 | Immersive/full-screen console | P | native app capability | Android window | R0 | P2 | Explicit console-only toggle, transient edge-swipe recovery, Back exit, and disposal restoration implemented; E open |
| C-08 | Stream diagnostics: transport, size, FPS, dropped/stalled frames | P | local transport events | video stream | R0 | P1 | C/U: cumulative drops/stalls and transport/FPS/latency live in the compact status surface; no framebuffer banner; E/A open |
| C-09 | Network round-trip indicator | P | authenticated ping/cheap read policy | network | R0 | P1 | C/U: bounded foreground GPIO sampler populates latency, applies exponential failure backoff, resets after success, and cancels with transport; A open |

## Input and host control

| ID | Capability | State | Firmware/capability gate | Hardware dependency | Risk / confirmation | Phase | Evidence / exit proof |
| --- | --- | --- | --- | --- | --- | --- | --- |
| I-01 | Direct absolute touch and relative trackpad | S | base input WebSocket | host HID USB | R1; connected foreground only | P2 | C/U: HID golden bytes/runtime tests; E/A open |
| I-02 | Vertical and horizontal scrollpad | S | base input WebSocket | host HID USB | R1 | P2 | C/U: gesture/runtime tests; E/A sensitivity matrix open |
| I-03 | Native IME text, modifiers, arrows and function keys | S | base input WebSocket | host HID USB | R1 | P2 | C/U: mapping/IME/latch tests; E/A layouts and lifecycle open |
| I-04 | Complete physical keyboard key-down/key-up mapping | P | base input WebSocket | external keyboard, host HID USB | R1 | P2 | C/U: Android key matrix covers navigation, locks, keypad, right modifiers/AltGr, media/edit keys, repeat suppression and lifecycle release; E/A open |
| I-05 | Left/right/middle mouse buttons | P | base input WebSocket | host HID USB | R1 | P1 | C/U: discoverable one-shot controls plus external absolute/relative mouse routing, cancellation and release; E/A open |
| I-06 | Mouse back/forward buttons | P | base 2.4.3-compatible HID report | compatible firmware and host HID | R1 | P1 | C/U: exact report bytes, compact controls, and external mouse button mapping with duplicate-dispatch prevention; E/A open |
| I-07 | Forward Delete and remaining non-text keys | P | base input WebSocket | host HID USB | R1 | P1 | C/U: native accessory and physical mappings cover Delete, navigation, keypad, edit and media usages; E/A open |
| I-08 | HID release-all and reset | S | base; `/api/hid/reset` | host HID USB | R1; explicit reset | P2 | C/U: release ordering/reset route tests; E/A open |
| I-09 | Server HID mode selection | P | probe `/api/hid/mode`; 2.4.3 reference | compatible firmware | R2; warn that input may stop | P2 | C/U: NORMAL/HID_ONLY UI, unknown read-only state, one-shot readback, and input-WebSocket recycle; E/A open |
| I-10 | Saved shortcut list, leader key, record/add/delete | P | shortcuts `>=2.3.2`; leader key `>=2.3.4` | host HID USB | R2; review sequence before save/run | P2 | C/U: exact 190-code map, bounded CRUD, physical recorder, latest-snapshot delete, incremental HID run/final release, and foreground native surface; E/A open |
| I-11 | Android clipboard to host by HID typing | P | base `/api/hid/paste` or paced input WebSocket | host HID USB | R1; preview destination/layout, then confirm | P1 | C/U: strict direct-text gateway, sensitive preview, destination-bound layout preflight, cancellable paced typing, progress, and session binding; API 26/29/31/33/34/37 E/A open |
| I-12 | True host-to-Android/bidirectional shared clipboard | X | no NanoKVM WebUI/server capability | would require a paired host agent | R3; separate threat model and pairing | - | Explicitly out of agentless parity scope; see clipboard boundary below |
| I-13 | Android plain-text share target into paste preview | P | Android intent capability + I-11 | host HID USB | R1; never auto-send | P1 | C/U/E: strict plain-text route enters the same preview and never auto-types; open: connected cold-start/process-death matrix on supported APIs |
| I-14 | Mouse jiggler | P | state/write floor `>=2.2.6` | host HID USB | R2; persistent-state review | P4 | C/U: off/relative/absolute, unknown read-only state, readback and confirmed UI; E/A open |
| H-01 | ATX short/long power press and reset | S | base GPIO | Full/PCIe ATX wiring; optional Lite expansion | R3; consequence confirmation | P1 | C/U: command gate/GPIO tests; A/R on disposable target open |
| H-02 | Host power and HDD activity status | P | base `/api/vm/gpio` | ATX status wiring; HDD may be absent | R0 | P1 | C/U: initial probe and foreground polling use bounded exponential failure backoff with reset after success; A open |

### Clipboard boundary

NanoKVM's paste endpoint and keyboard WebSocket **type USB HID keystrokes into
the host**. They do not read or write the host operating system clipboard, do
not preserve rich clipboard content, and cannot synchronize host clipboard
changes back to Android. The product label must therefore be "Paste Android
clipboard to remote", never "shared clipboard". Genuine two-way clipboard
support is a separate future product requiring a paired host agent, scoped
credentials, loop suppression, origin/sequence metadata, and its own security
review.

## Virtual media and Wake-on-LAN

| ID | Capability | State | Firmware/capability gate | Hardware dependency | Risk / confirmation | Phase | Evidence / exit proof |
| --- | --- | --- | --- | --- | --- | --- | --- |
| V-01 | List images and show current mount/CD-ROM mode | P | safe GET probes; 2.4.3 reference | image storage partition | R0 | P3 | C/U: bounded canonical list, session-bound opaque catalog, and foreground native surface; E/A open |
| V-02 | Mount, unmount, and switch image | P | V-01 plus mount route | image storage + host HID USB | R3; warn about host I/O/data loss | P3 | C/U: consequence review, exact one-shot route, stale-generation rejection, input recycle and readback reconciliation; E/A open |
| V-03 | Delete stored image | P | image list + delete route | image storage partition | R3; identify exact filename/impact | P3 | C/U: arbitrary paths impossible, exact-item confirmation, fresh mounted guard, ambiguous no-replay and refresh; E/A open |
| V-04 | Download an image to appliance and show progress | P | safe enabled/status GETs; 2.4.3 reference | free storage + appliance internet | R3; URL/storage review; 2.4.3 has no stable cancel/checksum | P3 | C/U: credential-free HTTP(S) image URL review, bounded status and surface-only 2.5 s polling; E/A open |
| V-05 | Enable/disable virtual USB mass-storage device | P | probe `/api/vm/device/virtual` | host HID USB | R3; safe-eject warning | P3 | C/U: native confirmation, GET-desired-state -> one toggle -> readback/reconcile and input recycle; E/A open |
| W-01 | Send Wake-on-LAN magic packet | P | base/reference route availability | target NIC/LAN configured for WOL | R1; explicit Send | P3 | C/U: canonical MAC, exact native review, one route dispatch and no delivery overclaim; E/A open |
| W-02 | WOL MAC history, names, and deletion | P | safe history GET; name/delete routes | none | R2 for stored history mutation | P3 | C/U: bounded opaque snapshot, native CRUD, duplicate/legacy handling and no replay; E/A open |

## Appliance administration

| ID | Capability | State | Firmware/capability gate | Hardware dependency | Risk / confirmation | Phase | Evidence / exit proof |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-01 | Application/image version, device key and network interfaces | P | base `/api/vm/info` and hardware | none | R0 | P1 | C/U: bounded control-character-filtered session mapper and selectable native device sheet; identifiers are redacted from diagnostics; E/A open |
| A-02 | Hardware revision/capability summary | P | base `/api/vm/hardware` | board-specific | R0 | P1 | C/U: hardware fallback plus sorted available/unavailable/runtime-check capability summary; E/A open |
| A-03 | Account password change | P | authenticated account route probe/version gate | none | R3; current/new confirmation, clear sessions | P4 | C/U: opt-in Android-authenticated credential staging, mutable zeroed secret, exact binding, acknowledged/rejected/indeterminate policies, 401 teardown, UI and mandatory session handling; A/R open |
| A-04 | Application online/preview/offline update | P | version + update/preview availability; offline `>=2.3.1`, preview `>=2.2.5` | storage + appliance internet/file | R3; version/channel and interruption warning | P4 | C/U: online controls plus transient OpenDocument source, no persistent URI/grant/path, exact-name 32 KiB streaming, cancellation-safe one-shot review/progress UI; A open |
| A-05 | Appliance reboot | P | 2.4.3 reference route | none | R3; consequence confirmation | P4 | C/U: native consequence confirmation, exact generation-bound one-shot and indeterminate guidance; E/A open |
| A-06 | OLED sleep configuration | P | available from `2.1.4`; state GET | Full/PCIe OLED | R2 | P4 | C/U: hardware existence, exact native presets and readback gateway; E/A open |
| A-07 | SSH enable/disable | P | available from `2.1.6`; state GET | none | R3; access/recovery warning | P4 | C/U: native recovery warning, explicit desired-state endpoint/readback and no replay; E/A open |
| A-08 | Hostname and mDNS enable/disable | P | safe state GETs; 2.4.3 reference | LAN/mDNS | R2; warn restart/name change may affect access | P4 | C/U: strict native label validation, explicit enable/disable and rediscovery guidance; E/A open |
| A-09 | Web title and custom logo | P | title probe; no 2.4.3 REST logo API | OLED for WebUI branding | R2 | P4 | C/U: native bounded custom/reset title and exact absent-file default; logo deliberately not claimed; E/A open |
| A-10 | DNS mode and servers | P | safe GET; 2.4.3 reference | network | R3; access/update impact warning | P4 | C/U: native canonical IP-only max-six desired-state/readback and recovery guidance; E/A open |
| A-11 | Wi-Fi manual connect/disconnect | P | safe Wi-Fi info probe; initial AP setup is out of app | optional Wi-Fi module | R3; network handoff confirmation | P4 | C/U: manual SSID only (2.4.3 has no scan API), authenticated UI and local-network permission recovery; AP onboarding intentionally excluded so cleartext remains disabled; A open |
| A-12 | RNDIS/NCM/virtual-device configuration | P | virtual-device state; NCM available after `2.1.5` | host USB | R3; safe-eject/loss-of-access warning | P4 | C/U: native NETWORK/DISK desired-state review, MEDIA read-only state, readback and input recycle; E/A open |
| A-13 | TLS enable/disable | P | enable-only write floor `>=2.2.7` | none | R3; disabling TLS is blocked by this app's security policy | P4 | C/U: confirmed native one-shot enable with HTTPS/certificate recovery guidance; no disable surface; E/A open |
| A-14 | Memory limit and swap size | P | memory `>=2.1.4`, swap `>=2.2.6` | available appliance storage/RAM | R3; performance/storage warning | P4 | C/U: native WebUI-pinned 75 MB memory limit and 0/64/128/256/512 MB swap choices with unknown read-only values; E/A soak open |
| A-15 | Tailscale extension status/control | P | extension state/write floor `>=2.1.6` | appliance internet/account | R3; external account/network side effects | P4 | C/U: typed state gate, native UI for all nine 2.4.3 commands, official HTTPS login-origin validation and one-shot approvals; A open |
| A-16 | WebUI language/theme/toolbar preferences | X | browser-local presentation | none | R0 | - | Replaced by resource-backed static and semantic runtime Android messages, Material theme, adaptive controls and persisted app preferences; translations plus long-string/narrow-window coverage remain explicit UI-06 future work |

## Operator tools and optional extensions

| ID | Capability | State | Firmware/capability gate | Hardware dependency | Risk / confirmation | Phase | Evidence / exit proof |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-01 | NanoKVM system terminal | P | authenticated `/api/vm/terminal`; modern PTY contract in compatibility floor | none | R3; explicit root-equivalent session, no command replay/history persistence | P5 | C/U: cookie-auth non-reconnecting socket, bounded binary PTY, text/resize, 401/disconnect tests, memory-only output and foreground native surface; A open |
| T-02 | Serial terminal and baud/port selection | P | same terminal route; no safe server-side port enumeration | exposed UART/device node | R3; target-port review | P5 | C/U: typed native port/options, exact safe picocom command, memory-only output and one-shot exit; hardware A open |
| T-03 | List and run custom scripts | P | safe list probe; 2.4.3 reference | appliance script store | R3; show exact listed handle/type then confirm | P5 | C/U: bounded latest opaque handles/output, native foreground/background review, exact enum and no replay; A open |
| T-04 | Upload and delete custom scripts | P | T-03 management routes | appliance script store | R3; exact file/name consequence confirmation | P5 | C/U: strict basename/extension, local upload cap, native exact-item confirmation, multipart/DELETE and consume-before-dispatch; A open |
| T-05 | Autostart script list/read/create/update/delete | P | verified first containing tag `>=2.3.1`; 2.4.3 WebUI component is not linked | appliance root at boot | R3; exact content/name and boot risk review | P5 | C/U: strict `.sh`/`.py`, 256 KiB UTF-8 mutable editor, latest-catalog update/delete, reviewed no-replay native surface; A open |
| X-01 | WebRTC video transport | P | route exists throughout `>=2.3.2`; runtime negotiation can still fail | network path and Android WebRTC stack | R1 | P6 | C/U: authenticated signalling, ordered ICE, rendered-frame success, watchdogs, full teardown and fresh WebRTC -> direct H.264 -> MJPEG fallback; performance E/A open |
| X-02 | PicoClaw assistant/control surface | P | version `>=2.4.0`, then probe only after explicit feature entry; runtime separately installed | configured extension/provider | R3; explicit broad device/host-control opt-in | P6 | C/U: explicit-entry-only probe, redacted no-replay gateway, destructive confirmations and conspicuous global HID lock/release UI; E/A open |
| X-03 | Browser on-screen keyboard and cursor cosmetics | X | browser-only presentation | none | R0 | - | Replaced by native IME, modifier accessory, direct touch and trackpad; parity judged by outcomes/accessibility |

## Verification register

The feature row records the required proof; this register points to retained
results for a particular implementation/release. Do not replace `open` with a
check mark without a durable path, target version, date, and result.

| Phase | Protocol/unit (C/U) | Android UI/device (E) | NanoKVM target (A) | Signed candidate (R) |
| --- | --- | --- | --- | --- |
| P0 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint; release matrix open | open: 2.3.2 + 2.4.3 | open |
| P1 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint; API 26/29/31/33/34 open | open: Cube/PCIe 2.4.3 | open |
| P2 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint; physical keyboard/external input open | open: long session + fallback | open |
| P3 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint | open: disposable host/storage | open |
| P4 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint | open: recovery-capable appliance | open |
| P5 | Local C/U checkpoint passed; retained release evidence open | API 37 local checkpoint | open: disposable commands/scripts/UART | open |
| P6 | Local C/U checkpoint passed; retained release evidence open | API 37 functional checkpoint; physical performance open | open: explicitly capable target | open |

For each retained appliance result record: app commit/APK hash, Android version
and device, NanoKVM hardware revision, image/application versions, certificate
mode, test case IDs, outcome, and links to redacted logs/screenshots. Never put
passwords, tokens, clipboard contents, terminal commands containing secrets, or
remote framebuffer captures with sensitive data in the evidence bundle.

The current 2026-07-20 adversarial remediation checkpoint passed 548/548 JVM
tests across 83 suites, 76/76 app instrumentation tests, the video module's 1/1
native WebRTC crash regression, and 1/1 real process-restart test on API 37.
`WebSocketIngressMemoryInstrumentedTest` was not invoked and no Android heap/PSS
result is claimed. Exact local build evidence, including historical checkpoints,
is recorded in `BUILD_VERIFICATION.md`. This remains development evidence: no
production binary is approved or signed, and the real-appliance,
compatibility-floor, physical-device, endurance, destructive, and performance
cases above remain open.
