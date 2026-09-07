# Android keyboard correction plan

Updated 7 September 2026 after the user confirmed that no Samsung phone is available. The current work uses the emulator's installed native keyboard. Samsung-specific confirmation, Samsung APK inspection and physical-host validation are deferred; they do not block fixing demonstrated app defects.

## Outcome and scope

Preserve live typing, suggestions and autocorrect. Completed corrections and undo should produce exactly one intended word; surrounding text and intentional repeated words must remain intact. Keep the existing physical-key mapping and ordered HID queue. Avoid spelling heuristics, timing-based deduplication, a custom keyboard or a remote-document model.

The original report is Samsung Keyboard on One UI 8.5. Stock emulator results establish app/framework correctness, not proof that every Samsung sequence is fixed. A Galaxy emulator skin does not include One UI ([Samsung documentation](https://developer.samsung.com/galaxy-emulator-skin/guide.html)).

## 1. Reproduce against actual app code — baseline captured

Use existing Android instrumentation dependencies to render the production ConsoleKeyboard with a recording remote-text sink. Compare completed edit sequences with a normal Android EditText. The recording sink models an ASCII editor; it is not a substitute for the production HID transport tests.

The fresh API 37 emulator reproduced 9 failures in 11 baseline tests:

- Code-point deletion followed by correction produced `teh the ` instead of `the `.
- Finishing composition dropped `hello` before a space.
- Replacing selected recent text appended `catdog`.
- Zero deletion emitted a Backspace.
- Committed surrounding text was unavailable to the keyboard.
- Correction undo failed.
- Closed and hidden keyboard connections still accepted late text.
- Suggestions were disabled by the editor flags.

The existing composition-to-correction and ordinary UTF-16 replacement controls passed. Preserve these while repairing the failures. Baseline logs and engineering APKs are private, ignored artifacts under `.scratch/native-ime-qa/baseline/`.

## 2. Use Android's editor and repair the transport boundary — implemented

Replace append-and-clear InputConnection logic with a standard EditText and a guarded InputConnectionWrapper. Let Android maintain Editable, selection, composition and query semantics. Translate completed edits of a bounded recent suffix into ordered Backspace/insertion operations. Cover legacy and modern TextAttribute/replaceText overloads; advisory correction notifications must not duplicate an edit.

Reuse the existing keyboard queue. Submit logical edits in callback order and invalidate dependent queued edits if a predecessor is rejected, stale or uncertain. Preflight every complete known replacement before deletion, rejecting unsupported characters rather than sending a supported subset. Never replay uncertain input. Guard Enter/Tab/navigation behind the preceding edit, so rejected composition cannot accidentally submit existing host text.

Keep raw Backspace and forward Delete usable outside the retained suffix. Process unbatched deletion as an ordinary deletion; a later unsupported insertion cannot retroactively make that deletion atomic. Retain recoverable text and provide explicit feedback instead of guessing future callbacks.

Invalidate correction context synchronously before pointer/caret/destination changes and when hiding or replacing the connection. Preserve pending text for explicit recovery; do not send it to a newly focused target. Keep local text and queued operations bounded. Multi-character composition with an armed shortcut modifier must not become multiple shortcuts.

The implementation trims delivered context to a 256-character suffix when editing is at its end, and bounds active local text to 4096 characters and pending edits to 32. Recovery text is scoped to the foreground session. Ordinary single-character deletion beyond known context remains available; bulk deletion of unknown host text is rejected. Raw modifier workflows remain separate: Shift-only whole commits preserve their existing behavior, while Ctrl/Alt/Super cannot turn a composed word into multiple shortcuts. Normal corrections, including the keyboard's own Shift/capitalization, use the editable bridge.

## 3. Exercise the emulator's real on-screen keyboard

Use the fresh, workspace-contained API 37 AVD and record its installed/default IME package/version. The current image provides Gboard. Drive on-screen keys using coordinates from the UI tree; ADB text injection does not test autocorrect.

Use only synthetic phrases with the recording sink displayed in a test-only UI. Check space-triggered correction, tapping suggestions, undo, intentional repeated words, punctuation, rapid typing and keyboard reopen. Compare with a standard Android field when needed. Convert observed callback differences into deterministic tests. No diagnostic activity or trace collector belongs in a release build.

## 4. Verify and record practical limits

- Run actual-input-connection regressions, including batch edits, modern overloads, stale connections, forward/excess deletion and rejected-text submit prevention.
- Run production protocol/backend tests for preflight, ordering, modifiers, stale generations and injected failures. A fake sink alone does not establish these properties.
- Exercise the native Gboard UI on the emulator and retain synthetic evidence.
- Run the existing relevant Android suite and strict repository build gate without changing dependency verification metadata.
- Add a focused older-API pass when an appropriate installed image is available; record unavailable coverage instead of requiring Samsung access or introducing a new test framework.

Video/HID provides no host document/caret readback. Host-side formatting or independent input can change context beyond the app's knowledge. Report separately what passed against the recording sink, the actual transport implementation and the native emulator keyboard. Samsung One UI 8.5 and a real NanoKVM/host remain follow-up validation when available.

## Completion record

Native Gboard 17.2.2.895242737-preload-x86_64 was exercised on the API 37 emulator with Auto-correction enabled, using accessibility-derived key bounds. The recording sink and actual editor agreed at each captured boundary:

- Tapping the suggested `the` after `teh` produced one `the `.
- Typing `wrold` and Space produced `world `, without retaining the original word.
- Backspace removed the trailing space; choosing Gboard's offered original `wrold` then restored one `wrold `. Backspace alone is not claimed to perform that reversal.
- An earlier corrected phrase retained its preceding `the `, producing `the world `.

Gboard did not automatically replace `teh` on Space in this environment; that observation is not counted as an automatic-correction pass. The successful automatic case was `wrold`. The microphone control was visible; audio transcription was not exercised. Evidence is private under `.scratch/native-ime-qa/final/` and `fixed-pass5/`; the reproducible test fixture is documented in [TESTING.md](TESTING.md#native-keyboard-corrections).

The emulator-based implementation and verification are complete:

| Check | Final result |
| --- | --- |
| JVM tests | 618 passed: 391 app, 191 protocol, 36 video. |
| API 37 / Android 17, 16 KiB pages | All 72 Android tests passed: 25 native-editor regressions, 41 console flows and 6 viewport accessibility cases. |
| API 36 / Android 16, 4 KiB pages | All 25 native-editor regressions passed on a separate fresh emulator using the same APKs. |
| Native Gboard | Real on-screen suggestion acceptance, automatic correction and original-word restoration matched the recording sink, as described above. |
| Strict repository gate | Passed JVM tests, release lint, release APK/AAB and benchmark builds, packaged-profile verification and reproducible SBOM metadata checks. Only the permitted dependency-version advisories remain in lint. |

The API 36 first-boot attempt was interrupted during startup before any test ran; the fresh image also logged Google Play services startup ANRs. After startup settled, the same APKs completed all 25 cases. The initial logs and successful retry are retained under `.scratch/native-ime-qa/api36/evidence/`. Final API 37 evidence is under `.scratch/native-ime-qa/final/`; the strict gate log is `.scratch/ime-fix/final-strict-gate.log`.

Samsung One UI 8.5, the minimum supported API 26, voice transcription and a physical NanoKVM/host were not validated. These remain explicit follow-ups, rather than prerequisites for this completed emulator-based repair. Host-side text/caret transformations still cannot be read back through HID/video.

This task does not publish an APK or replace an existing signed installation. Engineering builds were used only on disposable emulators; future APK handoff must follow AGENTS.md and the repository signing/upgrade checks.

Touch, forms, PicoClaw and boot-script features remain outside this focused keyboard repair.
