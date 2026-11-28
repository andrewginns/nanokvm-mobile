package org.nanokvm.mobile.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.video.NanoKvmVideoListener
import org.nanokvm.video.NanoKvmVideoStatus
import org.nanokvm.video.NanoKvmVideoTransport

class SessionBoundVideoListenerTest {
    @Test
    fun `invalidation drains an in flight callback and rejects cancellation ignoring late work`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)
        val delivered = AtomicInteger()
        val listener = SessionBoundVideoListener(
            object : NanoKvmVideoListener {
                override fun onStatusChanged(status: NanoKvmVideoStatus) {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                    delivered.incrementAndGet()
                }
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.execute {
                listener.onStatusChanged(
                    NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.H264),
                )
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            executor.execute {
                listener.invalidate()
                invalidationFinished.countDown()
            }

            assertFalse(invalidationFinished.await(100, TimeUnit.MILLISECONDS))
            releaseCallback.countDown()
            assertTrue(invalidationFinished.await(5, TimeUnit.SECONDS))

            listener.onStatusChanged(
                NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.MJPEG),
            )
            assertEquals(1, delivered.get())
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
