package org.nanokvm.mobile.clipboard

/**
 * Reads clipboard text only in response to an explicit, foreground paste action.
 *
 * Implementations must not coerce clipboard items, follow content-provider URIs, retain clipboard
 * history, or persist the returned payload.
 */
fun interface ClipboardGateway {
    fun readDirectPlainText(): ClipboardReadResult
}

sealed interface ClipboardReadResult {
    data class Available(val payload: ClipboardPayload) : ClipboardReadResult

    data class Empty(val reason: ClipboardEmptyReason) : ClipboardReadResult

    data class Rejected(val reason: ClipboardRejectionReason) : ClipboardReadResult

    /** Clipboard access was unavailable, for example because the app was not in the foreground. */
    data object Unavailable : ClipboardReadResult
}

enum class ClipboardEmptyReason {
    NoPrimaryClip,
    NoItems,
    EmptyText,
}

enum class ClipboardRejectionReason {
    MultipleItems,
    RichText,
    UriContent,
    IntentContent,
    NonPlainText,
    MissingDirectText,
}

enum class ClipboardTextWarning {
    ContainsNewline,
    ContainsTab,
    ContainsOtherControlCharacter,
    ExceedsServerPasteLimit,
}

/**
 * Memory-only clipboard material prepared for a user confirmation screen.
 *
 * This deliberately is not a data/serializable class, and [toString] never includes [text], so an
 * innocent diagnostic of a surrounding result cannot disclose clipboard contents.
 */
class ClipboardPayload internal constructor(
    val text: String,
    val isSensitive: Boolean,
    val characterCount: Int,
    val utf8ByteCount: Int,
    val warnings: Set<ClipboardTextWarning>,
) {
    val serverPasteLimitBytes: Int
        get() = ClipboardPayloadAnalyzer.SERVER_PASTE_LIMIT_BYTES

    val bytesOverServerPasteLimit: Int
        get() = (utf8ByteCount - serverPasteLimitBytes).coerceAtLeast(0)

    val fitsServerPasteLimit: Boolean
        get() = bytesOverServerPasteLimit == 0

    override fun toString(): String =
        "ClipboardPayload(text=<redacted>, isSensitive=$isSensitive, " +
            "characterCount=$characterCount, utf8ByteCount=$utf8ByteCount, " +
            "warnings=$warnings)"
}

object ClipboardPayloadAnalyzer {
    const val SERVER_PASTE_LIMIT_BYTES = 1_024

    /** Creates an immutable analysis snapshot; callers should discard it after the paste attempt. */
    fun analyzeDirectPlainText(
        text: CharSequence,
        isSensitive: Boolean = false,
    ): ClipboardPayload {
        val normalized = text.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val utf8ByteCount = normalized.toByteArray(Charsets.UTF_8).size
        val warnings = buildSet {
            if ('\n' in normalized) add(ClipboardTextWarning.ContainsNewline)
            if ('\t' in normalized) add(ClipboardTextWarning.ContainsTab)
            if (normalized.hasOtherControlCharacter()) {
                add(ClipboardTextWarning.ContainsOtherControlCharacter)
            }
            if (utf8ByteCount > SERVER_PASTE_LIMIT_BYTES) {
                add(ClipboardTextWarning.ExceedsServerPasteLimit)
            }
        }
        return ClipboardPayload(
            text = normalized,
            isSensitive = isSensitive,
            characterCount = normalized.codePointCount(0, normalized.length),
            utf8ByteCount = utf8ByteCount,
            warnings = warnings,
        )
    }

    private fun String.hasOtherControlCharacter(): Boolean {
        var index = 0
        while (index < length) {
            val codePoint = codePointAt(index)
            if (codePoint != '\n'.code &&
                codePoint != '\t'.code &&
                Character.isISOControl(codePoint)
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }
}

/** Identifies the exact remote session and destination approved by a later confirmation step. */
data class PasteTargetBinding(
    val profileId: String,
    val destinationLabel: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile ID must not be blank" }
        require(destinationLabel.isNotBlank()) { "Destination label must not be blank" }
        require(authority.isNotBlank()) { "Destination authority must not be blank" }
        require(sessionGeneration >= 0) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "PasteTargetBinding(profileId=<redacted>, destinationLabel=<redacted>, " +
            "authority=<redacted>, sessionGeneration=$sessionGeneration)"
}

/**
 * A memory-only confirmation request. Execution must compare [target] with the current session so a
 * reconnect or profile switch cannot redirect clipboard contents after the user confirms.
 */
class PasteConfirmationRequest(
    val payload: ClipboardPayload,
    val target: PasteTargetBinding,
) {
    fun remainsBoundTo(currentTarget: PasteTargetBinding): Boolean = target == currentTarget

    override fun toString(): String =
        "PasteConfirmationRequest(payload=$payload, target=$target)"
}
