package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nanokvm.video.NanoKvmVideoPreference

class WebRtcRuntimeProviderTest {
    @Test
    fun `non WebRTC preferences do not create the runtime`() {
        var createCalls = 0
        val provider = WebRtcRuntimeProvider {
            createCalls++
            null
        }

        assertNull(provider.resolve(NanoKvmVideoPreference.AUTO))
        assertNull(provider.resolve(NanoKvmVideoPreference.H264))
        assertNull(provider.resolve(NanoKvmVideoPreference.MJPEG))

        assertEquals(0, createCalls)
    }

    @Test
    fun `explicit WebRTC creates the runtime once and reuses the result`() {
        var createCalls = 0
        val provider = WebRtcRuntimeProvider {
            createCalls++
            null
        }

        assertNull(provider.resolve(NanoKvmVideoPreference.WEBRTC))
        assertNull(provider.resolve(NanoKvmVideoPreference.WEBRTC))

        assertEquals(1, createCalls)
    }
}
