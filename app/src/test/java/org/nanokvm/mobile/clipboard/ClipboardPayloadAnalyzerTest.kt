package org.nanokvm.mobile.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadAnalyzerTest {
    @Test
    fun `normalizes line endings and reports code points and utf8 bytes`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("A\r\nB\r😃")

        assertEquals("A\nB\n😃", payload.text)
        assertEquals(5, payload.characterCount)
        assertEquals(8, payload.utf8ByteCount)
        assertEquals(setOf(ClipboardTextWarning.ContainsNewline), payload.warnings)
    }

    @Test
    fun `reports tabs and control characters independently`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("one\ttwo\u0000")

        assertEquals(
            setOf(
                ClipboardTextWarning.ContainsTab,
                ClipboardTextWarning.ContainsOtherControlCharacter,
            ),
            payload.warnings,
        )
    }

    @Test
    fun `reports the server paste byte bound using utf8 size`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("£".repeat(513))

        assertEquals(513, payload.characterCount)
        assertEquals(1_026, payload.utf8ByteCount)
        assertEquals(2, payload.bytesOverServerPasteLimit)
        assertFalse(payload.fitsServerPasteLimit)
        assertTrue(ClipboardTextWarning.ExceedsServerPasteLimit in payload.warnings)
    }

    @Test
    fun `payload diagnostics redact sensitive and ordinary clipboard text`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText(
            text = "do-not-log-this",
            isSensitive = true,
        )
        val request = PasteConfirmationRequest(
            payload = payload,
            target = target(sessionGeneration = 4),
        )

        assertFalse(payload.toString().contains("do-not-log-this"))
        assertFalse(request.toString().contains("do-not-log-this"))
        assertFalse(request.toString().contains("profile-1"))
        assertFalse(request.toString().contains("Office NanoKVM"))
        assertFalse(request.toString().contains("192.0.2.250"))
        assertTrue(payload.toString().contains("<redacted>"))
        assertTrue(request.toString().contains("sessionGeneration=4"))
        assertTrue(payload.isSensitive)
    }

    @Test
    fun `confirmation stays bound only to the approved session and destination`() {
        val request = PasteConfirmationRequest(
            payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("text"),
            target = target(sessionGeneration = 9),
        )

        assertTrue(request.remainsBoundTo(target(sessionGeneration = 9)))
        assertFalse(request.remainsBoundTo(target(sessionGeneration = 10)))
        assertFalse(
            request.remainsBoundTo(
                target(sessionGeneration = 9).copy(profileId = "another-profile"),
            ),
        )
    }

    @Test
    fun `target binding rejects invalid identity data`() {
        assertThrows(IllegalArgumentException::class.java) {
            target(sessionGeneration = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            target(sessionGeneration = 0).copy(authority = "")
        }
    }

    private fun target(sessionGeneration: Long) = PasteTargetBinding(
        profileId = "profile-1",
        destinationLabel = "Office NanoKVM",
        authority = "192.0.2.250",
        sessionGeneration = sessionGeneration,
    )
}
