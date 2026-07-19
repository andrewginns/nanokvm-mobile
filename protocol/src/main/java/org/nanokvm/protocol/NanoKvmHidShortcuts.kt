package org.nanokvm.protocol

import kotlinx.serialization.Serializable

/**
 * A browser `KeyboardEvent.code` understood by the pinned NanoKVM 2.4.3 WebUI key map.
 *
 * Construction is allowlisted: callers can record and write only codes that 2.4.3 can turn into
 * an HID report. Unknown bounded values returned by a newer server remain visible on
 * [NanoKvmSavedHidShortcutKey], but cannot be converted into this type or written back.
 */
class NanoKvmHidKeyCode private constructor(
    val wireValue: String,
) {
    /** Stable fallback label used when storing a locally recorded shortcut. */
    val defaultLabel: String = defaultShortcutLabel(wireValue)

    override fun equals(other: Any?): Boolean =
        other is NanoKvmHidKeyCode && wireValue == other.wireValue

    override fun hashCode(): Int = wireValue.hashCode()

    override fun toString(): String = wireValue

    companion object {
        /** Creates an allowlisted 2.4.3 key code, rejecting unknown or misspelled values. */
        @JvmStatic
        fun known(wireValue: String): NanoKvmHidKeyCode {
            require(shortcutReportKey(wireValue) != null) {
                "NanoKVM 2.4.3 does not recognize HID key code: $wireValue"
            }
            return NanoKvmHidKeyCode(wireValue)
        }

        internal fun fromServerValueOrNull(wireValue: String): NanoKvmHidKeyCode? =
            if (shortcutReportKey(wireValue) == null) null else NanoKvmHidKeyCode(wireValue)
    }
}

/** A local recording ready to be stored by NanoKVM's shortcut API. */
class NanoKvmHidShortcutDraft private constructor(
    keys: List<NanoKvmHidKeyCode>,
) {
    val keys: List<NanoKvmHidKeyCode> = keys.toList()

    companion object {
        /**
         * Records the same maximum of six distinct key codes enforced by the 2.4.3 WebUI.
         * Display labels are derived locally and are never accepted as arbitrary write input.
         */
        @JvmStatic
        fun record(keys: List<NanoKvmHidKeyCode>): NanoKvmHidShortcutDraft {
            require(keys.size in 1..MAX_RECORDED_HID_SHORTCUT_KEYS) {
                "A NanoKVM shortcut must contain 1..$MAX_RECORDED_HID_SHORTCUT_KEYS keys"
            }
            require(keys.distinct().size == keys.size) {
                "A NanoKVM shortcut cannot contain a duplicate key code"
            }
            return NanoKvmHidShortcutDraft(keys)
        }
    }

    internal fun toRequestKeys(): List<HidShortcutKeyPayload> = keys.map { key ->
        HidShortcutKeyPayload(code = key.wireValue, label = key.defaultLabel)
    }
}

/** One bounded key entry read from the appliance's shortcut file. */
class NanoKvmSavedHidShortcutKey internal constructor(
    val code: String,
    val label: String,
    /** Null when [code] was introduced by a newer server and is therefore read-only. */
    val knownCode: NanoKvmHidKeyCode?,
)

/** An opaque saved shortcut handle returned by [NanoKvmApi.savedHidShortcuts]. */
class NanoKvmSavedHidShortcut internal constructor(
    val id: String,
    keys: List<NanoKvmSavedHidShortcutKey>,
) {
    val keys: List<NanoKvmSavedHidShortcutKey> = keys.toList()

    /** True only when the 2.4.3 WebUI run algorithm can safely encode every key. */
    val isRunnable: Boolean
        get() = keys.size <= MAX_RECORDED_HID_SHORTCUT_KEYS && keys.all { it.knownCode != null }
}

