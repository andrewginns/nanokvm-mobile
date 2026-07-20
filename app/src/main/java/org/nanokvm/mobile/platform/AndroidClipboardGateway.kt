package org.nanokvm.mobile.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.Spanned
import androidx.core.content.getSystemService
import org.nanokvm.mobile.clipboard.ClipboardEmptyReason
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalysis
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalyzer
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.clipboard.ClipboardRejectionReason

/** Android clipboard adapter that accepts one direct `text/plain` item and nothing else. */
class AndroidClipboardGateway internal constructor(
    private val primaryClipProvider: () -> ClipData?,
) : ClipboardGateway {
    constructor(context: Context) : this(
        primaryClipProvider = {
            context.applicationContext.getSystemService<ClipboardManager>()?.primaryClip
        },
    )

    override fun readDirectPlainText(): ClipboardReadResult {
        val clip = try {
            primaryClipProvider()
        } catch (_: SecurityException) {
            return ClipboardReadResult.Unavailable
        }
        if (clip == null) {
            return ClipboardReadResult.Empty(ClipboardEmptyReason.NoPrimaryClip)
        }
        if (clip.itemCount == 0) {
            return ClipboardReadResult.Empty(ClipboardEmptyReason.NoItems)
        }
        if (clip.itemCount != 1) {
            return ClipboardReadResult.Rejected(ClipboardRejectionReason.MultipleItems)
        }

        val item = clip.getItemAt(0)
        if (item.uri != null) {
            return ClipboardReadResult.Rejected(ClipboardRejectionReason.UriContent)
        }
        if (item.intent != null) {
            return ClipboardReadResult.Rejected(ClipboardRejectionReason.IntentContent)
        }

        val description = clip.description
        val mimeTypes = description.mimeTypes()
        if (item.htmlText != null || item.text is Spanned || mimeTypes.any(::isHtmlMimeType)) {
            return ClipboardReadResult.Rejected(ClipboardRejectionReason.RichText)
        }
        if (mimeTypes.size != 1 || !mimeTypes.single().equals(MIME_TEXT_PLAIN, ignoreCase = true)) {
            return ClipboardReadResult.Rejected(ClipboardRejectionReason.NonPlainText)
        }

        val directText = item.text
            ?: return ClipboardReadResult.Rejected(ClipboardRejectionReason.MissingDirectText)
        if (directText.isEmpty()) {
            return ClipboardReadResult.Empty(ClipboardEmptyReason.EmptyText)
        }

        return when (
            val analysis = ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
                text = directText,
                isSensitive = description.hasSensitiveMarker(),
            )
        ) {
            is ClipboardPayloadAnalysis.Accepted -> ClipboardReadResult.Available(analysis.payload)
            ClipboardPayloadAnalysis.TooLarge -> ClipboardReadResult.Rejected(
                ClipboardRejectionReason.TooLarge,
            )
        }
    }

    private fun ClipDescription.mimeTypes(): List<String> =
        List(mimeTypeCount, ::getMimeType)

    private fun ClipDescription.hasSensitiveMarker(): Boolean {
        val key = if (Build.VERSION.SDK_INT >= 33) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            LEGACY_SENSITIVE_EXTRA
        }
        return extras?.getBoolean(key, false) == true
    }

    private companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_TEXT_HTML = "text/html"
        const val LEGACY_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

        fun isHtmlMimeType(mimeType: String): Boolean =
            mimeType.equals(MIME_TEXT_HTML, ignoreCase = true)
    }
}
