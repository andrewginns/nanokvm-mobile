package org.nanokvm.video

import org.junit.Assert.assertEquals
import org.junit.Test

class NanoKvmVideoFallbackPolicyTest {
    @Test
    fun `WebRTC preference uses fresh WebRTC H264 MJPEG attempts in order`() {
        assertEquals(
            listOf(
                NanoKvmVideoTransport.WEBRTC,
                NanoKvmVideoTransport.H264,
                NanoKvmVideoTransport.MJPEG,
            ),
            NanoKvmVideoPreference.WEBRTC.transportChain(),
        )
    }

    @Test
    fun `existing preferences preserve their transport behavior`() {
        assertEquals(
            listOf(NanoKvmVideoTransport.H264, NanoKvmVideoTransport.MJPEG),
            NanoKvmVideoPreference.AUTO.transportChain(),
        )
        assertEquals(
            listOf(NanoKvmVideoTransport.H264),
            NanoKvmVideoPreference.H264.transportChain(),
        )
        assertEquals(
            listOf(NanoKvmVideoTransport.MJPEG),
            NanoKvmVideoPreference.MJPEG.transportChain(),
        )
    }
}