/** Immutable, API-instance-bound snapshot used to authorize an exact delete. */
class NanoKvmSavedHidShortcutCatalog internal constructor(
    shortcuts: List<NanoKvmSavedHidShortcut>,
) {
    val shortcuts: List<NanoKvmSavedHidShortcut> = shortcuts.toList()

    fun findById(id: String): NanoKvmSavedHidShortcut? =
        shortcuts.firstOrNull { it.id == id }

    internal fun requireExactMember(shortcut: NanoKvmSavedHidShortcut) {
        require(shortcuts.any { it === shortcut }) {
            "Shortcut must be an exact handle from the supplied NanoKVM shortcut catalog"
        }
    }
}

/** Current persisted leader-key value; unknown future codes are visible but read-only. */
class NanoKvmLeaderKeyState internal constructor(
    /** Empty means disabled, matching the 2.4.3 REST contract. */
    val code: String,
    val knownCode: NanoKvmHidKeyCode?,
) {
    val enabled: Boolean
        get() = code.isNotEmpty()
}

enum class NanoKvmHidShortcutRunRejectionReason {
    TOO_MANY_KEYS,
    UNKNOWN_KEY_CODE,
}

/** Result of one non-replaying WebSocket shortcut dispatch. */
sealed interface NanoKvmHidShortcutRunResult {
    /** Every incremental key-down report and the final release were accepted by OkHttp. */
    data class Completed(val reportsSent: Int) : NanoKvmHidShortcutRunResult

    /** Preflight failed, so no HID report was sent. */
    data class Rejected(
        val reason: NanoKvmHidShortcutRunRejectionReason,
        val unknownCodes: List<String> = emptyList(),
    ) : NanoKvmHidShortcutRunResult

    /** Dispatch stopped and was not replayed; a best-effort all-keys release was attempted. */
    data class ConnectionLost(val reportsSent: Int) : NanoKvmHidShortcutRunResult
}

@Serializable
internal data class HidShortcutKeyPayload(
    val code: String,
    val label: String,
)

@Serializable
internal data class HidShortcutPayload(
    val id: String,
    val keys: List<HidShortcutKeyPayload>,
)

@Serializable
internal data class HidShortcutsResponse(
    // A valid but empty persisted shortcut store may be encoded as a nil Go slice.
    val shortcuts: List<HidShortcutPayload>?,
)

@Serializable
internal data class AddHidShortcutRequest(
    val keys: List<HidShortcutKeyPayload>,
)

@Serializable
internal data class DeleteHidShortcutRequest(
    val id: String,
)

@Serializable
internal data class LeaderKeyResponse(
    val key: String,
)

@Serializable
internal data class SetLeaderKeyRequest(
    val key: String,
)

internal fun HidShortcutsResponse.toValidatedCatalog(): NanoKvmSavedHidShortcutCatalog =
    invalidHidShortcutResponse {
        val normalizedShortcuts = shortcuts.orEmpty()
        require(normalizedShortcuts.size <= MAX_SAVED_HID_SHORTCUTS) {
            "shortcut count exceeds $MAX_SAVED_HID_SHORTCUTS"
        }
        val ids = HashSet<String>(normalizedShortcuts.size)
        val models = normalizedShortcuts.map { shortcut ->
            require(
                shortcut.id.isNotBlank() &&
                    shortcut.id.hasBoundedUtf8Length(MAX_HID_SHORTCUT_ID_UTF8_BYTES) &&
                    shortcut.id.none(Char::isISOControl),
            ) {
                "shortcut id is blank, too long, or contains control text"
            }
            require(ids.add(shortcut.id)) { "shortcut ids are not unique" }
            require(shortcut.keys.isNotEmpty()) { "shortcut has no keys" }
            require(shortcut.keys.size <= MAX_SERVER_HID_SHORTCUT_KEYS) {
                "shortcut key count exceeds $MAX_SERVER_HID_SHORTCUT_KEYS"
            }
            val keys = shortcut.keys.map { key ->
                require(
                    key.code.isNotBlank() &&
                        key.code.hasBoundedUtf8Length(MAX_HID_KEY_CODE_UTF8_BYTES) &&
                        key.code.none(Char::isISOControl),
                ) {
                    "shortcut key code is blank, too long, or contains control text"
                }
                require(
                    key.label.hasBoundedUtf8Length(MAX_HID_KEY_LABEL_UTF8_BYTES) &&
                        key.label.none(Char::isISOControl),
                ) {
                    "shortcut key label is too long or contains control text"
                }
                NanoKvmSavedHidShortcutKey(
                    code = key.code,
                    label = key.label,
                    knownCode = NanoKvmHidKeyCode.fromServerValueOrNull(key.code),
                )
            }
            NanoKvmSavedHidShortcut(shortcut.id, keys)
        }
        NanoKvmSavedHidShortcutCatalog(models)
    }

