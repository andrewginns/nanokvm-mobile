package org.nanokvm.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264FrameQueueTest {
    @Test
    fun `external bootstrap key lets queue accept following delta frames`() {
        val queue = H264FrameQueue(capacity = 3)

        queue.onExternalKeyFrameQueued()

        val result = queue.offer(frame(key = false, timestamp = 2))
        assertTrue(result.accepted)
        assertEquals(2L, queue.poll()?.timestampUs)
    }

    @Test
    fun ignoresDeltasUntilKeyFrame() {
        val queue = H264FrameQueue()

        val dropped = queue.offer(frame(1, key = false))

        assertFalse(dropped.accepted)
        assertEquals(H264FrameDropReason.AWAITING_KEY_FRAME, dropped.dropReason)
        assertNull(queue.poll())
        assertTrue(queue.offer(frame(2, key = true)).accepted)
        assertEquals(2L, queue.poll()?.timestampUs)
    }

    @Test
    fun backlogDiscardsWholeGopAndAwaitsAnotherKeyFrame() {
        val queue = H264FrameQueue(capacity = 3)
        queue.offer(frame(10, key = true))
        queue.offer(frame(11))
        queue.offer(frame(12))

        val result = queue.offer(frame(13))

        assertFalse(result.accepted)
        assertEquals(4, result.droppedFrames)
        assertEquals(H264FrameDropReason.STALE_BACKLOG, result.dropReason)
        assertEquals(0, queue.snapshot().size)
        assertTrue(queue.snapshot().awaitingKeyFrame)
        assertNull(queue.poll())
        assertFalse(queue.offer(frame(14)).accepted)
        assertTrue(queue.offer(frame(20, key = true)).accepted)
        assertEquals(20L, queue.poll()?.timestampUs)
    }

    @Test
    fun capacityOneAlsoDropsAnchorAndDependentDeltaTogether() {
        val queue = H264FrameQueue(capacity = 1)
        queue.offer(frame(1, key = true))

        val result = queue.offer(frame(2))

        assertFalse(result.accepted)
        assertEquals(2, result.droppedFrames)
        assertTrue(queue.snapshot().awaitingKeyFrame)
        assertNull(queue.poll())
    }

    @Test
    fun newerKeyFrameReplacesStaleBacklog() {
        val queue = H264FrameQueue()
        queue.offer(frame(1, key = true))
        queue.offer(frame(2))

        val result = queue.offer(frame(20, key = true))

        assertEquals(2, result.droppedFrames)
        assertEquals(H264FrameDropReason.REPLACED_BY_KEY_FRAME, result.dropReason)
        assertEquals(20L, queue.poll()?.timestampUs)
        assertNull(queue.poll())
    }

    @Test
    fun resetRequiresAnotherKeyFrame() {
        val queue = H264FrameQueue()
        queue.offer(frame(1, key = true))
        queue.clearAndAwaitKeyFrame()
        assertFalse(queue.offer(frame(2)).accepted)
        assertTrue(queue.snapshot().awaitingKeyFrame)
    }

    private fun frame(timestamp: Long, key: Boolean = false) = H264AccessUnit(
        isKeyFrame = key,
        timestampUs = timestamp,
        data = byteArrayOf(timestamp.toByte()),
    )
}
