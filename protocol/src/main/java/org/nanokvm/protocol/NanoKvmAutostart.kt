package org.nanokvm.protocol

import java.io.Closeable
import kotlinx.serialization.Serializable

/** An opaque safe basename from one exact autostart-list snapshot. */
class NanoKvmAutostartScript internal constructor(
    val name: String,
) {
    val extension: String
        get() = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    override fun toString(): String = name
}

/** Immutable autostart-list snapshot; mutations additionally require it to be the latest read. */
class NanoKvmAutostartCatalog internal constructor(
    scripts: List<NanoKvmAutostartScript>,
) {
    val scripts: List<NanoKvmAutostartScript> = scripts.toList()

    fun find(name: String): NanoKvmAutostartScript? = scripts.firstOrNull { it.name == name }

    internal fun requireExactMember(script: NanoKvmAutostartScript) {
        require(scripts.any { it === script }) {
            "Autostart script must be an exact handle from the supplied catalog"
        }
    }
}

/**
 * Bounded root-equivalent script text read from the appliance.
 *
 * The exposed copy is mutable so its owner can clear it. Close this object to clear the retained
 * buffer. Its incidental string form never contains content.
 */
class NanoKvmAutostartContent internal constructor(
    private val ownedBytes: ByteArray,
) : Closeable {
    private var closed: Boolean = false

    val byteCount: Int
        get() = ownedBytes.size

    @Synchronized
    fun copyBytes(): ByteArray {
        check(!closed) { "Autostart content has been closed" }
        return ownedBytes.copyOf()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedBytes.fill(0)
    }

    @Synchronized
    override fun toString(): String =
        "NanoKvmAutostartContent(byteCount=$byteCount, content=<redacted>, closed=$closed)"
}

/**
 * Single-use, owned root-equivalent content for one create or update request.
 *
 * [takeOwnership] clears the supplied array immediately after taking a private copy. The private
 * bytes are cleared after request serialization or [close].
 */
class NanoKvmAutostartWriteContent private constructor(
    private val ownedBytes: ByteArray,
) : Closeable {
    private var consumed: Boolean = false

    val byteCount: Int = ownedBytes.size

    @Synchronized
    internal fun consumeJsonBody(): NanoKvmAutostartJsonBody {
        check(!consumed) { "Autostart content has already been consumed" }
        consumed = true
        return try {
            NanoKvmAutostartJsonBody(
                bytes = encodeAutostartJsonBody(ownedBytes),
                contentByteCount = byteCount,
            )
        } finally {
            ownedBytes.fill(0)
        }
    }

    @Synchronized
    override fun close() {
        consumed = true
        ownedBytes.fill(0)
    }

    @Synchronized
    override fun toString(): String =
        "NanoKvmAutostartWriteContent(byteCount=$byteCount, content=<redacted>, consumed=$consumed)"

    companion object {
        /** Takes a private copy, clears [content], and validates bounded UTF-8 script text. */
        @JvmStatic
        fun takeOwnership(content: ByteArray): NanoKvmAutostartWriteContent {
            if (content.isEmpty() || content.size > MAX_AUTOSTART_CONTENT_BYTES) {
                content.fill(0)
                throw IllegalArgumentException(
                    "Autostart content must contain 1..$MAX_AUTOSTART_CONTENT_BYTES UTF-8 bytes",
                )
            }
            val retained = try {
                content.copyOf()
            } finally {
                content.fill(0)
            }
            try {
                validateAutostartTextBytes(retained, allowEmpty = false)
                return NanoKvmAutostartWriteContent(retained)
            } catch (error: IllegalArgumentException) {
                retained.fill(0)
                throw error
            }
        }
    }
}

internal class NanoKvmAutostartJsonBody(
    val bytes: ByteArray,
    val contentByteCount: Int,
) : Closeable {
    override fun close() {
        bytes.fill(0)
    }
}

enum class NanoKvmAutostartWriteKind {
    CREATE,
    UPDATE,
}

/** A write receipt, not a reusable mutation handle. List again after any write. */
data class NanoKvmAutostartWriteReceipt(
    val fileName: String,
    val byteCount: Int,
    val kind: NanoKvmAutostartWriteKind,
)

