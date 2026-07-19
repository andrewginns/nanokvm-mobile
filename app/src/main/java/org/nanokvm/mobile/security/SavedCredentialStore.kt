package org.nanokvm.mobile.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nanokvm.mobile.data.HostProfile

/**
 * Stores only Android-Keystore-encrypted credential envelopes in the app's no-backup area.
 * Local authentication is required by the non-exportable AES key before encryption/decryption.
 */
interface SavedCredentials {
    suspend fun hasCredential(profileId: String): Boolean
    suspend fun prepareToSave(profile: HostProfile)
    suspend fun stageCredential(profile: HostProfile, password: CharArray): StagedCredential
    suspend fun commit(stagedCredential: StagedCredential)
    suspend fun unlock(profile: HostProfile): CharArray
    suspend fun delete(profileId: String)
    suspend fun deleteAll()
}

class SavedCredentialStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SavedCredentials {
    private val credentialsDirectory = File(context.noBackupFilesDir, CREDENTIALS_DIRECTORY)

    override suspend fun hasCredential(profileId: String): Boolean = withContext(ioDispatcher) {
        val alias = keyAlias(profileId)
        credentialFile(profileId).isFile && keyStore().containsAlias(alias)
    }

    /** Creates an authentication-bound key before the system authentication prompt is shown. */
    override suspend fun prepareToSave(profile: HostProfile) = withContext(ioDispatcher) {
        getOrCreateKey(keyAlias(profile.id))
        Unit
    }

