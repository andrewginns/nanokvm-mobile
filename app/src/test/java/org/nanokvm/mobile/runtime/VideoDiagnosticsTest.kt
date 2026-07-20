package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoDiagnosticsTest {
    @Test
    fun `repeated equal action feedback receives a new revision`() {
        val first = BackendSession().withActionFeedback(ConsoleMessage.CtrlAltDeleteSent)
        val second = first.withActionFeedback(ConsoleMessage.CtrlAltDeleteSent)

        assertEquals(1L, first.lastActionFeedback?.revision)
        assertEquals(2L, second.lastActionFeedback?.revision)
        assertEquals(first.lastActionFeedback?.content, second.lastActionFeedback?.content)
    }

    @Test
    fun `replacement session starts without prior action feedback`() {
        val old = BackendSession(sessionGeneration = 4L)
            .withActionFeedback(ConsoleMessage.HidInterfaceReset)
        val replacement = BackendSession(sessionGeneration = old.sessionGeneration + 1L)

        assertEquals(null, replacement.lastActionFeedback)
    }

    @Test
    fun `dropped frames accumulate without changing action feedback`() {
        val initial = BackendSession(droppedFrames = 4L)
            .withActionFeedback(ConsoleMessage.VideoSettingsApplied)

        val updated = initial.recordDroppedFrames(3)

        assertEquals(7L, updated.droppedFrames)
        assertEquals(
            ConsoleMessage.VideoSettingsApplied,
            updated.lastActionFeedback?.content,
        )
        assertEquals(0L, updated.videoStallEvents)
    }

    @Test
    fun `non-positive dropped frame callback is ignored`() {
        val initial = BackendSession(droppedFrames = 4L)

        assertSame(initial, initial.recordDroppedFrames(0))
        assertSame(initial, initial.recordDroppedFrames(-2))
    }

    @Test
    fun `video stalls accumulate and fresh sessions reset both counters`() {
        val updated = BackendSession(videoStallEvents = 2L).recordVideoStall()

        assertEquals(3L, updated.videoStallEvents)
        assertEquals(0L, BackendSession().droppedFrames)
        assertEquals(0L, BackendSession().videoStallEvents)
    }

    @Test
    fun `diagnostic counters saturate instead of overflowing`() {
        val saturatedDrops = BackendSession(droppedFrames = Long.MAX_VALUE)
            .recordDroppedFrames(Int.MAX_VALUE)
        val saturatedStalls = BackendSession(videoStallEvents = Long.MAX_VALUE)
            .recordVideoStall()

        assertEquals(Long.MAX_VALUE, saturatedDrops.droppedFrames)
        assertEquals(Long.MAX_VALUE, saturatedStalls.videoStallEvents)
    }
}
