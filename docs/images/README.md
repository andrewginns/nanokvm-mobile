# Image provenance

The public README uses only synthetic fixtures, reserved example addresses, and
captures of NanoKVM Mobile's real Compose UI. No private appliance or remote
computer data is included.

## Overview graphic

`readme/nanokvm-mobile-overview.png` is the 1024 x 500 README graphic. Its final
bitmap source is retained as `source/nanokvm-mobile-overview-source.png`.

The illustration was finalized with OpenAI image generation on 9 August 2026.
The final edit used:

- the preceding synthetic overview illustration for its composition;
- `readme/console.png`, a real app capture, for the phone screen; and
- Sipeed's official
  [NanoKVM Full product image](https://wiki.sipeed.com/hardware/assets/NanoKVM/introduce/NanoKVM_3.png)
  as a hardware reference.

The device was redrawn in the illustration's dark vector-like style rather than
copying the product photograph. The restrained `NanoKVM` label identifies the
compatible hardware; this project is independent of Sipeed. The final canvas is
a centre crop followed by a high-quality resize from the retained source.

## Interface screenshots

The four `readme/*.png` phone screenshots were captured from the production
Compose screens on 3 August 2026 using a controlled API 37 emulator. They use
the reserved hostname `kvm.example.com`, TEST-NET address `192.0.2.44`, and no
password or certificate fingerprint.

The console screenshots show a rights-clear synthetic framebuffer: a generic
dark code workspace and system-health dashboard containing no people, readable
prose, credentials, private endpoints, logos, trademarks, personal data, or
third-party interface branding. The capture path used instrumentation-only
state and did not weaken the production app's secure-screen behavior.
