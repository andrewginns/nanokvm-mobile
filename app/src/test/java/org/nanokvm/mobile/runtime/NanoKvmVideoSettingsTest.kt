package org.nanokvm.mobile.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint

class NanoKvmVideoSettingsTest {
    @Test
    fun `new servers accept FPS quality and GOP with automatic resolution`() = runBlocking {
        val requests = mutableListOf<String>()
        val applied = applyVideoSettings(supportsManualResolution = false, requests)

        assertEquals(0, applied.resolutionHeight)
        assertEquals(otherScreenRequests, requests)
    }

    @Test
    fun `older servers apply manual resolution before other video settings`() = runBlocking {
        val requests = mutableListOf<String>()
        val applied = applyVideoSettings(supportsManualResolution = true, requests)

        assertEquals(settings, applied)
        assertEquals(listOf("""{"type":"resolution","value":720}""") + otherScreenRequests, requests)
    }

    private val settings = VideoSettings(
        resolutionHeight = 720,
        framesPerSecond = 60,
        bitrateKbps = 2_000,
        jpegQuality = 60,
        gopFrames = 30,
    )

    private val otherScreenRequests = listOf(
        """{"type":"fps","value":60}""",
        """{"type":"quality","value":2000}""",
        """{"type":"quality","value":60}""",
        """{"type":"gop","value":30}""",
    )

    private suspend fun applyVideoSettings(
        supportsManualResolution: Boolean,
        requests: MutableList<String>,
    ): VideoSettings {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("/api/vm/screen", request.url.encodedPath)
            val content = Buffer()
            checkNotNull(request.body).writeTo(content)
            requests.add(content.readUtf8())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"code":0}""".toResponseBody())
                .build()
        }.build()
        return NanoKvmClient.using(NanoKvmEndpoint.parse("192.0.2.250"), transport).use { client ->
            NanoKvmConsoleFeatureGateway(client.api).applyVideoSettings(settings, supportsManualResolution)
        }
    }
}
