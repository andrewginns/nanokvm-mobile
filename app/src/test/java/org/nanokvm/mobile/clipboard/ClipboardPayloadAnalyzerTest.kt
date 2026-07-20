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
    fun `accepts exact utf8 boundary and rejects before retaining one byte over`() {
        val accepted = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress("£".repeat(512))
        val rejected = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress("£".repeat(512) + "a")

        assertTrue(accepted is ClipboardPayloadAnalysis.Accepted)
        assertEquals(1_024, (accepted as ClipboardPayloadAnalysis.Accepted).payload.utf8ByteCount)
        assertEquals(ClipboardPayloadAnalysis.TooLarge, rejected)
        assertThrows(IllegalArgumentException::class.java) {
            ClipboardPayloadAnalyzer.analyzeDirectPlainText("£".repeat(513))
        }
    }

    @Test
    fun `line-ending normalization is included in the allocation-free ingress bound`() {
        val accepted = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
            "a\r\n".repeat(512),
        )
        val rejected = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
            "a\r\n".repeat(512) + "a",
        )

        assertTrue(accepted is ClipboardPayloadAnalysis.Accepted)
        assertEquals(1_024, (accepted as ClipboardPayloadAnalysis.Accepted).payload.utf8ByteCount)
        assertEquals(ClipboardPayloadAnalysis.TooLarge, rejected)
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
