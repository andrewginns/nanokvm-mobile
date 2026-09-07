package org.nanokvm.mobile.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.RemoteTextEditContext

class KeyboardContextBoundaryTest {
    @Test
    fun `pointer target changes invalidate queued text before dispatch`() {
        val boundary = KeyboardContextBoundary()
        var context = RemoteTextEditContext()
        boundary.register { context.invalidate() }
        val recorder = RecordingInput {
            assertFalse("The target must change only after text context is invalidated", context.isValid)
        }
        val input = ContextInvalidatingInputSink(recorder, boundary)
        val actions: List<() -> Unit> = listOf(
            { input.mouseButton(MouseButton.Left, true) },
            { input.moveAbsolute(100, 200, setOf(MouseButton.Left)) },
            { input.moveRelative(3, 4, setOf(MouseButton.Left)) },
            { input.scrollWheel(1) },
            { input.scrollHorizontal(-1) },
            { input.releaseAllInput() },
        )

        actions.forEach { action ->
            context = RemoteTextEditContext()
            action()
        }

        assertEquals(
            listOf(
                "button:Left:true",
                "absolute:100:200:[Left]",
                "relative:3:4:[Left]",
                "wheel:1",
                "horizontal:-1",
                "release",
            ),
            recorder.events,
        )
    }

    @Test
    fun `hover and keyboard methods do not trigger duplicate UI invalidation`() {
        val boundary = KeyboardContextBoundary()
        val context = RemoteTextEditContext()
        boundary.register { context.invalidate() }
        val recorder = RecordingInput { assertTrue(context.isValid) }
        val input = ContextInvalidatingInputSink(recorder, boundary)

        input.moveAbsolute(100, 200)
        input.moveRelative(3, 4)
        input.mouseButton(MouseButton.Left, false)
        input.scrollWheel(0)
        input.scrollHorizontal(0)
        input.typeCommittedText("the the", KeyboardLayout.Uk)
        // NativeKeyboard owns raw key boundaries; this delegate must not reset its editor twice.
        input.key(RemoteKey.Enter, true)
        input.key(RemoteKey.Enter, false)

        assertEquals(
            listOf(
                "absolute:100:200:[]",
                "relative:3:4:[]",
                "button:Left:false",
                "wheel:0",
                "horizontal:0",
                "text:the the:Uk",
                "key:Enter:true",
                "key:Enter:false",
            ),
            recorder.events,
        )
    }

    @Test
    fun `disposing an old editor cannot remove the current callback`() {
        val boundary = KeyboardContextBoundary()
        var oldCalls = 0
        var currentCalls = 0
        val disposeOld = boundary.register { oldCalls++ }
        val disposeCurrent = boundary.register { currentCalls++ }

        disposeOld()
        boundary.invalidate()
        assertEquals(0, oldCalls)
        assertEquals(1, currentCalls)

        disposeCurrent()
        boundary.invalidate()
        assertEquals(1, currentCalls)
    }

    private class RecordingInput(private val beforeDispatch: () -> Unit) : RemoteInputSink {
        val events = mutableListOf<String>()

        private fun record(event: String) {
            beforeDispatch()
            events += event
        }

        override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) =
            record("absolute:$x:$y:$buttons")

        override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) =
            record("relative:$deltaX:$deltaY:$buttons")

        override fun mouseButton(button: MouseButton, pressed: Boolean) = record("button:$button:$pressed")
        override fun scrollWheel(steps: Int) = record("wheel:$steps")
        override fun scrollHorizontal(steps: Int) = record("horizontal:$steps")
        override fun typeCommittedText(text: String, layout: KeyboardLayout) = record("text:$text:$layout")
        override fun key(key: RemoteKey, pressed: Boolean) = record("key:$key:$pressed")
        override fun releaseAllInput() = record("release")
    }
}
