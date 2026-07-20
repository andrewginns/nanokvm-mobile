# Restrained Material You implementation

NanoKVM Mobile uses Material 3 as a functional system rather than a decorative
layer. The profile and trust flows follow the user's selected system, light, or
dark appearance and can use Android wallpaper-derived colour. The live console
uses a fixed neutral dark palette so wallpaper colour never casts the remote
image or changes the meaning of connection and warning states.

This work was implemented incrementally on the existing unidirectional state,
repository, protocol, video, and input boundaries. It does not replace the
console runtime, move high-rate input through Compose state, retain a secret in
saveable state, or broaden background behavior.

## Implemented phases

| Phase | Result | Modernization controls |
| --- | --- | --- |
| Foundation | Complete Material 3 fallback light/dark schemes, Android 12+ dynamic colour, restrained typography and shapes, and semantic fixed console tokens | Exact dependencies, strict verification metadata, no third-party design SDK, automated contrast checks |
| App shell and platform | Persisted System/Light/Dark and device-colour preferences, edge-to-edge rendering, screen-aware system-bar contrast, and Android 17 local-network permission | DataStore remains authoritative; permission state is ViewModel-owned UDF; Connect is the contextual request point; denial starts no transport; revocation clears pending secrets and disconnects |
| Profiles, credentials, and trust | Compact cards/list items, explicit connection/authentication/trust groups, secure-password affordances, certificate-warning hierarchy, fingerprint copy feedback, and guarded destructive recovery | Password text is never saveable; trust precedes credentials; destructive actions describe consequences and retain a cancel route; static labels and content descriptions are resource-backed |
| Console chrome and input | Neutral console surfaces, compact status and quick actions, bottom-sheet/side/supporting controls, full-width phone pan/zoom/scroll strip, native keyboard accessory, and semantic gesture alternatives | Remote video remains outside decorative surfaces; direct touch, trackpad, IME, pan, zoom, four-direction scroll, HID release, and guarded host controls retain their existing runtime ports |
| Adaptive windows | Decisions derive from the current window size class: compact portrait uses a bottom sheet, compact landscape and medium widths use a side overlay, and expanded widths use a supporting pane | No orientation/aspect restriction; non-secret console/viewport state is saveable; the video keeps a permanent main-pane composition parent while adaptive directives change, so a controls transition does not recreate its decoder Surface or reconnect the stream |
| Evidence | JVM state/layout/contrast tests, Compose semantics and surface-lifecycle instrumentation, release lint, minified release/profile/SBOM gates, and API 37 emulator journeys | Emulator evidence is diagnostic; signed-candidate, representative physical ARM, assistive-technology, and long real-appliance gates remain explicit rather than being inferred from source tests |

## Adaptive behavior

| Current window | Controls | Remote console behavior |
| --- | --- | --- |
| Compact portrait (at least 480dp high) | Modal bottom sheet | Stream stays full size behind transient controls; IME docks the navigation pad above the keyboard |
| Compact landscape (under 480dp high) | Dismissible side overlay | Vertical space is preserved; the labelled scrim and close action dismiss controls |
| Medium width | Dismissible side overlay | Viewpad and scrollpad span the available console width |
| Expanded width (840dp+) | Material supporting pane | Controls can remain alongside the console; the video stays in the scaffold's permanent main-pane slot without a transport or Surface-generation change |

Window decisions use `currentWindowAdaptiveInfo()` rather than device labels or
cached display metrics. Editor and catalogue reading widths are capped, while
the remote viewport consumes the space assigned to the main pane.

## Appearance and accessibility rules

- Dynamic colour applies to local app chrome only. Console canvas, surfaces,
  active controls, warnings, errors, and text use fixed semantic tokens.
- Colour is never the sole status signal: status is paired with text, shape,
  icon, or accessibility state.
- Material controls and custom gesture alternatives provide at least 48dp
  targets. Pan/zoom and four-direction scrolling expose custom accessibility
  actions in addition to gestures.
- Static labels, content descriptions, and affected runtime/feature notices live
  in Android resources behind exhaustive semantic mappings. Translated resource
  sets and complete long-string/narrow-state coverage remain explicit UI-06
  backlog rather than a completed localization claim.
- Non-secret layout context survives recreation. Passwords, staged credentials,
  tokens, and certificate decisions do not enter `rememberSaveable` or
  `SavedStateHandle`.

## Verification boundary

The repository-wide release-like gate is documented in
[`TESTING.md`](TESTING.md). API 37 emulator evidence for this implementation is
recorded in [`BUILD_VERIFICATION.md`](BUILD_VERIFICATION.md). Neither a passing
emulator run nor an unsigned minified APK closes the physical-device,
TalkBack/Switch Access, signed-candidate, or 30-minute H.264/MJPEG appliance
gates in [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).
