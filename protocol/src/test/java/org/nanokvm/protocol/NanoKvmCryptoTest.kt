package org.nanokvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NanoKvmCryptoTest {
    @Test
    fun `matches independent OpenSSL EVP BytesToKey vector`() {
        val encrypted = NanoKvmPasswordCipher.encrypt(
            "correct horse battery staple".toCharArray(),
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
        )

        assertEquals(
            "U2FsdGVkX18AAQIDBAUGB99Ve09RK6cnCykCM56hghX1FSJaRaCGpiUXipgb%2F%2BFj",
            encrypted,
        )
        assertEquals(
            "correct horse battery staple",
            NanoKvmPasswordCipher.decryptForCompatibilityTest(encrypted),
        )
    }

    @Test
    fun `random salt produces a different wire value`() {
        val firstPassword = "secret".toCharArray()
        val secondPassword = "secret".toCharArray()
        val first = NanoKvmPasswordCipher.encrypt(firstPassword)
        val second = NanoKvmPasswordCipher.encrypt(secondPassword)
        firstPassword.fill('\u0000')
        secondPassword.fill('\u0000')

        assertNotEquals(first, second)
    }

    @Test
    fun `mutable password encoding rejects malformed UTF-16 without a String conversion`() {
        val malformed = charArrayOf('\ud800')

        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmPasswordCipher.encrypt(
                malformed,
                byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            )
        }
        malformed.fill('\u0000')
    }
}
