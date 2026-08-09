# Google Play asset provenance

Run the repository validator before using these files:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-play-store-assets.ps1
```

## App icon

`play-icon-512.png` is a deterministic 512 x 512, 32-bit PNG rendering of
`play-icon-source.svg`. The SVG preserves the geometry and colours of
`app/src/main/res/drawable/ic_launcher.xml`; it is not an independently designed
logo or third-party mark.

## Feature graphic

`feature-graphic-imagegen-source.png` was finalized with the built-in OpenAI
image generation tool on 2026-08-09. The final edit used the preceding feature
graphic as its composition reference, the real
`screenshot-phone-03-console.png` app capture as the phone-screen reference,
and Sipeed's official
[NanoKVM Full product image](https://wiki.sipeed.com/hardware/assets/NanoKVM/introduce/NanoKVM_3.png)
as the hardware reference. `feature-graphic-1024x500.png` is a centre crop from
1794 x 877 to 1794 x 876 followed by a high-quality resize to the exact Google
Play dimensions. The final file was visually inspected at 1024 x 500 after
resizing.

The original generation prompt was:

```text
Use case: ads-marketing
Asset type: Google Play feature graphic background for NanoKVM Mobile, an independent open-source Android client for a user-owned IP-KVM appliance
Primary request: create a polished, wide technology illustration that communicates secure remote keyboard-video-mouse control without using any brand logo or words
Scene/backdrop: deep near-black navy background (#071016), a clean abstract desktop monitor/console at center-left, subtle network connection paths leading to a compact mobile control surface at right, restrained geometric interface shapes
Style/medium: crisp premium vector-like digital illustration, modern Android/Material sensibility, minimal and trustworthy rather than futuristic or flashy
Composition/framing: extremely wide banner composition approximately 2.05:1, important subjects kept within the central 80% safe area, generous negative space, readable at small size
Lighting/mood: calm, secure, capable
Color palette: dominant #071016 with teal #44D7B6 and pale mint #E8FFF9 accents; very limited secondary blue
Constraints: no text, no letters, no logos, no trademarks, no watermarks, no people, no photorealistic hardware, no padlock cliché, no screenshots, no gradients that muddy the dark background; clean edges suitable for later cropping to exactly 1024x500
Avoid: cyberpunk neon, server-room photography, excessive detail, fake UI text, Sipeed branding, NanoKVM wordmark
```

The final edit prompt was:

```text
Use case: precise-object-edit and compositing
Asset type: Google Play feature graphic, final canvas exactly 1024 x 500 pixels
Input images: Image 1 is the current feature-graphic edit target. Image 2 is the official visual reference for the NanoKVM Full hardware. Image 3 is the actual NanoKVM Mobile console interface already shown in the phone.
Primary request: Replace the flat generic mini-computer box at the far left of Image 1 with a clearly recognizable NanoKVM Full device based on Image 2. It must read as a compact cube-shaped IP-KVM appliance, not a desktop PC or generic router. Keep the actual app interface already visible inside the phone on the far right.
Hardware details: compact black rounded cube enclosure; top-mounted OLED/status display with subtle cyan status lines; small POWER and RESET controls; a front/side face with the distinctive vertically stacked auxiliary USB-C, HDMI, and PC-USB ports; Ethernet port visible on the adjacent face. Use the exact readable product label "NanoKVM" once, restrained and integrated on the device face or directly beneath the device.
Style/medium: match Image 1's polished dark vector-like technical illustration, thin cyan/teal outlines, muted grey materials, consistent lighting and line weight. Translate Image 2's real product shape into that same illustration style rather than pasting a photograph.
Composition/framing: keep the NanoKVM hardware near the lower-left, large enough that the cube form, OLED, and ports are immediately recognizable. Keep the monitor, keyboard, mouse, connection lines and phone in their existing positions. Keep the phone front-on and retain the complete real portrait app view from Image 3 inside its glass.
Text (verbatim): "NanoKVM"
Constraints: Change only the small hardware appliance and the minimum connecting line immediately around it. Preserve the background, monitor, keyboard, mouse, phone shell, actual phone interface, central connection diagram, palette, spacing, and overall composition. Preserve a clean 1024 x 500 landscape composition. No other labels or marketing copy.
Avoid: generic mini PC, router, network switch, rack server, desktop tower, large computer, invented ports, fictional mobile UI, simplified remote-control button grid, altered phone interface, altered monitor, extra logos, badges, watermark, people, photographic mismatch, or illegible invented text.
```

## Screenshots

The four `screenshot-phone-*.png` files were captured on 2026-08-03 from the
v0.3.7 source UI by
`app/src/androidTest/java/org/nanokvm/mobile/StoreScreenshotInstrumentedTest.kt`
and `scripts/capture-play-store-screenshots.ps1`. The controlled target was an
API 37 Google APIs x86_64 emulator with 16 KB pages, a 1080 x 1920 display,
360 dpi, 1.0 font scale, portrait rotation, the fixed dark theme, dynamic colour
disabled, and an en-US system locale. The script captures the complete display,
then performs an unscaled conversion to 24-bit RGB PNG. All four final images
were visually inspected and passed `scripts/verify-play-store-assets.ps1`.

The screenshots render the production Compose screens directly rather than
painting mock controls over an image:

- `screenshot-phone-01-connections.png` uses `ProfilesScreen` with
  `kvm.example.com` and TEST-NET-1 address `192.0.2.44`.
- `screenshot-phone-02-profile-editor.png` uses `ProfileEditorScreen` with a
  reserved example hostname and no password or certificate fingerprint.
- `screenshot-phone-03-console.png` uses `ConsoleScreen` with a test-only video
  surface that receives the generated framebuffer below.
- `screenshot-phone-04-video-settings.png` opens the real Video settings dialog
  over that console.

Production and Play builds still set `FLAG_SECURE`; the capture harness does not
change `MainActivity`, the production manifest, or release behaviour. It hosts
the same real composables in an instrumentation-only `ComponentActivity` with
deterministic harmless state. These files therefore document the current UI but
do not replace final comparison with the signed, Play-delivered candidate.

### Synthetic remote framebuffer

`app/src/androidTest/assets/play-store/remote-workstation-fixture.png` was
created with the built-in OpenAI image-generation tool on 2026-08-03. Its
SHA-256 is
`bd7e6c3cc9a4117f3f7d469056a99ffd3351514a9c2b1b35447b80612468a84d`.
It contains no people, readable prose, credentials, hostnames, IP addresses,
logos, trademarks, personal data, or third-party interface branding.

The generation prompt was:

```text
Use case: ui-mockup
Asset type: harmless 16:9 remote framebuffer content shown inside truthful Google Play screenshots of NanoKVM Mobile
Primary request: create a polished edge-to-edge desktop workspace that clearly reads as a safe demonstration computer being remotely controlled
Scene/backdrop: flat front-on 16:9 desktop screen content only, no monitor bezel or phone frame; dark navy workspace with an abstract code editor on the left and a compact system-health dashboard with charts on the right
Style/medium: crisp modern desktop UI illustration, realistic enough to look like a working test workstation but deliberately generic and rights-clear
Composition/framing: exact wide landscape composition, important content centered with safe margins; balanced visual hierarchy that remains legible when letterboxed inside a portrait phone screenshot
Lighting/mood: calm, capable, secure
Color palette: near-black navy, charcoal panels, restrained teal and mint accents with a little muted blue
Constraints: no people, no logos, no trademarks, no brand names, no personal data, no hostnames, no IP addresses, no credentials, no notifications, no readable prose, no gibberish pseudo-words, no terminal secrets, no watermark; abstract short code-line shapes and unlabeled charts are acceptable
Avoid: cyberpunk neon, red alerts, financial dashboards, photographic room backgrounds, device frames, perspective distortion, rounded outer-canvas corners, text labels, fake OS logos
```
