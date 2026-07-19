package org.nanokvm.video

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NanoKvmWebRtcRuntimeInstrumentedTest {
    @Test
    fun nativePeerCreatesAnOfferWithoutTerminatingTheProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            "WebRTC network monitoring requires ACCESS_NETWORK_STATE",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE),
        )
        val runtime = NanoKvmWebRtcRuntime.get(context)
        assertTrue("Native WebRTC should be available on the test device", runtime.isAvailable)

        val offerCreated = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val surfaceTexture = SurfaceTexture(false).apply {
            setDefaultBufferSize(1_920, 1_080)
        }
        val surface = Surface(surfaceTexture)
        val peer = runtime.peerFactory.create(
            iceServers = emptyList(),
            target = NanoKvmWebRtcRuntime.SurfaceRenderTarget(surface),
            listener = object : NanoKvmWebRtcPeerListener {
                override fun onLocalOffer(description: NanoKvmWebRtcSessionDescription) {
                    offerCreated.countDown()
                }

                override fun onLocalCandidate(candidate: NanoKvmWebRtcIceCandidate) = Unit
                override fun onFrameRendered(timestampNs: Long) = Unit
                override fun onVideoSizeChanged(width: Int, height: Int) = Unit

                override fun onFailure(cause: Throwable) {
                    failure.compareAndSet(null, cause)
                    offerCreated.countDown()
                }
            },
        )

        try {
            peer.start()
            assertTrue("Native WebRTC did not create an offer", offerCreated.await(10, TimeUnit.SECONDS))
            assertNull("Native WebRTC failed before creating an offer", failure.get())
        } finally {
            peer.close()
            surface.release()
            surfaceTexture.release()
        }
    }
}
