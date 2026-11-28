package org.nanokvm.video

import okio.ByteString.Companion.decodeHex
import org.junit.Assert.*
import org.junit.Test

class CometMediaTest {
    @Test fun separatesHeartbeatAndRejectsTruncatedFrames() {
        assertNull(CometMedia.frame("ff".decodeHex(), 5))
        for (wire in listOf("01", "0101")) {
            assertThrows(IllegalArgumentException::class.java) { CometMedia.frame(wire.decodeHex(), 5) }
        }
        val key = CometMedia.frame("01010000000165".decodeHex(), 1234)!!
        assertEquals(1234L, key.timestampUs)
        assertTrue(key.isKeyFrame)
        assertArrayEquals("0000000165".decodeHex().toByteArray(), key.data)
        val queue = H264FrameQueue(1)
        val delta = CometMedia.frame("010041".decodeHex(), 1240)!!
        assertFalse(queue.offer(delta).accepted)
        assertTrue(queue.offer(key).accepted)
        assertFalse(queue.offer(delta).accepted)
        assertTrue(queue.snapshot().awaitingKeyFrame)
        assertTrue(queue.offer(key).accepted)
    }
}
