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
    internal val chunkRanges: List<ClipboardChunkRange>,
) {
    val chunkCount: Int
        get() = chunkRanges.size

    /** Immutable text slices whose concatenation is exactly [text]. */
    internal fun textChunks(): List<String> = chunkRanges.map { range ->
        text.substring(range.startIndex, range.endIndexExclusive)
    }

    override fun toString(): String =
        "ClipboardPayload(text=<redacted>, isSensitive=$isSensitive, " +
            "characterCount=$characterCount, utf8ByteCount=$utf8ByteCount, " +
            "chunkCount=$chunkCount, warnings=$warnings)"
}

/** A non-empty UTF-16 range whose normalized text fits in one remote paste chunk. */
internal data class ClipboardChunkRange(
    val startIndex: Int,
    val endIndexExclusive: Int,
    val utf8ByteCount: Int,
)

object ClipboardPayloadAnalyzer {
    const val PASTE_CHUNK_LIMIT_BYTES = 1_024
    const val MAX_RETAINED_PASTE_BYTES = 64 * 1_024

    /**
     * Applies the retained-paste limit before copying or retaining text from an Android ingress.
     * Counting stops as soon as the normalized UTF-8 representation is known to be too large.
     */
    fun analyzeDirectPlainTextAtIngress(
        text: CharSequence,
        isSensitive: Boolean = false,
    ): ClipboardPayloadAnalysis {
        val snapshot = text.boundedNormalizedSnapshot(MAX_RETAINED_PASTE_BYTES)
            ?: return ClipboardPayloadAnalysis.TooLarge
        return ClipboardPayloadAnalysis.Accepted(
            ClipboardPayload(
                text = snapshot.text,
                isSensitive = isSensitive,
                characterCount = snapshot.characterCount,
                utf8ByteCount = snapshot.utf8ByteCount,
                warnings = snapshot.warnings,
                chunkRanges = snapshot.text.chunkRanges(PASTE_CHUNK_LIMIT_BYTES),
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
            "Clipboard text exceeds the ${MAX_RETAINED_PASTE_BYTES}-byte retained paste limit",
        )
    }

    private fun String.chunkRanges(limit: Int): List<ClipboardChunkRange> {
        if (isEmpty()) return emptyList()

        return buildList {
            var chunkStart = 0
            var chunkUtf8ByteCount = 0
            var index = 0
            while (index < length) {
                val first = this@chunkRanges[index]
                val codePoint = if (
                    first.isHighSurrogate() &&
                    index + 1 < length &&
                    this@chunkRanges[index + 1].isLowSurrogate()
                ) {
                    Character.toCodePoint(first, this@chunkRanges[index + 1])
                } else {
                    first.code
                }
                val consumedChars = Character.charCount(codePoint)
                val codePointUtf8ByteCount = codePoint.utf8ByteCount()

                if (chunkUtf8ByteCount + codePointUtf8ByteCount > limit) {
                    add(
                        ClipboardChunkRange(
                            startIndex = chunkStart,
                            endIndexExclusive = index,
                            utf8ByteCount = chunkUtf8ByteCount,
                        ),
                    )
                    chunkStart = index
                    chunkUtf8ByteCount = 0
                }

                chunkUtf8ByteCount += codePointUtf8ByteCount
                index += consumedChars
            }

            add(
                ClipboardChunkRange(
                    startIndex = chunkStart,
                    endIndexExclusive = length,
                    utf8ByteCount = chunkUtf8ByteCount,
                ),
            )
        }
    }

    private fun CharSequence.boundedNormalizedSnapshot(limit: Int): ClipboardTextSnapshot? {
        val normalized = StringBuilder(length.coerceAtMost(limit))
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
            normalized.appendCodePoint(normalizedCodePoint)
            characterCount++
            when {
                normalizedCodePoint == '\n'.code -> containsNewline = true
                normalizedCodePoint == '\t'.code -> containsTab = true
                Character.isISOControl(normalizedCodePoint) -> containsOtherControl = true
            }
            index += consumedChars
        }

        return ClipboardTextSnapshot(
            text = normalized.toString(),
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
        this in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> 1
        this <= 0xffff -> 3
        else -> 4
    }

    private data class ClipboardTextSnapshot(
        val text: String,
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
