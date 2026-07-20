package org.nanokvm.mobile.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nanokvm.mobile.clipboard.ClipboardEmptyReason
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.clipboard.ClipboardRejectionReason

@RunWith(AndroidJUnit4::class)
class AndroidClipboardGatewayInstrumentedTest {
    @Test
    fun acceptsOneDirectPlainTextItemAndPreservesSensitiveMarker() {
        val clip = ClipData.newPlainText("password", "line one\r\nline two")
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }

        val result = AndroidClipboardGateway { clip }.readDirectPlainText()

        assertTrue(result is ClipboardReadResult.Available)
        val payload = (result as ClipboardReadResult.Available).payload
        assertEquals("line one\nline two", payload.text)
        assertTrue(payload.isSensitive)
    }

    @Test
    fun modelsAbsentAndEmptyTextClips() {
        assertEquals(
            ClipboardReadResult.Empty(ClipboardEmptyReason.NoPrimaryClip),
            AndroidClipboardGateway { null }.readDirectPlainText(),
        )
        assertEquals(
            ClipboardReadResult.Empty(ClipboardEmptyReason.EmptyText),
            AndroidClipboardGateway { ClipData.newPlainText("empty", "") }.readDirectPlainText(),
        )
    }

    @Test
    fun rejectsMultipleItems() {
        val clip = ClipData.newPlainText("plain", "first").apply {
            addItem(ClipData.Item("second"))
        }

        assertRejected(clip, ClipboardRejectionReason.MultipleItems)
    }

    @Test
    fun rejectsRichTextAndUriWithoutCoercion() {
        assertRejected(
            ClipData.newHtmlText("rich", "shown", "<b>shown</b>"),
            ClipboardRejectionReason.RichText,
        )
        assertRejected(
            ClipData(
                ClipDescription("uri", arrayOf("text/plain")),
                ClipData.Item(Uri.parse("content://example/secret")),
            ),
            ClipboardRejectionReason.UriContent,
        )
    }

    @Test
    fun rejectsIntentAndNonPlainMimeTypes() {
        assertRejected(
            ClipData(
                ClipDescription("intent", arrayOf("text/plain")),
                ClipData.Item(Intent(Intent.ACTION_VIEW)),
            ),
            ClipboardRejectionReason.IntentContent,
        )
        assertRejected(
            ClipData(
                ClipDescription("json", arrayOf("application/json")),
                ClipData.Item("{\"secret\":true}"),
            ),
            ClipboardRejectionReason.NonPlainText,
        )
    }

    @Test
    fun mapsSecurityFailureToUnavailable() {
        val result = AndroidClipboardGateway {
            throw SecurityException("clipboard not available while backgrounded")
        }.readDirectPlainText()

        assertEquals(ClipboardReadResult.Unavailable, result)
    }

    @Test
    fun rejectsOversizedPlainTextWithoutRetainingIt() {
        assertRejected(
            ClipData.newPlainText("oversized", "a".repeat(1_025)),
            ClipboardRejectionReason.TooLarge,
        )
    }

    private fun assertRejected(clip: ClipData, expected: ClipboardRejectionReason) {
        assertEquals(
            ClipboardReadResult.Rejected(expected),
            AndroidClipboardGateway { clip }.readDirectPlainText(),
        )
    }
}
