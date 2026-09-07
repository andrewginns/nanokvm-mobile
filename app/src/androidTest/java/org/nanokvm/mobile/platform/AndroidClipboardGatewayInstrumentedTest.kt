package org.nanokvm.mobile.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.PersistableBundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.nanokvm.mobile.clipboard.ClipboardEmptyReason
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.clipboard.ClipboardRejectionReason

@RunWith(AndroidJUnit4::class)
class AndroidClipboardGatewayInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun acceptsOneDirectPlainTextItemAndPreservesSensitiveMarker() {
        val text = SpannableStringBuilder("line one\r\nline two").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val clip = ClipData.newPlainText("password", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }

        val result = AndroidClipboardGateway { clip }.readDirectPlainText()

        assertTrue(result is ClipboardReadResult.Available)
        val payload = (result as ClipboardReadResult.Available).payload
        text.replace(0, text.length, "changed after read")
        assertEquals("line one\nline two", payload.text)
        assertTrue(payload.isSensitive)
    }

    @Test
    fun acceptsPlainTextCopiedByAStandardAndroidEditor() {
        lateinit var reference: EditText
        composeRule.setContent {
            AndroidView(factory = { context ->
                EditText(context).also {
                    reference = it
                    it.inputType = InputType.TYPE_CLASS_TEXT
                    it.setText("Blender Cycles render test")
                }
            })
        }
        composeRule.runOnIdle { assertTrue(reference.requestFocus()) }
        composeRule.waitUntil(timeoutMillis = 5_000L) { reference.hasWindowFocus() }
        composeRule.runOnIdle {
            reference.selectAll()
            assertTrue(reference.onTextContextMenuItem(android.R.id.copy))
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            val clip = checkNotNull(clipboard.primaryClip)
            assertEquals(1, clip.description.mimeTypeCount)
            assertEquals(ClipDescription.MIMETYPE_TEXT_PLAIN, clip.description.getMimeType(0))
            assertEquals(null, clip.getItemAt(0).htmlText)

            val result = AndroidClipboardGateway(composeRule.activity).readDirectPlainText()

            assertTrue("Standard editor Copy should be accepted, got $result", result is ClipboardReadResult.Available)
            assertEquals("Blender Cycles render test", (result as ClipboardReadResult.Available).payload.text)
        }
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
    fun acceptsPlainTextOverOneChunk() {
        val result = AndroidClipboardGateway {
            ClipData.newPlainText("chunked", "a".repeat(1_025))
        }.readDirectPlainText()

        assertTrue(result is ClipboardReadResult.Available)
        val payload = (result as ClipboardReadResult.Available).payload
        assertEquals(1_025, payload.utf8ByteCount)
        assertEquals(2, payload.chunkCount)
    }

    @Test
    fun rejectsPlainTextOverRetainedLimitWithoutRetainingIt() {
        assertRejected(
            ClipData.newPlainText("oversized", "a".repeat(65_537)),
            ClipboardRejectionReason.TooLarge,
        )
        assertRejected(
            ClipData.newPlainText("oversized spans", SpannableStringBuilder("a".repeat(65_537))),
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