internal fun LeaderKeyResponse.toValidatedState(): NanoKvmLeaderKeyState =
    invalidHidShortcutResponse {
        require(
            key.hasBoundedUtf8Length(MAX_HID_KEY_CODE_UTF8_BYTES) &&
                key.none(Char::isISOControl),
        ) {
            "leader key is too long or contains control text"
        }
        NanoKvmLeaderKeyState(
            code = key,
            knownCode = key.takeIf(String::isNotEmpty)?.let(NanoKvmHidKeyCode::fromServerValueOrNull),
        )
    }

internal sealed interface HidShortcutRunPlan {
    data class Reports(val reports: List<HidKeyboardReport>) : HidShortcutRunPlan
    data class Rejected(val result: NanoKvmHidShortcutRunResult.Rejected) : HidShortcutRunPlan
}

/** Reproduces 2.4.3's incremental `KeyboardReport.keyDown()` loop plus one final reset. */
internal fun NanoKvmSavedHidShortcut.toRunPlan(): HidShortcutRunPlan {
    if (keys.size > MAX_RECORDED_HID_SHORTCUT_KEYS) {
        return HidShortcutRunPlan.Rejected(
            NanoKvmHidShortcutRunResult.Rejected(
                NanoKvmHidShortcutRunRejectionReason.TOO_MANY_KEYS,
            ),
        )
    }
    val unknown = keys.mapNotNull { key -> key.code.takeIf { key.knownCode == null } }
    if (unknown.isNotEmpty()) {
        return HidShortcutRunPlan.Rejected(
            NanoKvmHidShortcutRunResult.Rejected(
                reason = NanoKvmHidShortcutRunRejectionReason.UNKNOWN_KEY_CODE,
                unknownCodes = unknown.distinct(),
            ),
        )
    }

    var modifierMask = 0
    val pressedUsages = LinkedHashMap<String, Int>()
    val reports = ArrayList<HidKeyboardReport>(keys.size + 1)
    keys.forEach { key ->
        when (val reportKey = requireNotNull(shortcutReportKey(key.code))) {
            is ShortcutReportKey.Modifier -> modifierMask = modifierMask or reportKey.bit
            is ShortcutReportKey.Usage -> pressedUsages[key.code] = reportKey.code
        }
        val bytes = ByteArray(8)
        bytes[0] = modifierMask.toByte()
        pressedUsages.values.forEachIndexed { index, code -> bytes[index + 2] = code.toByte() }
        reports += HidKeyboardReport.fromBytes(bytes)
    }
    reports += HidKeyboardReport.released()
    return HidShortcutRunPlan.Reports(reports)
}

private sealed interface ShortcutReportKey {
    data class Modifier(val bit: Int) : ShortcutReportKey
    data class Usage(val code: Int) : ShortcutReportKey
}

private fun shortcutReportKey(wireValue: String): ShortcutReportKey? =
    KNOWN_SHORTCUT_REPORT_KEYS[wireValue]

