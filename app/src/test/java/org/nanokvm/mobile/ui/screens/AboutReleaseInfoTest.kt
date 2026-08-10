package org.nanokvm.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.BuildConfig

class AboutReleaseInfoTest {
    @Test
    fun developmentSourceUrlUsesTheCurrentMainBranch() {
        assertEquals(
            "https://github.com/andrewginns/nanokvm-mobile/tree/main",
            BuildConfig.SOURCE_URL,
        )
    }

    @Test
    fun certificateDigestAndDisplayUseTheFullSha256Identity() {
        val digest = sha256CertificateFingerprint("public certificate bytes".encodeToByteArray())

        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'A'..'F' })
        assertEquals(
            digest.chunked(2).joinToString(":"),
            formatSha256Fingerprint(digest.lowercase()),
        )
    }

    @Test
    fun documentChunkingIsBoundedAndLossless() {
        val document = buildString {
            repeat(500) { index ->
                append("Licence line ")
                append(index)
                append('\n')
            }
        }

        val chunks = chunkAboutDocumentText(document, maximumChunkCharacters = 97)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 97 })
        assertEquals(document, chunks.joinToString(separator = ""))
    }

    @Test
    fun fingerprintFormattingAcceptsColonSeparatedPublicMetadata() {
        assertEquals("AA:BB:CC", formatSha256Fingerprint("aa: bb:cc"))
    }
}
