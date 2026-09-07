package org.nanokvm.mobile

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Xml
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.RemoteTextEditContext
import org.nanokvm.mobile.runtime.RemoteTextEditResult
import org.nanokvm.mobile.runtime.RemoteTextEditRejection
import org.nanokvm.mobile.ui.components.ConsoleKeyboard
import org.nanokvm.mobile.ui.input.KeyboardContextBoundary
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

/** Exercises the production editor and records host input without opening a network connection. */
class NativeKeyboardInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sink = KeyboardRecordingSink()
    private val visible = mutableStateOf(true)
    private val generation = mutableLongStateOf(0L)
    private val contextBoundary = KeyboardContextBoundary()

    @After
    fun dismissKeyboard() {
        // Optional live QA uses the same production editor after a normal test. The
        // instrumentation thread waits; the UI and installed keyboard remain live.
        val liveMs = InstrumentationRegistry.getArguments().getString("nativeImeLiveMs")
            ?.toLongOrNull()?.coerceIn(0L, 300_000L) ?: 0L
        if (liveMs > 0L) {
            // Synthetic tests request their own connection. Reconnect the real IME before
            // manually driving its keys so it cannot retain that replaced test connection.
            composeRule.runOnIdle {
                val view = editor()
                view.context.getSystemService(InputMethodManager::class.java).apply {
                    restartInput(view)
                    showSoftInput(view, 0)
                }
            }
            exposeLiveKeyboardTree(liveMs)
        }
        composeRule.runOnIdle {
            visible.value = false
            WindowCompat.getInsetsController(
                composeRule.activity.window,
                composeRule.activity.window.decorView,
            ).hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitForIdle()
    }

    @Test
    fun standardEditorKeepsSuggestionsAndVoiceInputAvailable() {
        renderKeyboard()
        composeRule.runOnIdle {
            val info = EditorInfo()
            assertNotNull(editor().onCreateInputConnection(info))
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType and InputType.TYPE_MASK_VARIATION)
            assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            assertEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
        }
    }

    @Test
    fun compositionCanBeCorrectedBeforeCommit() = compareWithReference { connection ->
        assertTrue(connection.setComposingText("teh", 1))
        assertTrue(connection.commitText("the ", 1))
    }

    @Test
    fun mixedLineBreaksPreserveBlankLines() {
        renderKeyboard()
        composeRule.runOnIdle {
            assertTrue(connection().commitText("a\r\r\nb", 1))
            assertEquals("a\n\nb", sink.remoteText.value)
        }
    }

    @Test
    fun finishingCompositionSendsTheWordExactlyOnceBeforeTheSeparator() =
        compareWithReference { connection ->
            assertTrue(connection.setComposingText("hello", 1))
            assertTrue(connection.finishComposingText())
            assertTrue(connection.commitText(" ", 1))
        }

    @Test
    fun codepointCorrectionReplacesTheOriginallyCommittedWord() = compareWithReference { connection ->
        assertTrue(connection.commitText("teh ", 1))
        assertTrue(connection.deleteSurroundingTextInCodePoints(4, 0))
        assertTrue(connection.commitText("the ", 1))
    }

    @Test
    fun utf16CorrectionReplacesTheOriginallyCommittedWord() = compareWithReference { connection ->
        assertTrue(connection.commitText("teh ", 1))
        assertTrue(connection.deleteSurroundingText(4, 0))
        assertTrue(connection.commitText("the ", 1))
    }

    @Test
    fun autocorrectionUndoRestoresOneOriginalWordAcrossBatchAndComposition() =
        compareWithReference { connection ->
            assertTrue(connection.commitText("the ", 1))
            connection.beginBatchEdit()
            assertTrue(connection.deleteSurroundingTextInCodePoints(4, 0))
            assertTrue(connection.setComposingText("teh", 1))
            connection.endBatchEdit()
            assertTrue(connection.finishComposingText())
            assertTrue(connection.commitText(" ", 1))
        }

    @Test
    fun backspaceKeyEventEditsTheComposingWordBeforeSendingIt() {
        renderKeyboard()
        lateinit var inputConnection: InputConnection
        composeRule.runOnIdle {
            inputConnection = connection()
            assertTrue(inputConnection.setComposingText("cat", 1))
            assertTrue(inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            assertTrue(inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
        }
        // Framework key dispatch can be posted; finish only after the UI has handled that pair.
        composeRule.runOnIdle {
            assertTrue(inputConnection.finishComposingText())
            assertEquals("ca", sink.remoteText.value)
        }
    }

    @Test
    fun repeatedCorrectionsKeepExactlyOneCopyOfEachWord() = compareWithReference { connection ->
        repeat(3) {
            assertTrue(connection.commitText("teh ", 1))
            connection.beginBatchEdit()
            assertTrue(connection.deleteSurroundingTextInCodePoints(4, 0))
            assertTrue(connection.commitText("the ", 1))
            connection.endBatchEdit()
        }
    }

    @Test
    fun longTypingKeepsBoundedRecentContextAndCanStillCorrectTheLastWord() {
        renderKeyboard()
        composeRule.runOnIdle {
            val connection = connection()
            val prefix = "word ".repeat(100)
            assertTrue(connection.commitText(prefix, 1))
            assertEquals(prefix.takeLast(256), connection.getTextBeforeCursor(1_000, 0).toString())
            assertTrue(connection.deleteSurroundingTextInCodePoints(5, 0))
            assertTrue(connection.commitText("last ", 1))
            assertEquals("word ".repeat(99) + "last ", sink.remoteText.value)
        }
    }

    @Test
    fun oversizedCallbackIsRejectedWithoutSendingPartialText() {
        renderKeyboard()
        composeRule.runOnIdle {
            assertFalse(connection().commitText("x".repeat(4_097), 1))
            assertEquals("", sink.remoteText.value)
            assertTrue(sink.textEdits.isEmpty())
        }
    }

    @Test
    fun selectedRecentTextCanBeCorrectedWithoutAppendingADuplicate() =
        compareWithReference { connection ->
            assertTrue(connection.commitText("cat", 1))
            assertTrue(connection.setSelection(0, 3))
            assertTrue(connection.commitText("dog", 1))
        }

    @Test
    fun keyboardCanQueryItsRecentlyCommittedContext() {
        renderKeyboard()
        composeRule.runOnIdle {
            val connection = connection()
            assertTrue(connection.commitText("the ", 1))
            assertEquals("the ", connection.getTextBeforeCursor(20, 0).toString())
            assertEquals("", connection.getTextAfterCursor(20, 0).toString())
            assertEquals("the ", sink.remoteText.value)
        }
    }

    @Test
    fun batchedCorrectionReachesTheSinkAsOneReplacement() {
        renderKeyboard()
        composeRule.runOnIdle {
            val connection = connection()
            assertTrue(connection.commitText("teh", 1))
            assertTrue(connection.beginBatchEdit())
            assertTrue(connection.deleteSurroundingTextInCodePoints(3, 0))
            assertTrue(connection.commitText("the", 1))
            assertEquals("teh", sink.remoteText.value)
            assertEquals(1, sink.textEdits.size)
            connection.endBatchEdit()
            assertEquals("the", sink.remoteText.value)
            assertEquals(2 to "he", sink.textEdits.last())
            assertEquals(2, sink.textEdits.size)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun textAttributeOverloadsCommitAndCorrectThroughTheSameTransport() {
        renderKeyboard()
        composeRule.runOnIdle {
            val connection = connection()
            val attributes = TextAttribute.Builder().build()
            assertTrue(connection.commitText("cat", 1, attributes))
            assertEquals("cat", sink.remoteText.value)
            assertTrue(connection.setComposingRegion(0, 3, attributes))
            assertTrue(connection.setComposingText("coat", 1, attributes))
            assertEquals("cat", sink.remoteText.value)
            assertTrue(connection.commitText("coat ", 1, attributes))
            assertEquals("coat ", sink.remoteText.value)
            connection.closeConnection()
            assertFalse(connection.commitText("late", 1, attributes))
            assertEquals("coat ", sink.remoteText.value)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun replaceTextOverloadReplacesRecentContextWithoutAppending() =
        compareWithReference { connection ->
            assertTrue(connection.commitText("cat", 1))
            assertTrue(connection.replaceText(0, 3, "dog", 1, null))
        }

    @Test
    fun pointerContextBoundaryDiscardsDraftAndOldConnectionBeforeNewHostInput() {
        renderKeyboard()
        composeRule.runOnIdle {
            val old = connection()
            assertTrue(old.setComposingText("draft", 1))
            contextBoundary.invalidate()
            sink.setRemoteDocument("other target")
            assertFalse(old.finishComposingText())
            assertFalse(old.commitText("late", 1))
            val fresh = connection()
            assertEquals("", fresh.getTextBeforeCursor(20, 0).toString())
            assertTrue(fresh.deleteSurroundingTextInCodePoints(1, 0))
            assertEquals("other targe", sink.remoteText.value)
            assertEquals(listOf(RemoteKey.Backspace to true, RemoteKey.Backspace to false), sink.keyEvents)
            assertTrue(sink.textEdits.isEmpty())
        }
    }

    @Test
    fun zeroDeletionDoesNothingAndForwardDeletionUsesForwardDeleteWithoutLocalContext() {
        renderKeyboard()
        composeRule.runOnIdle {
            sink.setRemoteDocument("ab", cursor = 1)
            val connection = connection()
            assertTrue(connection.deleteSurroundingText(0, 0))
            assertEquals("ab", sink.remoteText.value)
            assertTrue(sink.keyEvents.isEmpty())
            assertTrue(connection.deleteSurroundingText(0, 1))
            assertEquals("a", sink.remoteText.value)
            assertEquals(listOf(RemoteKey.Delete to true, RemoteKey.Delete to false), sink.keyEvents)
        }
    }

    @Test
    fun forwardDeleteCanReachBeyondTheLocallyKnownSuffix() {
        renderKeyboard()
        composeRule.runOnIdle {
            sink.setRemoteDocument("!", cursor = 0)
            val connection = connection()
            assertTrue(connection.commitText("ab", 1))
            assertEquals("ab!", sink.remoteText.value)
            assertTrue(connection.deleteSurroundingText(0, 1))
            assertEquals("ab", sink.remoteText.value)
            assertEquals(listOf(RemoteKey.Delete to true, RemoteKey.Delete to false), sink.keyEvents)
        }
    }

    @Test
    fun forwardDeleteKeyEventCanReachBeyondTheLocallyKnownSuffix() {
        renderKeyboard()
        composeRule.runOnIdle {
            sink.setRemoteDocument("!", cursor = 0)
            val connection = connection()
            assertTrue(connection.commitText("ab", 1))
            assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL)))
            assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD_DEL)))
            assertEquals("ab", sink.remoteText.value)
            assertEquals(listOf(RemoteKey.Delete to true, RemoteKey.Delete to false), sink.keyEvents)
        }
    }

    @Test
    fun shiftAloneDoesNotDiscardAWholeImeCommit() {
        renderKeyboard()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.console_key_shift))
            .performScrollTo()
            .performClick()
        awaitEditor()
        composeRule.runOnIdle {
            assertTrue(connection().commitText("HELLO", 1))
            assertEquals("HELLO", sink.remoteText.value)
            assertEquals(listOf(RemoteKey.Shift to true, RemoteKey.Shift to false), sink.keyEvents)
        }
    }

    @Test
    fun rejectedCompositionDoesNotSendAnEnterWithoutItsText() {
        renderKeyboard()
        composeRule.runOnIdle {
            sink.editResult = RemoteTextEditResult.Rejected(RemoteTextEditRejection.UnsupportedText)
            val connection = connection()
            assertTrue(connection.setComposingText("\u2603", 1))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals("", sink.remoteText.value)
            assertFalse(sink.keyEvents.contains(RemoteKey.Enter to true))
        }
    }

    @Test
    fun navigationInvalidatesTheConnectionThatCouldStillCorrectThePreviousCaret() {
        renderKeyboard()
        composeRule.runOnIdle {
            val old = connection()
            assertTrue(old.commitText("cat", 1))
            assertTrue(old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)))
            old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
            assertFalse(old.commitText("dog", 1))
            assertEquals("cat", sink.remoteText.value)
            val fresh = connection()
            assertEquals("", fresh.getTextBeforeCursor(20, 0).toString())
        }
    }

    @Test
    fun closedConnectionCannotSendLateText() {
        renderKeyboard()
        composeRule.runOnIdle {
            val old = connection()
            old.closeConnection()
            assertFalse(old.commitText("late", 1))
            assertEquals("", sink.remoteText.value)
            assertTrue(connection().commitText("fresh", 1))
            assertEquals("fresh", sink.remoteText.value)
        }
    }

    @Test
    fun hiddenConnectionRejectsLateCallbacksAndAnExplicitFreshConnectionCanType() {
        renderKeyboard()
        lateinit var old: InputConnection
        composeRule.runOnIdle { old = connection(); visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse(old.commitText("late", 1))
            visible.value = true
        }
        awaitEditor()
        composeRule.runOnIdle {
            assertTrue(connection().commitText("fresh", 1))
            assertEquals("fresh", sink.remoteText.value)
        }
    }

    @Test
    fun gboardContinuesTypingAfterRepeatedReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ime = Settings.Secure.getString(
            instrumentation.targetContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        assumeTrue("This installed-keyboard regression requires English Gboard", ime?.startsWith("com.google.android.inputmethod.latin/") == true)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            renderKeyboard()
            // Never request a test InputConnection or restartInput here: Android and the
            // installed keyboard must retain/recreate their own connection on reopen.
            listOf("cat" to "cat ", "dog" to "cat dog ", "fish" to "cat dog fish ")
                .forEachIndexed { index, (typed, expected) ->
                    if (index > 0) {
                        composeRule.onNodeWithContentDescription(
                            composeRule.activity.getString(R.string.console_hide_native_keyboard),
                        ).performScrollTo().performClick()
                        composeRule.waitForIdle()
                        composeRule.onNodeWithText("Reopen test keyboard").performClick()
                        awaitEditor()
                    }
                    awaitInstalledKeyboardReady()
                    typed.forEach { tapInstalledKeyboardKey(it.toString()) }
                    tapInstalledKeyboardKey("Space")
                    fun state() = composeRule.runOnIdle {
                        val focus = composeRule.activity.currentFocus
                        "host=[${sink.remoteText.value}], editor=[${(focus as? EditText)?.text}], " +
                            "focused=${focus?.hasFocus()}, visible=${visible.value}"
                    }
                    Log.i("NativeImeReopenQa", "Cycle $index expected [$expected]; ${state()}")
                    try {
                        composeRule.waitUntil(timeoutMillis = 5_000L) { sink.remoteText.value == expected }
                    } catch (failure: Throwable) {
                        throw AssertionError("Installed keyboard cycle $index expected [$expected]; ${state()}", failure)
                    }
                }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun awaitInstalledKeyboardReady() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.runOnIdle {
                val view = editor()
                val inputMethod = view.context.getSystemService(InputMethodManager::class.java)
                inputMethod.isActive(view) && inputMethod.isAcceptingText &&
                    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        // IME insets can become visible before the keyboard's entrance animation ends.
        // Let its observed touchscreen targets settle without repairing its connection.
        SystemClock.sleep(350L)
    }

    private fun tapInstalledKeyboardKey(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun findBounds(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && node.contentDescription?.toString().equals(label, ignoreCase = true)) {
                return Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::findBounds)?.let { return it }
            }
            return null
        }
        var bounds: Rect? = null
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            bounds = automation.windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root?.let(::findBounds) }
                .firstOrNull()
            bounds != null
        }
        val key = checkNotNull(bounds)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, key.exactCenterX(), key.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Could not tap installed keyboard key $label", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        SystemClock.sleep(100L)
    }

    private fun compareWithReference(sequence: (InputConnection) -> Unit) {
        renderKeyboard()
        composeRule.runOnIdle {
            val reference = EditText(composeRule.activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                setText("")
                setSelection(0)
            }
            val referenceConnection = checkNotNull(reference.onCreateInputConnection(EditorInfo()))
            sequence(referenceConnection)
            sequence(connection())
            assertEquals("The host should receive the reference editor's final text", reference.text.toString(), sink.remoteText.value)
        }
    }

    private fun exposeLiveKeyboardTree(liveMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val output = File(instrumentation.targetContext.cacheDir, "native-ime-windows.xml")
        val nextOutput = File(instrumentation.targetContext.cacheDir, "native-ime-windows.next.xml")
        val end = SystemClock.uptimeMillis() + liveMs
        try {
            while (SystemClock.uptimeMillis() < end) {
                val state = composeRule.runOnIdle {
                    val focusedEditor = editor() as? EditText
                    Triple(
                        sink.remoteText.value,
                        focusedEditor?.text?.toString().orEmpty(),
                        "${focusedEditor?.selectionStart},${focusedEditor?.selectionEnd}",
                    )
                }
                nextOutput.writer().use { writer ->
                    val xml = Xml.newSerializer().apply { setOutput(writer); startDocument("UTF-8", true) }
                    fun writeNode(node: AccessibilityNodeInfo) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        xml.startTag(null, "node")
                        xml.attribute(null, "text", node.text?.toString().orEmpty())
                        xml.attribute(null, "content-desc", node.contentDescription?.toString().orEmpty())
                        xml.attribute(null, "class", node.className?.toString().orEmpty())
                        xml.attribute(null, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                        for (index in 0 until node.childCount) node.getChild(index)?.let(::writeNode)
                        xml.endTag(null, "node")
                    }
                    xml.startTag(null, "hierarchy")
                    xml.attribute(null, "host-text", state.first)
                    xml.attribute(null, "editor-text", state.second)
                    xml.attribute(null, "selection", state.third)
                    xml.attribute(null, "live-remaining-ms", (end - SystemClock.uptimeMillis()).coerceAtLeast(0L).toString())
                    for (window in automation.windows) {
                        xml.startTag(null, "window")
                        xml.attribute(null, "type", window.type.toString())
                        window.root?.let(::writeNode)
                        xml.endTag(null, "window")
                    }
                    xml.endTag(null, "hierarchy")
                    xml.endDocument()
                }
                check(nextOutput.renameTo(output)) { "Could not publish the complete live keyboard tree" }
                SystemClock.sleep(500L)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            output.delete()
            nextOutput.delete()
        }
    }

    private fun renderKeyboard() {
        composeRule.setContent {
            NanoKvmTheme(useDynamicColor = false) {
                Column {
                    Text("Native IME test host: [${sink.remoteText.value}]", Modifier.testTag("native-ime-host-text"))
                    if (!visible.value) {
                        Button(onClick = { visible.value = true }) { Text("Reopen test keyboard") }
                    }
                    ConsoleKeyboard(
                        input = sink,
                        visible = visible.value,
                        releaseGeneration = generation.longValue,
                        interceptLocalEscape = false,
                        onLocalEscape = {},
                        layout = KeyboardLayout.Us,
                        onLayoutChange = {},
                        onClose = { visible.value = false },
                        onCtrlAltDelete = {},
                        onViewportAction = {},
                        contextBoundary = contextBoundary,
                    )
                }
            }
        }
        awaitEditor()
    }

    private fun awaitEditor() {
        var lastFocus = "none"
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                val focus = composeRule.activity.currentFocus
                lastFocus = "${focus?.javaClass?.name}, editor=${focus?.onCheckIsTextEditor()}, " +
                    "touchFocusable=${focus?.isFocusableInTouchMode}"
                focus?.onCheckIsTextEditor() == true
            }
        } catch (failure: Exception) {
            throw AssertionError("Native keyboard did not focus its text editor; last focus: $lastFocus", failure)
        }
    }

    private fun editor(): View = checkNotNull(composeRule.activity.currentFocus)
    private fun connection(): InputConnection = checkNotNull(editor().onCreateInputConnection(EditorInfo()))
}

