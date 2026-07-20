package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicoClawSurfaceLifecycleTest {
    @Test
    fun `surface requires visibility foreground and exact live generation`() {
        val installed = binding(7)

        assertTrue(picoClawSurfaceCanLoad(true, true, installed, installed))
        assertFalse(picoClawSurfaceCanLoad(false, true, installed, installed))
        assertFalse(picoClawSurfaceCanLoad(true, false, installed, installed))
        assertFalse(picoClawSurfaceCanLoad(true, true, binding(8), installed))
        assertFalse(picoClawSurfaceCanLoad(true, true, null, installed))
    }

    @Test
    fun `provider request owns clears and redacts mutable key`() {
        val key = "provider-private-key".toCharArray()
        val request = PicoClawModelConfigurationRequest(
            model = "private-model",
            apiBase = "https://private-provider.example/v1",
            apiKey = key,
        )

        request.clear()

        assertTrue(key.all { it == '\u0000' })
        assertFalse(request.toString().contains("provider-private-key"))
        assertFalse(request.toString().contains("private-provider"))
        assertFalse(request.toString().contains("private-model"))
    }

    @Test
    fun `chat UI history remains bounded and content diagnostics are redacted`() {
        val messages = (1..80).fold(emptyList<PicoClawMessageUiState>()) { current, index ->
            appendBoundedPicoClawMessage(
                current,
                PicoClawMessageUiState(
                    PicoClawMessageContent.ApplianceText(
                        PicoClawMessageRole.Assistant,
                        "private-$index",
                    ),
                ),
            )
        }

        assertEquals(64, messages.size)
        assertEquals("private-17", messages.first().applianceText())
        assertEquals("private-80", messages.last().applianceText())
        assertTrue(messages.none { it.toString().contains("private-") })
    }

    @Test
    fun `chat display content enforces utf8 bounds and redacts diagnostics`() {
        val applianceText = PicoClawMessageContent.ApplianceText(
            PicoClawMessageRole.Assistant,
            "private response",
        )
        val toolAction = PicoClawMessageContent.ToolAction("click")

        assertEquals(16, applianceText.utf8ByteCount)
        assertEquals(5, toolAction.utf8ByteCount)
        assertFalse(applianceText.toString().contains("private response"))
        assertFalse(toolAction.toString().contains("click"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PicoClawMessageContent.ApplianceText(
                PicoClawMessageRole.Assistant,
                "a".repeat(32 * 1_024 + 1),
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            PicoClawMessageContent.ToolAction("a".repeat(257))
        }
    }

    private fun PicoClawMessageUiState.applianceText(): String =
        (content as PicoClawMessageContent.ApplianceText).value

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )
}
