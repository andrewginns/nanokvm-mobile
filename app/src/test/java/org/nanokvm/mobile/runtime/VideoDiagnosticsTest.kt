package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoDiagnosticsTest {
    @Test
    fun `dropped frames accumulate without changing the transient message`() {
        val initial = BackendSession(message = "Keep this app-authored status", droppedFrames = 4L)

        val updated = initial.recordDroppedFrames(3)

        assertEquals(7L, updated.droppedFrames)
        assertEquals("Keep this app-authored status", updated.message)
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