    /**
     * Encrypts a candidate password after device authentication, but does not persist it. The
     * caller commits the returned envelope only after the NanoKVM accepts the credentials.
     */
    override suspend fun stageCredential(
        profile: HostProfile,
        password: CharArray,
    ): StagedCredential = withContext(ioDispatcher) {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val alias = keyAlias(profile.id)
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: throw SavedCredentialUnavailableException("Credential key is missing")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val aad = credentialBinding(profile)
        val plaintext = try {
            encodePassword(password)
        } catch (error: Throwable) {
            aad.fill(0)
            throw error
        }
        val ciphertext = try {
            cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
            aad.fill(0)
        }
        val payload = try {
            encodeEnvelope(cipher.iv, ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        StagedCredential(profile.id, payload)
    }

    /** Atomically installs a successfully authenticated staged credential. */
    override suspend fun commit(stagedCredential: StagedCredential) = withContext(ioDispatcher) {
        check(keyStore().containsAlias(keyAlias(stagedCredential.profileId))) {
            "Credential key is unavailable"
        }
        val payload = stagedCredential.copyPayload()
        require(payload.size <= MAX_ENVELOPE_BYTES) { "Credential envelope is too large" }
        credentialsDirectory.mkdirs()
        check(credentialsDirectory.isDirectory) { "Credential directory is unavailable" }
        val atomicFile = AtomicFile(credentialFile(stagedCredential.profileId))
        val output = atomicFile.startWrite()
        try {
            output.write(payload)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        } finally {
            payload.fill(0)
        }
    }

    /** Decrypts a committed password after the device has authenticated the user. */
    override suspend fun unlock(profile: HostProfile): CharArray = withContext(ioDispatcher) {
        val alias = keyAlias(profile.id)
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: throw SavedCredentialUnavailableException("Credential key is missing")
        val payload = readCredentialPayload(profile.id)
        val envelope = try {
            decodeEnvelope(payload)
        } finally {
            payload.fill(0)
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val aad = credentialBinding(profile)
        val plaintext = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
            cipher.updateAAD(aad)
            cipher.doFinal(envelope.ciphertext)
        } finally {
            aad.fill(0)
            envelope.clear()
        }
        try {
            decodePassword(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Deletes both halves of a record and fails unless absence can be verified. */
    override suspend fun delete(profileId: String) = withContext(ioDispatcher) {
        val file = credentialFile(profileId)
        val alias = keyAlias(profileId)
        val failures = mutableListOf<Throwable>()
        runCatching { AtomicFile(file).delete() }.exceptionOrNull()?.let(failures::add)
        val store = runCatching { keyStore() }
            .getOrElse { error ->
                failures += error
                null
            }
        if (store != null) {
            runCatching {
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }.exceptionOrNull()?.let(failures::add)
        }
        val fileGone = !file.exists() && !File(file.path + ".bak").exists()
        val aliasGone = store != null && runCatching { !store.containsAlias(alias) }
            .getOrElse { error ->
                failures += error
                false
            }
        if (!fileGone || !aliasGone || failures.isNotEmpty()) {
            throw SavedCredentialDeletionException(
                "Android could not verify removal of the protected credential.",
                failures.firstOrNull(),
            )
        }
        Unit
    }

    /** Used by explicit profile-storage recovery so corrupt records cannot orphan credentials. */
    override suspend fun deleteAll() = withContext(ioDispatcher) {
        val failures = mutableListOf<Throwable>()
        credentialsDirectory.listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(".credential") || file.name.endsWith(".credential.bak")) {
                runCatching {
                    if (file.exists() && !file.delete()) error("Credential file remained: ${file.name}")
                }.exceptionOrNull()?.let(failures::add)
            }
        }
        val store = runCatching { keyStore() }.getOrElse { error ->
            failures += error
            null
        }
        if (store != null) {
            val aliases = runCatching { store.aliases().toList() }.getOrElse { error ->
                failures += error
                emptyList()
            }
            aliases.filter { it.startsWith(KEY_ALIAS_PREFIX) }.forEach { alias ->
                runCatching { store.deleteEntry(alias) }.exceptionOrNull()?.let(failures::add)
            }
        }
        val filesGone = credentialsDirectory.listFiles().orEmpty().none { file ->
            file.name.endsWith(".credential") || file.name.endsWith(".credential.bak")
        }
        val aliasesGone = store != null && runCatching {
            store.aliases().toList().none { it.startsWith(KEY_ALIAS_PREFIX) }
        }.getOrElse { error ->
            failures += error
            false
        }
        if (!filesGone || !aliasesGone || failures.isNotEmpty()) {
            throw SavedCredentialDeletionException(
                "Android could not verify removal of all protected credentials.",
                failures.firstOrNull(),
            )
        }
        Unit
    }

    private fun readCredentialPayload(profileId: String): ByteArray {
        val file = credentialFile(profileId)
        if (!file.isFile) throw SavedCredentialUnavailableException("Credential record is missing")
        val length = file.length()
        if (length !in 1..MAX_ENVELOPE_BYTES.toLong()) {
            throw SavedCredentialUnavailableException("Credential record is invalid")
        }
        return AtomicFile(file).openRead().use { it.readBytes() }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(AES_KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTHENTICATION_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTHENTICATION_VALIDITY_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(specification) }
            .generateKey()
    }

    private fun credentialFile(profileId: String): File =
        File(credentialsDirectory, "${profileHash(profileId)}.credential")

    private fun keyAlias(profileId: String): String = "$KEY_ALIAS_PREFIX${profileHash(profileId)}"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        internal const val AUTHENTICATION_VALIDITY_SECONDS = 10
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CREDENTIALS_DIRECTORY = "saved_credentials"
        private const val KEY_ALIAS_PREFIX = "org.nanokvm.mobile.saved-password."
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val MAX_ENVELOPE_BYTES = 65_536
        private const val MAX_PASSWORD_UTF8_BYTES = 64_000
        private val ENVELOPE_MAGIC = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'C'.code.toByte())
        private const val ENVELOPE_VERSION = 1

        internal fun profileHash(profileId: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(profileId.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        /**
         * Canonical login identity shared by credential binding and profile-change checks.
         * Host names are DNS-style case-insensitive; usernames remain case-sensitive.
         */
        internal fun credentialIdentity(profile: HostProfile): CredentialIdentity =
            CredentialIdentity(
                profileId = profile.id,
                scheme = if (profile.useHttps) "https" else "http",
                host = profile.host.trim().lowercase(Locale.ROOT),
                port = profile.port,
                username = profile.username.trim(),
            )

        internal fun credentialBinding(profile: HostProfile): ByteArray {
            val identity = credentialIdentity(profile)
            return buildString {
                append("nanokvm-mobile-credential-v2\n")
                append(identity.profileId)
                append('\n')
                append(identity.scheme)
                append('\n')
                append(identity.host)
                append('\n')
                append(identity.port)
                append('\n')
                append(identity.username)
            }.encodeToByteArray()
        }

        /** Encodes without ever materializing the password as an immutable [String]. */
        internal fun encodePassword(password: CharArray): ByteArray {
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            require(password.size <= MAX_PASSWORD_UTF8_BYTES) { "Password is too long" }
            val maximumBytes = (password.size * encoder.maxBytesPerChar())
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(MAX_PASSWORD_UTF8_BYTES)
            val scratch = ByteBuffer.allocate(maximumBytes)
            return try {
                val encodeResult = encoder.encode(CharBuffer.wrap(password), scratch, true)
                require(!encodeResult.isOverflow) { "Password is too long" }
                if (encodeResult.isError) encodeResult.throwException()
                val flushResult = encoder.flush(scratch)
                require(!flushResult.isOverflow) { "Password is too long" }
                if (flushResult.isError) flushResult.throwException()
                ByteArray(scratch.position()).also { encoded ->
                    scratch.flip()
                    scratch.get(encoded)
                }
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Password contains invalid text", error)
            } finally {
                scratch.array().fill(0)
            }
        }

        /** Decodes through a mutable character buffer whose backing array is always erased. */
        internal fun decodePassword(plaintext: ByteArray): CharArray {
            if (plaintext.size > MAX_PASSWORD_UTF8_BYTES) {
                throw SavedCredentialUnavailableException("Credential password is too large")
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val maximumChars = (plaintext.size * decoder.maxCharsPerByte())
                .toInt()
                .coerceAtLeast(1)
            val scratch = CharBuffer.allocate(maximumChars)
            return try {
                val decodeResult = decoder.decode(ByteBuffer.wrap(plaintext), scratch, true)
                if (decodeResult.isOverflow) {
                    throw SavedCredentialUnavailableException("Credential password is too large")
                }
                if (decodeResult.isError) decodeResult.throwException()
                val flushResult = decoder.flush(scratch)
                if (flushResult.isOverflow) {
                    throw SavedCredentialUnavailableException("Credential password is too large")
                }
                if (flushResult.isError) flushResult.throwException()
                CharArray(scratch.position()).also { decoded ->
                    scratch.flip()
                    scratch.get(decoded)
                }
            } catch (error: CharacterCodingException) {
                throw SavedCredentialUnavailableException(
                    "Credential password contains invalid text",
                    error,
                )
            } finally {
                scratch.array().fill('\u0000')
            }
        }

        internal fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            require(iv.size in 12..32) { "Unexpected GCM IV size" }
            require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
            return ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(ENVELOPE_MAGIC)
                    output.writeByte(ENVELOPE_VERSION)
                    output.writeByte(iv.size)
                    output.write(iv)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                }
                bytes.toByteArray()
            }
        }

        internal fun decodeEnvelope(payload: ByteArray): CredentialEnvelope {
            if (payload.size > MAX_ENVELOPE_BYTES) {
                throw SavedCredentialUnavailableException("Credential record is too large")
            }
            return runCatching {
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    val magic = ByteArray(ENVELOPE_MAGIC.size).also(input::readFully)
                    if (!magic.contentEquals(ENVELOPE_MAGIC)) error("Unexpected credential record")
                    if (input.readUnsignedByte() != ENVELOPE_VERSION) error("Unsupported credential record")
                    val ivSize = input.readUnsignedByte()
                    if (ivSize !in 12..32) error("Invalid credential IV")
                    val iv = ByteArray(ivSize).also(input::readFully)
                    val ciphertextSize = input.readInt()
                    if (ciphertextSize !in 1..MAX_ENVELOPE_BYTES) error("Invalid credential payload")
                    val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
                    if (input.available() != 0) error("Unexpected trailing credential data")
                    CredentialEnvelope(iv, ciphertext)
                }
            }.getOrElse { error ->
                throw SavedCredentialUnavailableException("Credential record is invalid", error)
            }
        }
    }
}

internal data class CredentialIdentity(
    val profileId: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String,
)

class StagedCredential internal constructor(
    val profileId: String,
    private val payload: ByteArray,
) {
    internal fun copyPayload(): ByteArray = payload.copyOf()

    fun clear() {
        payload.fill(0)
    }
}

internal class CredentialEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun clear() {
        iv.fill(0)
        ciphertext.fill(0)
    }
}

class SavedCredentialUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SavedCredentialDeletionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
