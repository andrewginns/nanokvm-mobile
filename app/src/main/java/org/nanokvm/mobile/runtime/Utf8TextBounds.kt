package org.nanokvm.mobile.runtime

/**
 * Returns the exact UTF-8 byte count when it is within [limit], without allocating an encoded
 * copy. Unpaired UTF-16 surrogates use the same replacement width as Kotlin's UTF-8 encoder.
 */
internal fun String.utf8SizeAtMost(limit: Int): Int? =
    boundedUtf8Size(limit, requireSafeScalarText = false)

/**
 * As [utf8SizeAtMost], while also requiring Unicode scalar text whose only control characters
 * are tab and line endings. This is the appliance autostart-editor policy.
 */
internal fun String.safeScalarTextUtf8SizeAtMost(limit: Int): Int? =
    boundedUtf8Size(limit, requireSafeScalarText = true)

private fun String.boundedUtf8Size(
    limit: Int,
    requireSafeScalarText: Boolean,
): Int? {
    require(limit >= 0) { "UTF-8 byte limit must not be negative" }
    var byteCount = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        val scalarBytes = when {
            character.isHighSurrogate() -> {
                if (index + 1 < length && this[index + 1].isLowSurrogate()) {
                    index++
                    4
                } else {
                    if (requireSafeScalarText) return null
                    INVALID_UTF16_CODE_UNIT_BYTES
                }
            }
            character.isLowSurrogate() -> {
                if (requireSafeScalarText) return null
                INVALID_UTF16_CODE_UNIT_BYTES
            }
            requireSafeScalarText && character.isISOControl() &&
                character != '\t' && character != '\n' && character != '\r' -> return null
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            else -> 3
        }
        if (byteCount > limit - scalarBytes) return null
        byteCount += scalarBytes
        index++
    }
    return byteCount
}

private val INVALID_UTF16_CODE_UNIT_BYTES: Int = "\uD800".encodeToByteArray().size