/** Exact union of `ModifierMap` and `KeycodeMap` in the pinned 2.4.3 WebUI. */
private val KNOWN_SHORTCUT_REPORT_KEYS: Map<String, ShortcutReportKey> = buildMap {
    putModifier("ControlLeft", 0x01)
    putModifier("ShiftLeft", 0x02)
    putModifier("AltLeft", 0x04)
    putModifier("MetaLeft", 0x08)
    putModifier("ControlRight", 0x10)
    putModifier("ShiftRight", 0x20)
    putModifier("AltRight", 0x40)
    putModifier("MetaRight", 0x80)

    ('A'..'Z').forEachIndexed { index, letter -> putUsage("Key$letter", 0x04 + index) }
    (1..9).forEach { digit -> putUsage("Digit$digit", 0x1d + digit) }
    putUsage("Digit0", 0x27)

    putUsages(
        "Enter" to 0x28,
        "Escape" to 0x29,
        "Backspace" to 0x2a,
        "Tab" to 0x2b,
        "Space" to 0x2c,
        "Minus" to 0x2d,
        "Equal" to 0x2e,
        "BracketLeft" to 0x2f,
        "BracketRight" to 0x30,
        "Backslash" to 0x31,
        "Semicolon" to 0x33,
        "Quote" to 0x34,
        "Backquote" to 0x35,
        "Comma" to 0x36,
        "Period" to 0x37,
        "Slash" to 0x38,
        "CapsLock" to 0x39,
    )
    (1..12).forEach { number -> putUsage("F$number", 0x39 + number) }
    putUsages(
        "PrintScreen" to 0x46,
        "ScrollLock" to 0x47,
        "Pause" to 0x48,
        "Insert" to 0x49,
        "Home" to 0x4a,
        "PageUp" to 0x4b,
        "Delete" to 0x4c,
        "End" to 0x4d,
        "PageDown" to 0x4e,
        "ArrowRight" to 0x4f,
        "ArrowLeft" to 0x50,
        "ArrowDown" to 0x51,
        "ArrowUp" to 0x52,
        "NumLock" to 0x53,
        "NumpadDivide" to 0x54,
        "NumpadMultiply" to 0x55,
        "NumpadSubtract" to 0x56,
        "NumpadAdd" to 0x57,
        "NumpadEnter" to 0x58,
    )
    (1..9).forEach { digit -> putUsage("Numpad$digit", 0x58 + digit) }
    putUsages(
        "Numpad0" to 0x62,
        "NumpadDecimal" to 0x63,
        "IntlBackslash" to 0x64,
        "ContextMenu" to 0x65,
        "Power" to 0x66,
        "NumpadEqual" to 0x67,
    )
    (13..24).forEach { number -> putUsage("F$number", 0x68 + (number - 13)) }
    putUsages(
        "Execute" to 0x74,
        "Help" to 0x75,
        "Props" to 0x76,
        "Select" to 0x77,
        "Stop" to 0x78,
        "Again" to 0x79,
        "Undo" to 0x7a,
        "Cut" to 0x7b,
        "Copy" to 0x7c,
        "Paste" to 0x7d,
        "Find" to 0x7e,
        "AudioVolumeMute" to 0x7f,
        "AudioVolumeUp" to 0x80,
        "AudioVolumeDown" to 0x81,
        "VolumeMute" to 0x7f,
        "VolumeUp" to 0x80,
        "VolumeDown" to 0x81,
        "LockingCapsLock" to 0x82,
        "LockingNumLock" to 0x83,
        "LockingScrollLock" to 0x84,
        "NumpadComma" to 0x85,
        "NumpadEqual2" to 0x86,
        "IntlRo" to 0x87,
        "KanaMode" to 0x88,
        "IntlYen" to 0x89,
        "Convert" to 0x8a,
        "NonConvert" to 0x8b,
        "International6" to 0x8c,
        "International7" to 0x8d,
        "International8" to 0x8e,
        "International9" to 0x8f,
    )
    (1..9).forEach { number -> putUsage("Lang$number", 0x8f + number) }
    putUsages(
        "IntlHash" to 0x32,
        "NumpadParenLeft" to 0xb6,
        "NumpadParenRight" to 0xb7,
        "NumpadBackspace" to 0xbb,
        "NumpadMemoryStore" to 0xd0,
        "NumpadMemoryRecall" to 0xd1,
        "NumpadMemoryClear" to 0xd2,
        "NumpadMemoryAdd" to 0xd3,
        "NumpadMemorySubtract" to 0xd4,
        "NumpadClear" to 0xd8,
        "NumpadClearEntry" to 0xd9,
        "BrowserSearch" to 0xf0,
        "BrowserHome" to 0xf1,
        "BrowserBack" to 0xf2,
        "BrowserForward" to 0xf3,
        "BrowserStop" to 0xf4,
        "BrowserRefresh" to 0xf5,
        "BrowserFavorites" to 0xf6,
        "MediaPlayPause" to 0xe8,
        "MediaStop" to 0xe9,
        "MediaTrackPrevious" to 0xea,
        "MediaTrackNext" to 0xeb,
        "Eject" to 0xec,
        "MediaSelect" to 0xed,
        "LaunchMail" to 0xee,
        "LaunchApp1" to 0xef,
        "LaunchApp2" to 0xf0,
        "Sleep" to 0xf8,
        "Wake" to 0xf9,
        "MediaRewind" to 0xfa,
        "MediaFastForward" to 0xfb,
    )
}

