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
        assertEquals(1, payload.chunkCount)
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
    fun `uses one chunk at exact chunk boundary and two at one byte over`() {
        val exact = ClipboardPayloadAnalyzer.analyzeDirectPlainText("a".repeat(1_024))
        val over = ClipboardPayloadAnalyzer.analyzeDirectPlainText("a".repeat(1_025))

        assertEquals(1, exact.chunkCount)
        assertEquals(listOf(1_024), exact.chunkRanges.map { it.utf8ByteCount })
        assertEquals(2, over.chunkCount)
        assertEquals(listOf(1_024, 1), over.chunkRanges.map { it.utf8ByteCount })
    }

    @Test
    fun `accepts text over one chunk and exposes bounded contiguous ranges`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText(
            "a".repeat(1_023) + "£" + "b".repeat(1_023) + "😃" + "tail",
        )

        assertEquals(3, payload.chunkCount)
        assertEquals(payload.text, payload.textChunks().joinToString(separator = ""))
        assertEquals(
            listOf(1_023, 1_024, 9),
            payload.chunkRanges.map(ClipboardChunkRange::utf8ByteCount),
        )
        payload.chunkRanges.forEachIndexed { index, range ->
            assertTrue(range.startIndex < range.endIndexExclusive)
            assertTrue(range.utf8ByteCount <= ClipboardPayloadAnalyzer.PASTE_CHUNK_LIMIT_BYTES)
            assertEquals(
                range.utf8ByteCount,
                payload.text.substring(range.startIndex, range.endIndexExclusive)
                    .toByteArray(Charsets.UTF_8).size,
            )
            if (index > 0) {
                assertEquals(payload.chunkRanges[index - 1].endIndexExclusive, range.startIndex)
            }
        }
    }

    @Test
    fun `does not split surrogate pairs at chunk boundaries`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText(
            "a".repeat(1_020) + "😃" + "😎" + "z",
        )

        assertEquals(2, payload.chunkCount)
        assertEquals(listOf(1_024, 5), payload.chunkRanges.map { it.utf8ByteCount })
        assertEquals("a".repeat(1_020) + "😃", payload.textChunks()[0])
        assertEquals("😎z", payload.textChunks()[1])
        payload.chunkRanges.drop(1).forEach { range ->
            assertFalse(payload.text[range.startIndex].isLowSurrogate())
        }
    }

    @Test
    fun `malformed surrogate byte metrics match the retained UTF-8 representation`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("\uD800")

        assertEquals(payload.text.encodeToByteArray().size, payload.utf8ByteCount)
        assertEquals(
            payload.text.encodeToByteArray().size,
            payload.chunkRanges.single().utf8ByteCount,
        )
    }

    @Test
    fun `accepts exact retained utf8 boundary and rejects one byte over`() {
        val accepted = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress("£".repeat(32_768))
        val rejected = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
            "£".repeat(32_768) + "a",
        )

        assertTrue(accepted is ClipboardPayloadAnalysis.Accepted)
        val payload = (accepted as ClipboardPayloadAnalysis.Accepted).payload
        assertEquals(ClipboardPayloadAnalyzer.MAX_RETAINED_PASTE_BYTES, payload.utf8ByteCount)
        assertEquals(64, payload.chunkCount)
        assertEquals(ClipboardPayloadAnalysis.TooLarge, rejected)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ClipboardPayloadAnalyzer.analyzeDirectPlainText("a".repeat(65_537))
        }
        assertTrue(error.message.orEmpty().contains("65536-byte retained paste limit"))
    }

    @Test
    fun `line-ending normalization is included in retained bound and chunk reconstruction`() {
        val accepted = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
            "a\r\n".repeat(32_768),
        )
        val rejected = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
            "a\r\n".repeat(32_768) + "a",
        )

        assertTrue(accepted is ClipboardPayloadAnalysis.Accepted)
        val payload = (accepted as ClipboardPayloadAnalysis.Accepted).payload
        assertEquals(ClipboardPayloadAnalyzer.MAX_RETAINED_PASTE_BYTES, payload.utf8ByteCount)
        assertEquals(64, payload.chunkCount)
        assertEquals(payload.text, payload.textChunks().joinToString(separator = ""))
        assertFalse(payload.text.contains('\r'))
        assertEquals(ClipboardPayloadAnalysis.TooLarge, rejected)
    }

    @Test
    fun `oversized ingress is rejected before requesting a retained string`() {
        val oversized = object : CharSequence {
            override val length: Int = ClipboardPayloadAnalyzer.MAX_RETAINED_PASTE_BYTES + 1

            override fun get(index: Int): Char = 'a'

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                error("subSequence must not be called")

            override fun toString(): String = error("toString must not be called")
        }

        assertEquals(
            ClipboardPayloadAnalysis.TooLarge,
            ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(oversized),
        )
    }

    @Test
    fun `ingress snapshots only the indexed bounded text and never trusts a different toString`() {
        val inconsistent = object : CharSequence {
            override val length: Int = 1

            override fun get(index: Int): Char = 'a'

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "a"

            override fun toString(): String = "x".repeat(
                ClipboardPayloadAnalyzer.MAX_RETAINED_PASTE_BYTES + 1,
            )
        }

        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText(inconsistent)

        assertEquals("a", payload.text)
        assertEquals(1, payload.characterCount)
        assertEquals(1, payload.utf8ByteCount)
        assertEquals(listOf("a"), payload.textChunks())
    }

    @Test
    fun `empty payload has no empty chunks`() {
        val payload = ClipboardPayloadAnalyzer.analyzeDirectPlainText("")

        assertEquals(0, payload.chunkCount)
        assertTrue(payload.chunkRanges.isEmpty())
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
        assertTrue(payload.toString().contains("chunkCount=1"))
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
