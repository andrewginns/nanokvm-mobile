package org.nanokvm.mobile.runtime

import android.view.KeyEvent
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.nanokvm.protocol.MAX_AUTOSTART_CONTENT_BYTES
import org.nanokvm.protocol.NanoKvmHidKeyCode

internal data class NanoKvmRecordedHidKey(
    val wireCode: String,
    val displayLabel: String,
)

internal enum class NanoKvmPhysicalKeyRecordResult {
    ADDED,
    DUPLICATE,
    LIMIT_REACHED,
    UNSUPPORTED,
    REPEAT_IGNORED,
}

/**
 * Ephemeral recorder for the common physical keys Android reports reliably.
 *
 * It records key-down only, ignores repeats, derives server labels locally, and caps the reviewed
 * chord at the pinned WebUI maximum of six distinct keys. Nothing here writes to the appliance.
 */
internal class NanoKvmPhysicalShortcutRecorder {
    private val lock = Any()
    private val recorded = mutableListOf<NanoKvmRecordedHidKey>()

    val keys: List<NanoKvmRecordedHidKey>
        get() = synchronized(lock) { recorded.toList() }

    fun recordAndroidKeyCode(
        androidKeyCode: Int,
        repeatCount: Int = 0,
    ): NanoKvmPhysicalKeyRecordResult {
        if (repeatCount != 0) return NanoKvmPhysicalKeyRecordResult.REPEAT_IGNORED
        val wireCode = commonAndroidKeyCodeToWireCode(androidKeyCode)
            ?: return NanoKvmPhysicalKeyRecordResult.UNSUPPORTED
        return recordWireCode(wireCode)
    }

    internal fun recordWireCode(wireCode: String): NanoKvmPhysicalKeyRecordResult {
        val key = runCatching { NanoKvmHidKeyCode.known(wireCode) }.getOrNull()
            ?: return NanoKvmPhysicalKeyRecordResult.UNSUPPORTED
        return synchronized(lock) {
            when {
                recorded.any { it.wireCode == wireCode } ->
                    NanoKvmPhysicalKeyRecordResult.DUPLICATE
                recorded.size >= MAX_RECORDED_KEYS ->
                    NanoKvmPhysicalKeyRecordResult.LIMIT_REACHED
                else -> {
                    recorded += NanoKvmRecordedHidKey(wireCode, key.defaultLabel)
                    NanoKvmPhysicalKeyRecordResult.ADDED
                }
            }
        }
    }

    fun remove(wireCode: String): Boolean = synchronized(lock) {
        recorded.removeAll { it.wireCode == wireCode }
    }

    fun clear() = synchronized(lock) { recorded.clear() }

    override fun toString(): String = synchronized(lock) {
        "NanoKvmPhysicalShortcutRecorder(keys=${recorded.size}, values=<redacted>)"
    }

    private companion object {
        const val MAX_RECORDED_KEYS = 6
    }
}

/**
 * Mutable, closeable root-equivalent editor/import buffer.
 *
 * Compose necessarily uses transient immutable strings while editing; this owner keeps no String
 * field. Imported arrays are cleared immediately after copying. Close or write consumption clears
 * the retained bytes, and diagnostics expose only byte count.
 */