enum class NanoKvmAutostartOperation {
    LIST,
    READ,
    CREATE,
    UPDATE,
    DELETE,
}

/** Redacted failure metadata; server bodies, script text, and server messages are never retained. */
sealed interface NanoKvmAutostartFailure {
    data class Api(val code: Int) : NanoKvmAutostartFailure
    data class Http(val statusCode: Int) : NanoKvmAutostartFailure
    data object InvalidResponse : NanoKvmAutostartFailure
    data object Transport : NanoKvmAutostartFailure
}

class NanoKvmAutostartOperationException(
    val operation: NanoKvmAutostartOperation,
    val failure: NanoKvmAutostartFailure,
) : NanoKvmException(
    "NanoKVM autostart ${operation.name.lowercase()} failed (${failure.redactedLabel()})",
)

@Serializable
internal data class AutostartListResponse(
    // Empty autostart directories are returned by the Go server as a nil slice (JSON null).
    val files: List<String>?,
)

internal fun AutostartListResponse.toValidatedCatalog(): NanoKvmAutostartCatalog =
    invalidAutostartResponse {
        val normalizedFiles = files.orEmpty()
        require(normalizedFiles.size <= MAX_AUTOSTART_SCRIPT_COUNT) {
            "Autostart list exceeds $MAX_AUTOSTART_SCRIPT_COUNT entries"
        }
        val names = normalizedFiles.map(::validateAutostartBasename)
        require(names.toSet().size == names.size) { "Autostart list contains duplicate basenames" }
        NanoKvmAutostartCatalog(names.map(::NanoKvmAutostartScript))
    }

internal fun autostartContentFromResponse(content: String): NanoKvmAutostartContent =
    invalidAutostartResponse {
        require(content.hasOnlyUnicodeScalarValues()) {
            "Autostart content contains an unpaired surrogate"
        }
        require(content.hasBoundedUtf8Length(MAX_AUTOSTART_CONTENT_BYTES)) {
            "Autostart content exceeds $MAX_AUTOSTART_CONTENT_BYTES UTF-8 bytes"
        }
        val bytes = content.encodeToByteArray()
        try {
            validateAutostartTextBytes(bytes, allowEmpty = true)
            NanoKvmAutostartContent(bytes)
        } catch (error: IllegalArgumentException) {
            bytes.fill(0)
            throw error
        }
    }

internal fun validateAutostartWriteResponse(expectedName: String, returnedName: String): String =
    invalidAutostartResponse {
        val validatedName = validateAutostartBasename(returnedName)
        require(validatedName == expectedName) {
            "NanoKVM returned a different autostart basename"
        }
        validatedName
    }

internal fun validateAutostartBasename(value: String): String {
    require(value.hasBoundedUtf8Length(MAX_AUTOSTART_BASENAME_UTF8_BYTES)) {
        "Autostart basename is too long"
    }
    require(SAFE_AUTOSTART_BASENAME.matches(value) && ".." !in value) {
        "Autostart script must be a safe .sh or .py basename"
    }
    return value
}

private fun validateAutostartTextBytes(bytes: ByteArray, allowEmpty: Boolean) {
    require(allowEmpty || bytes.isNotEmpty()) { "Autostart content must not be empty" }
    require(bytes.size <= MAX_AUTOSTART_CONTENT_BYTES) { "Autostart content is too large" }
    require(bytes.isStrictUtf8()) { "Autostart content must be valid UTF-8" }
    require(bytes.all { byte ->
        val value = byte.toInt() and 0xff
        value >= 0x20 || value == 0x09 || value == 0x0a || value == 0x0d
    }) {
        "Autostart content contains unsupported control bytes"
    }
    require(bytes.none { (it.toInt() and 0xff) == 0x7f }) {
        "Autostart content contains an unsupported control byte"
    }
}

