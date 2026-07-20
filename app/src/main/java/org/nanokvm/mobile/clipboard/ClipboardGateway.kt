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
    TooLarge,
}

enum class ClipboardTextWarning {
    ContainsNewline,
    ContainsTab,
    ContainsOtherControlCharacter,
}

sealed interface ClipboardPayloadAnalysis {
    data class Accepted(val payload: ClipboardPayload) : ClipboardPayloadAnalysis
    data object TooLarge : ClipboardPayloadAnalysis
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
    override fun toString(): String =
        "ClipboardPayload(text=<redacted>, isSensitive=$isSensitive, " +
            "characterCount=$characterCount, utf8ByteCount=$utf8ByteCount, " +
            "warnings=$warnings)"
}

object ClipboardPayloadAnalyzer {
    const val SERVER_PASTE_LIMIT_BYTES = 1_024

    /**
     * Applies the remote paste limit before copying or retaining text from an Android ingress.
     * Counting stops as soon as the normalized UTF-8 representation is known to be too large.
     */
    fun analyzeDirectPlainTextAtIngress(
        text: CharSequence,
        isSensitive: Boolean = false,
    ): ClipboardPayloadAnalysis {
        val metrics = text.boundedNormalizedMetrics(SERVER_PASTE_LIMIT_BYTES)
            ?: return ClipboardPayloadAnalysis.TooLarge
        val normalized = text.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        return ClipboardPayloadAnalysis.Accepted(
            ClipboardPayload(
                text = normalized,
                isSensitive = isSensitive,
                characterCount = metrics.characterCount,
                utf8ByteCount = metrics.utf8ByteCount,
                warnings = metrics.warnings,
            ),
        )
    }

    /** Creates a bounded immutable snapshot for app-owned text and test fixtures. */
    fun analyzeDirectPlainText(
        text: CharSequence,
        isSensitive: Boolean = false,
    ): ClipboardPayload = when (val analysis = analyzeDirectPlainTextAtIngress(text, isSensitive)) {
        is ClipboardPayloadAnalysis.Accepted -> analysis.payload
        ClipboardPayloadAnalysis.TooLarge -> throw IllegalArgumentException(
            "Clipboard text exceeds the ${SERVER_PASTE_LIMIT_BYTES}-byte remote paste limit",
        )
    }

    private fun CharSequence.boundedNormalizedMetrics(limit: Int): ClipboardTextMetrics? {
        var characterCount = 0
        var utf8ByteCount = 0
        var containsNewline = false
        var containsTab = false
        var containsOtherControl = false
        var index = 0
        while (index < length) {
            val first = this[index]
            val normalizedCodePoint: Int
            val consumedChars: Int
            if (first == '\r') {
                normalizedCodePoint = '\n'.code
                consumedChars = if (index + 1 < length && this[index + 1] == '\n') 2 else 1
            } else if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
                normalizedCodePoint = Character.toCodePoint(first, this[index + 1])
                consumedChars = 2
            } else {
                normalizedCodePoint = first.code
                consumedChars = 1
            }

            utf8ByteCount += normalizedCodePoint.utf8ByteCount()
            if (utf8ByteCount > limit) return null
            characterCount++
            when {
                normalizedCodePoint == '\n'.code -> containsNewline = true
                normalizedCodePoint == '\t'.code -> containsTab = true
                Character.isISOControl(normalizedCodePoint) -> containsOtherControl = true
            }
            index += consumedChars
        }

        return ClipboardTextMetrics(
            characterCount = characterCount,
            utf8ByteCount = utf8ByteCount,
            warnings = buildSet {
                if (containsNewline) add(ClipboardTextWarning.ContainsNewline)
                if (containsTab) add(ClipboardTextWarning.ContainsTab)
                if (containsOtherControl) add(ClipboardTextWarning.ContainsOtherControlCharacter)
            },
        )
    }

    private fun Int.utf8ByteCount(): Int = when {
        this < 0x80 -> 1
        this < 0x800 -> 2
        this <= 0xffff -> 3
        else -> 4
    }

    private data class ClipboardTextMetrics(
        val characterCount: Int,
        val utf8ByteCount: Int,
        val warnings: Set<ClipboardTextWarning>,
    )
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