private class KeyboardRecordingSink : RemoteInputSink {
    val remoteText = mutableStateOf("")
    val keyEvents = mutableListOf<Pair<RemoteKey, Boolean>>()
    val textEdits = mutableListOf<Pair<Int, String>>()
    var editResult: RemoteTextEditResult = RemoteTextEditResult.Submitted
    private var cursor = 0

    fun setRemoteDocument(text: String, cursor: Int = text.length) {
        remoteText.value = text
        this.cursor = cursor
        keyEvents.clear()
    }

    override fun typeCommittedText(text: String, layout: KeyboardLayout) {
        remoteText.value = remoteText.value.substring(0, cursor) + text + remoteText.value.substring(cursor)
        cursor += text.length
    }

    override suspend fun applyTextEdit(
        deleteBefore: Int,
        insertText: String,
        layout: KeyboardLayout,
        context: RemoteTextEditContext,
    ): RemoteTextEditResult {
        if (!context.isValid) return RemoteTextEditResult.Stale
        textEdits += deleteBefore to insertText
        if (editResult != RemoteTextEditResult.Submitted) {
            context.invalidate()
            return editResult
        }
        repeat(deleteBefore) {
            key(RemoteKey.Backspace, true)
            key(RemoteKey.Backspace, false)
        }
        typeCommittedText(insertText, layout)
        return RemoteTextEditResult.Submitted
    }

    override fun key(key: RemoteKey, pressed: Boolean) {
        keyEvents += key to pressed
        if (!pressed) return
        val current = remoteText.value
        when (key) {
            RemoteKey.Backspace -> if (cursor > 0) {
                remoteText.value = current.removeRange(cursor - 1, cursor)
                cursor--
            }
            RemoteKey.Delete -> if (cursor < current.length) remoteText.value = current.removeRange(cursor, cursor + 1)
            RemoteKey.Enter -> typeCommittedText("\n", KeyboardLayout.Us)
            else -> Unit
        }
    }

    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) = Unit
    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) = Unit
    override fun mouseButton(button: MouseButton, pressed: Boolean) = Unit
    override fun scrollWheel(steps: Int) = Unit
    override fun scrollHorizontal(steps: Int) = Unit
    override fun releaseAllInput() = Unit
}