private fun ByteArray.isStrictUtf8(): Boolean {
    var index = 0
    while (index < size) {
        val first = this[index].toInt() and 0xff
        when {
            first <= 0x7f -> index++
            first in 0xc2..0xdf -> {
                if (!hasContinuation(index + 1)) return false
                index += 2
            }
            first == 0xe0 -> {
                if (!hasByteIn(index + 1, 0xa0..0xbf) || !hasContinuation(index + 2)) return false
                index += 3
            }
            first in 0xe1..0xec || first in 0xee..0xef -> {
                if (!hasContinuation(index + 1) || !hasContinuation(index + 2)) return false
                index += 3
            }
            first == 0xed -> {
                if (!hasByteIn(index + 1, 0x80..0x9f) || !hasContinuation(index + 2)) return false
                index += 3
            }
            first == 0xf0 -> {
                if (
                    !hasByteIn(index + 1, 0x90..0xbf) ||
                    !hasContinuation(index + 2) ||
                    !hasContinuation(index + 3)
                ) {
                    return false
                }
                index += 4
            }
            first in 0xf1..0xf3 -> {
                if (
                    !hasContinuation(index + 1) ||
                    !hasContinuation(index + 2) ||
                    !hasContinuation(index + 3)
                ) {
                    return false
                }
                index += 4
            }
            first == 0xf4 -> {
                if (
                    !hasByteIn(index + 1, 0x80..0x8f) ||
                    !hasContinuation(index + 2) ||
                    !hasContinuation(index + 3)
                ) {
                    return false
                }
                index += 4
            }
            else -> return false
        }
    }
    return true
}

private fun ByteArray.hasContinuation(index: Int): Boolean =
    hasByteIn(index, 0x80..0xbf)

private fun ByteArray.hasByteIn(index: Int, range: IntRange): Boolean =
    index < size && (this[index].toInt() and 0xff) in range

private fun String.hasOnlyUnicodeScalarValues(): Boolean {
    var index = 0
    while (index < length) {
        val value = this[index]
        when {
            value.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            value.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun encodeAutostartJsonBody(content: ByteArray): ByteArray {
    var escapedLength = 0
    content.forEach { byte ->
        escapedLength += when (byte.toInt() and 0xff) {
            0x09, 0x0a, 0x0d, 0x22, 0x5c -> 2
            else -> 1
        }
    }
    val result = ByteArray(AUTOSTART_JSON_PREFIX.size + escapedLength + AUTOSTART_JSON_SUFFIX.size)
    var output = 0
    AUTOSTART_JSON_PREFIX.copyInto(result, output)
    output += AUTOSTART_JSON_PREFIX.size
    content.forEach { byte ->
        when (byte.toInt() and 0xff) {
            0x09 -> {
                result[output++] = BACKSLASH
                result[output++] = 't'.code.toByte()
            }
            0x0a -> {
                result[output++] = BACKSLASH
                result[output++] = 'n'.code.toByte()
            }
            0x0d -> {
                result[output++] = BACKSLASH
                result[output++] = 'r'.code.toByte()
            }
            0x22 -> {
                result[output++] = BACKSLASH
                result[output++] = QUOTE
            }
            0x5c -> {
                result[output++] = BACKSLASH
                result[output++] = BACKSLASH
            }
            else -> result[output++] = byte
        }
    }
    AUTOSTART_JSON_SUFFIX.copyInto(result, output)
    return result
}

private fun NanoKvmAutostartFailure.redactedLabel(): String = when (this) {
    is NanoKvmAutostartFailure.Api -> "API $code"
    is NanoKvmAutostartFailure.Http -> "HTTP $statusCode"
    NanoKvmAutostartFailure.InvalidResponse -> "invalid response"
    NanoKvmAutostartFailure.Transport -> "transport"
}

private inline fun <Result> invalidAutostartResponse(block: () -> Result): Result = try {
    block()
} catch (error: IllegalArgumentException) {
    throw InvalidApiResponseException("NanoKVM returned invalid autostart data")
}

private val SAFE_AUTOSTART_BASENAME = Regex(
    pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:sh|py)",
    option = RegexOption.IGNORE_CASE,
)

private val AUTOSTART_JSON_PREFIX = "{\"content\":\"".encodeToByteArray()
private val AUTOSTART_JSON_SUFFIX = "\"}".encodeToByteArray()
private val BACKSLASH: Byte = '\\'.code.toByte()
private val QUOTE: Byte = '"'.code.toByte()

internal const val MAX_AUTOSTART_SCRIPT_COUNT: Int = 512
internal const val MAX_AUTOSTART_BASENAME_UTF8_BYTES: Int = 255
const val MAX_AUTOSTART_CONTENT_BYTES: Int = 256 * 1024
