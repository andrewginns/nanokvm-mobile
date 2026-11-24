package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalysis
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalyzer
import org.nanokvm.protocol.CometHid
import org.nanokvm.protocol.CommittedTextLineBreakMode
import org.nanokvm.protocol.HidCharacterMapper
import org.nanokvm.protocol.HidKeystroke
import org.nanokvm.protocol.HidModifier
import org.nanokvm.protocol.HidUsage
import org.nanokvm.protocol.MAX_HID_TEXT_EDIT_DELETE
import org.nanokvm.protocol.MAX_HID_TEXT_EDIT_INSERT_CHARS
import org.nanokvm.protocol.KeyboardLayout as WireLayout

/** One ordered writer per control socket. An epoch ends at every release boundary.
 * A stroke and its modifier restoration are atomic with respect to retirement. */
internal class CometInput(
    scope: CoroutineScope,
    private val lock: Any,
    private val send: (ByteString) -> Boolean,
    private val setOutput: (Boolean) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val onProgress: (RemotePasteProgress?) -> Unit,
    private val feedback: (ConsoleMessage.ActionFeedback) -> Unit,
) {
    private val commands = Channel<suspend () -> Unit>(256)
    private val keys = linkedSetOf<String>()
    private val buttons = linkedSetOf<String>()
    private var epoch = 0L
    private var retired = false
    private var pasteToken = 0L
    private var pasteActive = false
    @Volatile var relativeOutput: Boolean? = null

    private val writer = scope.launch {
        for (command in commands) {
            try {
                command()
            } catch (error: IOException) {
                onFailure(error)
            }
        }
    }

    private fun enqueue(task: suspend (Long) -> Unit): Boolean = synchronized(lock) {
        val owner = epoch
        if (retired) return false
        if (commands.trySend { task(owner) }.isSuccess) return true
        // Enqueue failure could lose a key-up. End this socket instead of continuing.
        onFailure(IOException("Input queue full"))
        false
    }

    private fun current(owner: Long) = !retired && epoch == owner
    private inline fun runCurrent(owner: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (!current(owner)) return false
        block()
        true
    }

    private fun emit(bytes: ByteString) {
        if (!send(bytes)) throw IOException("Input socket closed")
    }

    private fun keyNow(code: String, down: Boolean) {
        if (if (down) keys.add(code) else keys.remove(code)) emit(CometHid.key(code, down))
    }

    private fun buttonNow(name: String, down: Boolean) {
        if (if (down) buttons.add(name) else buttons.remove(name)) emit(CometHid.button(name, down))
    }

    fun key(key: RemoteKey, down: Boolean, context: RemoteTextEditContext? = null) {
        enqueue { owner -> runCurrent(owner) {
            if (!down || context?.isValid != false) keyNow(key.webCode(), down)
        } }
    }

    fun tap(key: RemoteKey, context: RemoteTextEditContext) {
        enqueue { owner -> runCurrent(owner) {
            if (context.isValid) {
                keyNow(key.webCode(), true)
                keyNow(key.webCode(), false)
            }
        } }
    }

    fun mouseButton(button: MouseButton, down: Boolean) {
        enqueue { owner -> runCurrent(owner) { buttonNow(button.webName(), down) } }
    }

    fun pointer(relative: Boolean, motion: ByteString? = null, held: Set<MouseButton> = emptySet()) {
        enqueue { owner ->
            if (!synchronized(lock) { current(owner) }) return@enqueue
            if (relativeOutput != relative) {
                setOutput(relative)
                relativeOutput = relative
            }
            runCurrent(owner) {
                held.forEach { buttonNow(it.webName(), true) }
                motion?.let(::emit)
            }
        }
    }

    fun wheel(x: Int, y: Int) {
        enqueue { owner -> runCurrent(owner) { emit(CometHid.delta(true, x, y)) } }
    }

    private fun releaseHeld() {
        buttons.toList().asReversed().forEach { buttonNow(it, false) }
        keys.toList().asReversed().forEach { keyNow(it, false) }
    }

    fun release(retire: Boolean = false) = synchronized(lock) {
        epoch++
        cancelPaste()
        if (!retired) {
            try { releaseHeld() } catch (_: IOException) { /* Socket close clears HID on RM1. */ }
        }
        if (retire) {
            retired = true
            commands.close()
        }
    }

    private fun stroke(stroke: HidKeystroke) {
        val added = stroke.modifiers.map { it.webCode() }.filterNot(keys::contains)
        added.forEach { keyNow(it, true) }
        val code = stroke.usage.webCode()
        keyNow(code, true)
        keyNow(code, false)
        added.asReversed().forEach { keyNow(it, false) }
    }

    suspend fun edit(delete: Int, text: String, layout: KeyboardLayout, context: RemoteTextEditContext): RemoteTextEditResult {
        if (delete !in 0..MAX_HID_TEXT_EDIT_DELETE || text.length > MAX_HID_TEXT_EDIT_INSERT_CHARS) {
            context.invalidate()
            return RemoteTextEditResult.Rejected(RemoteTextEditRejection.InvalidRange)
        }
        val mapped = mapText(text, layout)
        if (mapped.unsupported.isNotEmpty() || text.any { it.isISOControl() && it !in "\r\n" }) {
            context.invalidate()
            return RemoteTextEditResult.Rejected(RemoteTextEditRejection.UnsupportedText)
        }
        val result = CompletableDeferred<RemoteTextEditResult>()
        val queued = enqueue { owner ->
            var sent = 0
            try {
                if (synchronized(lock) { keys.isNotEmpty() }) {
                    context.invalidate()
                    result.complete(RemoteTextEditResult.Rejected(RemoteTextEditRejection.ModifierConflict))
                    return@enqueue
                }
                val count = delete + mapped.keystrokes.size
                for (i in 0 until count) {
                    val accepted = runCurrent(owner) {
                        if (context.isValid) {
                            stroke(if (i < delete) HidKeystroke(HidUsage.BACKSPACE) else mapped.keystrokes[i - delete])
                            sent++
                        }
                    }
                    if (!accepted || sent <= i) break
                }
                if (sent != count) context.invalidate()
                result.complete(when {
                    sent == count && context.isValid -> RemoteTextEditResult.Submitted
                    sent == 0 -> RemoteTextEditResult.Stale
                    else -> RemoteTextEditResult.Unknown
                })
            } finally {
                if (!result.isCompleted) {
                    context.invalidate()
                    result.complete(RemoteTextEditResult.Unknown)
                }
            }
        }
        if (!queued) {
            context.invalidate()
            return RemoteTextEditResult.Stale
        }
        val closed = writer.invokeOnCompletion {
            if (!result.isCompleted) {
                context.invalidate()
                result.complete(RemoteTextEditResult.Unknown)
            }
        }
        return try {
            result.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            context.invalidate()
            throw e
        } finally {
            closed.dispose()
        }
    }

    fun type(text: String, layout: KeyboardLayout, paste: Boolean) {
        val analysis = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(text)
        if (analysis !is ClipboardPayloadAnalysis.Accepted) {
            feedback(ConsoleMessage.ClipboardTextOutsideByteLimit(ClipboardPayloadAnalyzer.MAX_RETAINED_PASTE_BYTES))
            return
        }
        val token = synchronized(lock) {
            if (paste && pasteActive) return
            if (paste) {
                pasteActive = true
                pasteToken++
                onProgress(RemotePasteProgress(pasteToken, 0, analysis.payload.characterCount))
            }
            pasteToken
        }
        enqueue { owner ->
            try {
                val mapping = mapText(analysis.payload.text, layout)
                if (mapping.unsupported.isNotEmpty()) {
                    runCurrent(owner) { feedback(ConsoleMessage.UnsupportedKeyboardCharacters(mapping.unsupported.size)) }
                    return@enqueue
                }
                val total = mapping.keystrokes.size
                for ((i, item) in mapping.keystrokes.withIndex()) {
                    var typed = false
                    runCurrent(owner) {
                        if (!paste || token == pasteToken) {
                            if (paste && i == 0) releaseHeld()
                            stroke(item)
                            typed = true
                            if (paste) onProgress(RemotePasteProgress(token, i + 1, total))
                        }
                    }
                    if (!typed) return@enqueue
                    if (i + 1 < total) delay(12)
                }
                if (paste) runCurrent(owner) { feedback(ConsoleMessage.ClipboardTextTyped) }
            } finally {
                synchronized(lock) { if (paste && token == pasteToken) cancelPaste() }
            }
        }
    }

    fun cancelPaste() = synchronized(lock) {
        if (pasteActive) {
            pasteActive = false
            pasteToken++
            onProgress(null)
        }
    }

    private fun mapText(text: String, layout: KeyboardLayout) = HidCharacterMapper.mapText(
        text.replace("\r\n", "\n"),
        if (layout == KeyboardLayout.Uk) WireLayout.UK else WireLayout.US,
        CommittedTextLineBreakMode.SHIFT_ENTER,
    )
}

