package org.nanokvm.protocol

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** CryptoJS/OpenSSL-compatible password protection used by NanoKVM's login endpoint. */
object NanoKvmPasswordCipher {
    private const val PASSPHRASE = "nanokvm-sipeed-2024"
    private val OPENSSL_PREFIX = "Salted__".encodeToByteArray()

    /**
     * Encrypts [password] using AES-256-CBC and the legacy OpenSSL EVP_BytesToKey MD5 KDF.
     *
     * The returned value is Base64 encoded and then URI-component encoded exactly like the
     * NanoKVM web client. [salt] is injectable so compatibility can be tested deterministically.
     */
    @JvmStatic
    @JvmOverloads
    fun encrypt(
        password: CharArray,
        salt: ByteArray = ByteArray(8).also(SecureRandom()::nextBytes),
    ): String {
        require(salt.size == 8) { "OpenSSL-compatible salt must contain exactly 8 bytes" }

        val passwordBytes = encodePassword(password)
        val passphraseBytes = PASSPHRASE.encodeToByteArray()
        val (key, iv) = deriveKeyAndIv(passphraseBytes, salt)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(passwordBytes)
            val payload = OPENSSL_PREFIX + salt + encrypted
            uriEncodeBase64(java.util.Base64.getEncoder().encodeToString(payload))
        } finally {
            passwordBytes.fill(0)
            passphraseBytes.fill(0)
            key.fill(0)
            iv.fill(0)
        }
    }

    internal fun decryptForCompatibilityTest(encoded: String): String {
        val payload = java.util.Base64.getDecoder().decode(uriDecodeBase64(encoded))
        require(payload.size >= 16 && payload.copyOfRange(0, 8).contentEquals(OPENSSL_PREFIX)) {
            "Ciphertext is not in OpenSSL salted format"
        }
        val salt = payload.copyOfRange(8, 16)
        val passphraseBytes = PASSPHRASE.encodeToByteArray()
        val (key, iv) = deriveKeyAndIv(passphraseBytes, salt)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(payload.copyOfRange(16, payload.size)).decodeToString()
        } finally {
            passphraseBytes.fill(0)
            key.fill(0)
            iv.fill(0)
        }
    }

    private fun deriveKeyAndIv(passphrase: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ByteArray(48)
        var produced = 0
        var previous = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")

        while (produced < output.size) {
            md5.reset()
            if (previous.isNotEmpty()) md5.update(previous)
            md5.update(passphrase)
            md5.update(salt)
            val block = md5.digest()
            val count = minOf(block.size, output.size - produced)
            block.copyInto(output, destinationOffset = produced, endIndex = count)
            produced += count
            previous.fill(0)
            previous = block
        }
        previous.fill(0)

        return output.copyOfRange(0, 32) to output.copyOfRange(32, 48).also { output.fill(0) }
    }

    /** UTF-8 encodes from mutable input without creating an immutable plaintext String. */
    private fun encodePassword(password: CharArray): ByteArray {
        require(password.size <= MAX_PASSWORD_CHARS) { "Password is too long" }
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val maximumBytes = (password.size * encoder.maxBytesPerChar())
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_PASSWORD_UTF8_BYTES)
        val scratch = ByteBuffer.allocate(maximumBytes)
        return try {
            val encoded = encoder.encode(CharBuffer.wrap(password), scratch, true)
            require(!encoded.isOverflow) { "Password is too long" }
            if (encoded.isError) encoded.throwException()
            val flushed = encoder.flush(scratch)
            require(!flushed.isOverflow) { "Password is too long" }
            if (flushed.isError) flushed.throwException()
            ByteArray(scratch.position()).also { result ->
                scratch.flip()
                scratch.get(result)
            }
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Password contains invalid text", error)
        } finally {
            scratch.array().fill(0)
        }
    }

    private fun uriEncodeBase64(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '+' -> append("%2B")
                '/' -> append("%2F")
                '=' -> append("%3D")
                else -> append(character)
            }
        }
    }

    private fun uriDecodeBase64(value: String): String = value
        .replace("%2B", "+", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private const val MAX_PASSWORD_CHARS = 64_000
    private const val MAX_PASSWORD_UTF8_BYTES = 64_000
}
