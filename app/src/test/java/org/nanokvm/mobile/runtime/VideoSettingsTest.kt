package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.nanokvm.video.NanoKvmVideoPreference

class VideoSettingsTest {
    @Test
    fun `transport preference maps to video session preference`() {
        val expected = mapOf(
            VideoTransportPreference.Auto to NanoKvmVideoPreference.AUTO,
            VideoTransportPreference.WEBRTC to NanoKvmVideoPreference.WEBRTC,
            VideoTransportPreference.H264 to NanoKvmVideoPreference.H264,
            VideoTransportPreference.MJPEG to NanoKvmVideoPreference.MJPEG,
        )

        expected.forEach { (appPreference, videoPreference) ->
            val config = VideoSettings(transportPreference = appPreference).toNanoKvmVideoConfig()
            assertEquals(videoPreference, config.preference)
        }
    }

    @Test
    fun `resolution height maps to matching decoder dimensions`() {
        val expected = mapOf(
            0 to (1920 to 1080),
            480 to (640 to 480),
            600 to (800 to 600),
            720 to (1280 to 720),
            1080 to (1920 to 1080),
        )

        expected.forEach { (height, dimensions) ->
            val decoder = VideoSettings(resolutionHeight = height).toNanoKvmVideoConfig().decoder
            assertEquals(dimensions.first, decoder.expectedWidth)
            assertEquals(dimensions.second, decoder.expectedHeight)
        }
    }

    @Test
    fun `unsupported settings fail before network work is scheduled`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(resolutionHeight = 900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(framesPerSecond = 61)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(bitrateKbps = 4_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(jpegQuality = 75)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(gopFrames = 25)
        }
    }
}
