package org.nanokvm.mobile.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CometInputTest {
    @Test fun releaseStopsPacedTextAndRetirementDropsQueuedKeys() = runTest {
        val sent = mutableListOf<String>()
        val input = CometInput(this, Any(), { sent.add(it.hex()) }, {}, { throw it }, {}, {})
        input.key(RemoteKey.Shift, true)
        input.mouseButton(MouseButton.Left, true)
        runCurrent()
        input.key(RemoteKey.A, true)
        input.release()
        runCurrent()
        assertEquals(listOf("010153686966744c656674", "02016c656674", "02006c656674", "010053686966744c656674"), sent)
        sent.clear()
        input.type("ABCD", KeyboardLayout.Us, true)
        runCurrent()
        input.key(RemoteKey.F1, true)
        input.release(retire = true)
        advanceUntilIdle()
        assertEquals(listOf("010153686966744c656674", "01014b657941", "01004b657941", "010053686966744c656674"), sent)
    }

    @Test fun rejectedEditFencesTheFollowingSoftEnter() = runTest {
        val sent = mutableListOf<String>()
        val input = CometInput(this, Any(), { sent.add(it.hex()) }, {}, { throw it }, {}, {})
        val context = RemoteTextEditContext()
        val result = input.edit(1, "🛰", KeyboardLayout.Us, context)
        assertEquals(RemoteTextEditResult.Rejected(RemoteTextEditRejection.UnsupportedText), result)
        input.tap(RemoteKey.Enter, context)
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        input.release(retire = true)
    }

    @Test fun layoutAndNewlineMatchPreviewAndOutputSelectionPrecedesMotion() = runTest {
        val events = mutableListOf<String>()
        val input = CometInput(this, Any(), { events.add(it.hex()) }, { events.add("relative=$it") }, { throw it }, {}, {})
        input.pointer(true, org.nanokvm.protocol.CometHid.delta(false, 1, -1))
        input.type("#\r\n", KeyboardLayout.Uk, true)
        advanceUntilIdle()
        assertEquals(listOf("relative=true", "040001ff", "01014261636b736c617368", "01004261636b736c617368",
            "010153686966744c656674", "0101456e746572", "0100456e746572", "010053686966744c656674"), events)
        input.release(retire = true)
    }
}