private fun RemoteKey.webCode(): String = when (this) {
    RemoteKey.Control -> "ControlLeft"
    RemoteKey.RightControl -> "ControlRight"
    RemoteKey.Shift -> "ShiftLeft"
    RemoteKey.RightShift -> "ShiftRight"
    RemoteKey.Alt -> "AltLeft"
    RemoteKey.RightAlt -> "AltRight"
    RemoteKey.Super -> "MetaLeft"
    RemoteKey.RightSuper -> "MetaRight"
    RemoteKey.LeftBracket -> "BracketLeft"
    RemoteKey.RightBracket -> "BracketRight"
    RemoteKey.Grave -> "Backquote"
    RemoteKey.Apostrophe -> "Quote"
    RemoteKey.NonUsHash -> "Backslash"
    RemoteKey.NonUsBackslash -> "IntlBackslash"
    else -> if (name.length == 1) "Key$name" else name
}

private fun HidModifier.webCode() = when (this) {
    HidModifier.LEFT_SHIFT -> "ShiftLeft"
    HidModifier.RIGHT_SHIFT -> "ShiftRight"
    HidModifier.LEFT_ALT -> "AltLeft"
    HidModifier.RIGHT_ALT -> "AltRight"
    HidModifier.LEFT_CONTROL -> "ControlLeft"
    HidModifier.RIGHT_CONTROL -> "ControlRight"
    HidModifier.LEFT_SUPER -> "MetaLeft"
    HidModifier.RIGHT_SUPER -> "MetaRight"
}

private fun HidUsage.webCode() = when (this) {
    HidUsage.LEFT_BRACKET -> "BracketLeft"
    HidUsage.RIGHT_BRACKET -> "BracketRight"
    HidUsage.NON_US_HASH -> "Backslash"
    HidUsage.NON_US_BACKSLASH -> "IntlBackslash"
    HidUsage.APOSTROPHE -> "Quote"
    HidUsage.GRAVE -> "Backquote"
    else -> when {
        name.length == 1 -> "Key$name"
        name.startsWith("DIGIT_") -> "Digit" + name.last()
        else -> name.lowercase().replaceFirstChar(Char::uppercaseChar)
    }
}

private fun MouseButton.webName() = when (this) {
    MouseButton.Back -> "up"
    MouseButton.Forward -> "down"
    else -> name.lowercase()
}
