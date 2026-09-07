package org.nanokvm.mobile.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.PreviewableHandwritingGesture
import android.widget.EditText
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.RemoteTextEditContext
import org.nanokvm.mobile.runtime.RemoteTextEditResult
import java.util.concurrent.Executor
import java.util.function.IntConsumer

/** Android owns the editable; this view translates finished edits of its recent suffix into HID. */
internal class NativeImeView(context: Context) : EditText(context) {
    var input: RemoteInputSink? = null
    var layout: KeyboardLayout = KeyboardLayout.Us
    var onRemoteKey: (RemoteKey, Boolean) -> Unit = { _, _ -> }
    var onSoftKeyConsumed: () -> Unit = {}
    var onShortcutText: (String) -> Boolean = { false }
    var onRecoverText: (String) -> Unit = {}
    var interceptLocalEscape = false
    var onLocalEscape: () -> Unit = {}

    private val localEscape = LocalEscapeKeyInterceptor()
    private val inputMethod by lazy { context.getSystemService(InputMethodManager::class.java) }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var keepImeFocus = false
    private var connection: KeyboardConnection? = null
    private var editContext = RemoteTextEditContext()
    // Predicted text is guarded by editContext. A failed operation invalidates every dependent edit.
    private var submittedText = ""
    private var pendingEdits = 0
    private val restoreIme = Runnable { restoreImeFocus() }
    private var binding: List<Any?>? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isCursorVisible = false
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setTextColor(Color.TRANSPARENT)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= 33) isAutoHandwritingEnabled = false
        onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && keepImeFocus) scheduleImeRestore()
        }
    }

    fun bind(input: RemoteInputSink, layout: KeyboardLayout, releaseGeneration: Long, contextKey: Any?) {
        val next = listOf(input, layout, releaseGeneration, contextKey)
        if (binding != null && binding != next) {
            clearCorrectionContext(recover = binding?.get(3) == contextKey)
        }
        binding = next
        this.input = input
        this.layout = layout
    }

    fun setImeActive(active: Boolean) {
        if (keepImeFocus == active) return
        keepImeFocus = active
        removeCallbacks(restoreIme)
        if (active) {
            scheduleImeRestore()
        } else {
            clearCorrectionContext(restart = false)
            inputMethod.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
        }
    }

    /** A destination/caret change cancels pending work before the external action is dispatched. */
    fun clearCorrectionContext(restart: Boolean = true, recover: Boolean = true) {
        val hadContext = connection != null || text.isNotEmpty() || pendingEdits != 0
        if (recover && (pendingEdits != 0 || text.toString() != submittedText)) recoverText()
        editContext.invalidate()
        editContext = RemoteTextEditContext()
        connection?.retire()
        connection = null
        pendingEdits = 0
        submittedText = ""
        text.clear()
        setSelection(0)
        if (hadContext && restart && keepImeFocus && isAttachedToWindow) inputMethod.restartInput(this)
    }

    /** Toolbar keys must commit pending composition before their HID pair enters the same queue. */
    fun sendToolbarKey(key: RemoteKey) {
        val token = editContext
        connection?.finishComposingText()
        if (!token.isValid || editContext !== token) return
        sendSoftKey(key)
    }

    private fun recoverText() {
        text.toString().takeIf(String::isNotEmpty)?.let(onRecoverText)
    }

    private fun scheduleImeRestore() {
        removeCallbacks(restoreIme)
        post(restoreIme)
        postDelayed(restoreIme, 250L)
    }

    private fun restoreImeFocus() {
        if (!keepImeFocus || !isAttachedToWindow) return
        if (!hasFocus()) requestFocus()
        inputMethod.showSoftInput(this, 0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromWindow() {
        setImeActive(false)
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!keepImeFocus) return null
        connection?.retire()
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        if (Build.VERSION.SDK_INT >= 34) {
            outAttrs.setSupportedHandwritingGestures(emptyList())
            outAttrs.setSupportedHandwritingGesturePreviews(emptySet())
        }
        return KeyboardConnection(target).also { connection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (localEscape.onKeyEvent(event, interceptLocalEscape, onLocalEscape)) return true
        val key = remoteKeyForAndroidKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            val token = editContext
            connection?.finishComposingText()
            if (!token.isValid || editContext !== token) return true
            input?.key(key, true, editContext)
            retireEditor()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (localEscape.onKeyEvent(event, interceptLocalEscape, onLocalEscape)) return true
        val key = remoteKeyForAndroidKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        onRemoteKey(key, false)
        return true
    }

    private fun sendSoftKey(key: RemoteKey, retire: Boolean = true) {
        input?.tapKey(key, editContext)
        onSoftKeyConsumed()
        if (retire) retireEditor()
    }

    private fun retireEditor() {
        // Keep the delivery token: a rejected predecessor must also suppress its dependent key.
        // Retire the connection itself so an old correction cannot target the new caret.
        connection?.retire()
        connection = null
        submittedText = ""
        text.clear()
        setSelection(0)
        if (keepImeFocus && isAttachedToWindow) inputMethod.restartInput(this)
    }

    private fun synchronizeText() {
        val current = connection ?: return
        if (!current.active || current.batchDepth != 0 ||
            BaseInputConnection.getComposingSpanStart(text) >= 0
        ) return
        val desired = text.toString()
        if (desired == submittedText && current.outsideBefore == 0 && current.outsideAfter == 0) return
        if (!editContext.isValid) {
            clearCorrectionContext()
            return
        }
        if (desired != submittedText && onShortcutText(desired)) {
            retireEditor()
            return
        }
        if (pendingEdits >= MAX_PENDING_EDITS || desired.length > MAX_LOCAL_TEXT) {
            clearCorrectionContext()
            return
        }
        val shared = submittedText.commonPrefixWith(desired).length
        val deleteBefore = submittedText.length - shared + current.outsideBefore
        val insertion = desired.substring(shared)
        val forwardDeletes = current.outsideAfter
        current.outsideBefore = 0
        current.outsideAfter = 0
        val destination = input ?: return
        val token = editContext
        submittedText = desired
        pendingEdits++
        scope.launch {
            // Main.immediate enters the existing transport queue before the next IME callback.
            val result = destination.applyTextEdit(deleteBefore, insertion, layout, token)
            if (editContext !== token) return@launch
            pendingEdits--
            if (result != RemoteTextEditResult.Submitted) {
                recoverText()
                clearCorrectionContext()
            } else if (pendingEdits == 0) {
                trimRecentContext()
            }
        }
        repeat(forwardDeletes) { destination.tapKey(RemoteKey.Delete, token) }
        if ('\n' in insertion || '\r' in insertion) retireEditor()
    }

    private fun trimRecentContext() {
        if (BaseInputConnection.getComposingSpanStart(text) >= 0 ||
            selectionStart != text.length || selectionEnd != text.length ||
            text.toString() != submittedText || text.length <= RECENT_CONTEXT_LENGTH
        ) return
        val drop = text.length - RECENT_CONTEXT_LENGTH
        text.delete(0, drop)
        submittedText = submittedText.substring(drop)
        setSelection(text.length)
    }

    private inner class KeyboardConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        var active = true
        var batchDepth = 0
        var outsideBefore = 0
        var outsideAfter = 0

        private fun edit(block: () -> Boolean): Boolean {
            if (!active || !keepImeFocus || connection !== this) return false
            val result = block()
            if (text.length > MAX_LOCAL_TEXT) {
                clearCorrectionContext()
                return false
            }
            if (result) synchronizeText()
            return result
        }

        override fun beginBatchEdit(): Boolean {
            if (!active || batchDepth >= 16) return false
            batchDepth++
            super.beginBatchEdit()
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (!active || batchDepth == 0) return false
            batchDepth--
            super.endBatchEdit()
            synchronizeText()
            return batchDepth != 0
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            acceptText(text) { super.commitText(text?.normalizedLineBreaks(), newCursorPosition) }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            acceptText(text) { super.setComposingText(text?.normalizedLineBreaks(), newCursorPosition) }

        private fun acceptText(value: CharSequence?, block: () -> Boolean): Boolean {
            if (!active) return false
            if (value != null && value.length > MAX_LOCAL_TEXT) {
                onRecoverText(value.toString())
                clearCorrectionContext()
                return false
            }
            return edit(block)
        }

        @RequiresApi(33)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            acceptText(text) { super.commitText(text.normalizedLineBreaks(), newCursorPosition, textAttribute) }

        @RequiresApi(33)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            acceptText(text) { super.setComposingText(text.normalizedLineBreaks(), newCursorPosition, textAttribute) }

        @RequiresApi(33)
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
            edit { super.setComposingRegion(start, end, textAttribute) }

        @RequiresApi(34)
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            acceptText(text) { super.replaceText(start, end, text.normalizedLineBreaks(), newCursorPosition, textAttribute) }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            edit { super.setComposingRegion(start, end) }

        override fun setSelection(start: Int, end: Int): Boolean =
            edit { super.setSelection(start, end) }

        override fun finishComposingText(): Boolean = edit { super.finishComposingText() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            delete(beforeLength, afterLength, false) { super.deleteSurroundingText(beforeLength, afterLength) }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            delete(beforeLength, afterLength, true) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        private fun delete(before: Int, after: Int, codePoints: Boolean, localDelete: () -> Boolean): Boolean {
            if (!active || before !in 0..RECENT_CONTEXT_LENGTH || after !in 0..RECENT_CONTEXT_LENGTH) return false
            val composingStart = BaseInputConnection.getComposingSpanStart(text)
            val composingEnd = BaseInputConnection.getComposingSpanEnd(text)
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0).let {
                if (composingStart >= 0) minOf(it, composingStart) else it
            }
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0).let {
                if (composingEnd >= 0) maxOf(it, composingEnd) else it
            }
            val availableBefore = if (codePoints) Character.codePointCount(text, 0, start) else start
            val availableAfter = if (codePoints) Character.codePointCount(text, end, text.length) else text.length - end
            val extraBefore = (before - availableBefore).coerceAtLeast(0)
            val extraAfter = (after - availableAfter).coerceAtLeast(0)
            // No remote text readback exists to validate a bulk replacement outside our suffix.
            if (extraBefore > 1 || extraAfter > 1) return false
            if (text.isEmpty() && batchDepth == 0) {
                repeat(extraBefore) { sendSoftKey(RemoteKey.Backspace, retire = false) }
                repeat(extraAfter) { sendSoftKey(RemoteKey.Delete, retire = false) }
                return true
            }
            outsideBefore += extraBefore
            outsideAfter += extraAfter
            return edit(localDelete)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP && event.keyCode in consumedKeyUps) {
                consumedKeyUps.remove(event.keyCode)
                return true
            }
            if (!active) return false
            if (localEscape.onKeyEvent(event, interceptLocalEscape, onLocalEscape)) return true
            val key = remoteKeyForAndroidKeyCode(event.keyCode) ?: return false
            if (key == RemoteKey.Backspace || key == RemoteKey.Delete) {
                return when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (key == RemoteKey.Delete && selectionStart == text.length && selectionEnd == text.length) {
                        deleteSurroundingText(0, 1)
                    } else if (text.isNotEmpty()) edit { deleteKeyLocally(event) }
                        else if (key == RemoteKey.Backspace) deleteSurroundingText(1, 0)
                        else deleteSurroundingText(0, 1)
                    KeyEvent.ACTION_UP -> true
                    else -> false
                }
            }
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    finishComposingText()
                    if (!active) return false
                    if (event.repeatCount == 0) {
                        consumedKeyUps.add(event.keyCode)
                        sendSoftKey(key)
                    }
                    true
                }
                KeyEvent.ACTION_UP -> true
                else -> false
            }
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (!active) return false
            finishComposingText()
            if (!active) return false
            sendSoftKey(RemoteKey.Enter)
            return true
        }

        private val consumedKeyUps = mutableSetOf<Int>()

        override fun commitCompletion(text: CompletionInfo?): Boolean = edit { super.commitCompletion(text) }
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = active && super.commitCorrection(correctionInfo)
        override fun performContextMenuAction(id: Int): Boolean = edit { super.performContextMenuAction(id) }
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            if (active) super.getTextBeforeCursor(n, flags) else null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            if (active) super.getTextAfterCursor(n, flags) else null
        override fun getSelectedText(flags: Int): CharSequence? = if (active) super.getSelectedText(flags) else null
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (active) super.getExtractedText(request, flags) else null
        @RequiresApi(31)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
            if (active) super.getSurroundingText(beforeLength, afterLength, flags) else null
        override fun getCursorCapsMode(reqModes: Int): Int = if (active) super.getCursorCapsMode(reqModes) else 0
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
            active && super.requestCursorUpdates(cursorUpdateMode)
        @RequiresApi(33)
        override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
            active && super.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter)

        // A HID console has no screen-to-text geometry for Android handwriting gestures.
        @RequiresApi(34)
        override fun performHandwritingGesture(gesture: HandwritingGesture, executor: Executor?, consumer: IntConsumer?) {
            if (executor != null && consumer != null) {
                executor.execute { consumer.accept(InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED) }
            }
        }
        @RequiresApi(34)
        override fun previewHandwritingGesture(gesture: PreviewableHandwritingGesture, cancellationSignal: CancellationSignal?): Boolean = false

        override fun closeConnection() {
            if (!active) return
            if (connection === this) clearCorrectionContext(restart = false) else retire()
        }

        fun retire() {
            if (!active) return
            active = false
            // Close may finish composition internally; it must never submit after losing focus.
            super.closeConnection()
            batchDepth = 0
        }
    }

    private fun deleteKeyLocally(event: KeyEvent): Boolean = super.onKeyDown(event.keyCode, event)

    private fun CharSequence.normalizedLineBreaks(): CharSequence {
        if ('\r' !in this) return this
        val normalized = SpannableStringBuilder(this)
        for (index in normalized.lastIndex downTo 0) {
            if (this[index] == '\r') {
                val end = if (index + 1 < length && this[index + 1] == '\n') index + 2 else index + 1
                normalized.replace(index, end, "\n")
            }
        }
        return normalized
    }

    private companion object {
        const val RECENT_CONTEXT_LENGTH = 256
        const val MAX_LOCAL_TEXT = 4096
        const val MAX_PENDING_EDITS = 32
    }
}