internal val knownHidShortcutKeyCodeCount: Int
    get() = KNOWN_SHORTCUT_REPORT_KEYS.size

private fun MutableMap<String, ShortcutReportKey>.putModifier(code: String, bit: Int) {
    put(code, ShortcutReportKey.Modifier(bit))
}

private fun MutableMap<String, ShortcutReportKey>.putUsage(code: String, usage: Int) {
    put(code, ShortcutReportKey.Usage(usage))
}

private fun MutableMap<String, ShortcutReportKey>.putUsages(
    vararg values: Pair<String, Int>,
) {
    values.forEach { (code, usage) -> putUsage(code, usage) }
}

private fun defaultShortcutLabel(code: String): String = when {
    code.startsWith("Key") && code.length == 4 -> code.removePrefix("Key")
    code.startsWith("Digit") && code.length == 6 -> code.removePrefix("Digit")
    code.matches(Regex("F(?:[1-9]|1[0-9]|2[0-4])")) -> code
    code.startsWith("Numpad") -> "Num${code.removePrefix("Numpad")}"
    else -> DEFAULT_SHORTCUT_LABELS[code] ?: code
}

private val DEFAULT_SHORTCUT_LABELS = mapOf(
    "ControlLeft" to "Ctrl",
    "ControlRight" to "Ctrl",
    "ShiftLeft" to "Shift",
    "ShiftRight" to "Shift",
    "AltLeft" to "Alt",
    "AltRight" to "Alt",
    "MetaLeft" to "Win",
    "MetaRight" to "Win",
    "Space" to "Space",
    "Backspace" to "⌫",
    "Enter" to "↵",
    "Tab" to "Tab",
    "CapsLock" to "Caps",
    "Escape" to "Esc",
    "ArrowUp" to "↑",
    "ArrowDown" to "↓",
    "ArrowLeft" to "←",
    "ArrowRight" to "→",
    "Delete" to "Del",
    "Insert" to "Ins",
    "Home" to "Home",
    "End" to "End",
    "PageUp" to "PgUp",
    "PageDown" to "PgDn",
    "Minus" to "-",
    "Equal" to "=",
    "BracketLeft" to "[",
    "BracketRight" to "]",
    "Backslash" to "\\",
    "Semicolon" to ";",
    "Quote" to "'",
    "Backquote" to "`",
    "Comma" to ",",
    "Period" to ".",
    "Slash" to "/",
)

private inline fun <Result> invalidHidShortcutResponse(block: () -> Result): Result = try {
    block()
} catch (error: IllegalArgumentException) {
    throw InvalidApiResponseException("NanoKVM returned invalid HID shortcut response data", error)
}

const val MAX_RECORDED_HID_SHORTCUT_KEYS: Int = 6
internal const val MAX_SAVED_HID_SHORTCUTS: Int = 512
internal const val MAX_SERVER_HID_SHORTCUT_KEYS: Int = 64
internal const val MAX_HID_SHORTCUT_ID_UTF8_BYTES: Int = 128
internal const val MAX_HID_KEY_CODE_UTF8_BYTES: Int = 64
internal const val MAX_HID_KEY_LABEL_UTF8_BYTES: Int = 128