internal class NanoKvmAutostartEditorBuffer private constructor(
    private var ownedBytes: ByteArray,
) : Closeable {
    private var closed = false

    val byteCount: Int
        @Synchronized get() = ownedBytes.size

    @Synchronized
    fun copyText(): String {
        check(!closed) { "Autostart editor is closed" }
        return ownedBytes.decodeStrictUtf8()
    }

    @Synchronized
    fun replaceText(value: String) {
        check(!closed) { "Autostart editor is closed" }
        val replacement = value.toValidatedAutostartBytes(allowEmpty = true)
        ownedBytes.fill(0)
        ownedBytes = replacement
    }

    @Synchronized
    internal fun consumeForWrite(): NanoKvmAutomationPortAutostartWrite {
        check(!closed) { "Autostart editor is closed" }
        require(ownedBytes.isNotEmpty()) { "Autostart write content must not be empty" }
        closed = true
        val transfer = ownedBytes
        ownedBytes = ByteArray(0)
        return try {
            NanoKvmAutomationPortAutostartWrite.takeOwnership(transfer)
        } finally {
            transfer.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedBytes.fill(0)
        ownedBytes = ByteArray(0)
    }

    @Synchronized
    override fun toString(): String =
        "NanoKvmAutostartEditorBuffer(byteCount=${ownedBytes.size}, content=<redacted>, closed=$closed)"

    companion object {
        fun empty(): NanoKvmAutostartEditorBuffer = NanoKvmAutostartEditorBuffer(ByteArray(0))

        fun fromText(value: String): NanoKvmAutostartEditorBuffer =
            NanoKvmAutostartEditorBuffer(value.toValidatedAutostartBytes(allowEmpty = true))

        /** Takes a private copy and clears [bytes], including when validation fails. */
        fun importOwned(bytes: ByteArray): NanoKvmAutostartEditorBuffer {
            if (bytes.size > MAX_AUTOSTART_CONTENT_BYTES) {
                bytes.fill(0)
                throw IllegalArgumentException("Autostart import exceeds the editor limit")
            }
            val retained = try {
                bytes.copyOf()
            } finally {
                bytes.fill(0)
            }
            try {
                retained.validateAutostartEditorBytes(allowEmpty = true)
                return NanoKvmAutostartEditorBuffer(retained)
            } catch (error: IllegalArgumentException) {
                retained.fill(0)
                throw error
            }
        }
    }
}

internal fun isSafeAutostartEditorText(value: String): Boolean =
    value.safeScalarTextUtf8SizeAtMost(MAX_AUTOSTART_CONTENT_BYTES) != null

internal fun isSafeAutostartBasename(value: String): Boolean =
    value.utf8SizeAtMost(255) != null &&
        SAFE_AUTOSTART_BASENAME.matches(value) &&
        ".." !in value

private fun String.toValidatedAutostartBytes(allowEmpty: Boolean): ByteArray {
    require(hasOnlyUnicodeScalarValues()) { "Autostart text contains an unpaired surrogate" }
    val bytes = encodeToByteArray()
    try {
        bytes.validateAutostartEditorBytes(allowEmpty)
        return bytes
    } catch (error: IllegalArgumentException) {
        bytes.fill(0)
        throw error
    }
}

private fun ByteArray.validateAutostartEditorBytes(allowEmpty: Boolean) {
    require(allowEmpty || isNotEmpty()) { "Autostart content must not be empty" }
    require(size <= MAX_AUTOSTART_CONTENT_BYTES) { "Autostart content exceeds the editor limit" }
    val decoded = decodeStrictUtf8()
    require(decoded.hasOnlyUnicodeScalarValues()) { "Autostart content is not Unicode scalar text" }
    require(decoded.all { value ->
        value == '\t' || value == '\n' || value == '\r' ||
            (!value.isISOControl() && value != '\u007f')
    }) {
        "Autostart content contains unsupported control text"
    }
}

private fun ByteArray.decodeStrictUtf8(): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (error: Exception) {
    throw IllegalArgumentException("Autostart content must be valid UTF-8")
}

private fun String.hasOnlyUnicodeScalarValues(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            this[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun commonAndroidKeyCodeToWireCode(keyCode: Int): String? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
        "Key${'A' + (keyCode - KeyEvent.KEYCODE_A)}"
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
        "Digit${keyCode - KeyEvent.KEYCODE_0}"
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
        "Numpad${keyCode - KeyEvent.KEYCODE_NUMPAD_0}"
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
        "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
    KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
    KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
    KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
    KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
    KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
    KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
    KeyEvent.KEYCODE_META_LEFT -> "MetaLeft"
    KeyEvent.KEYCODE_META_RIGHT -> "MetaRight"
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> "Escape"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock"
    KeyEvent.KEYCODE_INSERT -> "Insert"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
    KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    KeyEvent.KEYCODE_MINUS -> "Minus"
    KeyEvent.KEYCODE_EQUALS -> "Equal"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
    KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
    KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
    KeyEvent.KEYCODE_APOSTROPHE -> "Quote"
    KeyEvent.KEYCODE_GRAVE -> "Backquote"
    KeyEvent.KEYCODE_COMMA -> "Comma"
    KeyEvent.KEYCODE_PERIOD -> "Period"
    KeyEvent.KEYCODE_SLASH -> "Slash"
    else -> null
}

private val SAFE_AUTOSTART_BASENAME = Regex(
    pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:sh|py)",
    option = RegexOption.IGNORE_CASE,
)
