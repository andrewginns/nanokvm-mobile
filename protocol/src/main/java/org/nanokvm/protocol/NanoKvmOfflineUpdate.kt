package org.nanokvm.protocol

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Opens a fresh byte stream for one local NanoKVM application package. */
public fun interface NanoKvmOfflineUpdateStream {
    /**
     * Opens the package stream. The protocol client closes the returned stream after its one
     * upload attempt. Implementations should not retain credentials or expose a source path in
     * thrown exception messages.
     */
    @Throws(IOException::class)
    public fun open(): InputStream
}

/**
 * A validated, single-use NanoKVM application package.
 *
 * This type deliberately accepts neither an Android `Uri` nor a filesystem path. Platform code
 * owns document-provider access and supplies only a length plus a stream opener. The opener is
 * released when the package is consumed, and the same package object cannot authorize a replay
 * after cancellation, timeout, disconnect, or process-level workflow restoration.
 */
public class NanoKvmOfflineUpdatePackage private constructor(
    public val fileName: String,
    public val contentLength: Long,
    stream: NanoKvmOfflineUpdateStream,
) {
    private val stream = AtomicReference(stream)

    internal fun consume(): NanoKvmOfflineUpdateStream =
        stream.getAndSet(null)
            ?: throw IllegalStateException("Offline update package has already been consumed")

    override fun toString(): String =
        "NanoKvmOfflineUpdatePackage(fileName=$fileName, contentLength=$contentLength, stream=<redacted>)"

    public companion object {
        /** A defensive client-side ceiling; official packages are substantially smaller. */
        public const val MAX_CONTENT_LENGTH_BYTES: Long = 256L * 1024L * 1024L

        /**
         * Validates metadata without opening or buffering [stream]. The stream is opened at most
         * once, only when [NanoKvmApi.startOfflineUpdate] writes the request body.
         */
        @JvmStatic
        public fun create(
            fileName: String,
            contentLength: Long,
            stream: NanoKvmOfflineUpdateStream,
        ): NanoKvmOfflineUpdatePackage {
            require(OFFLINE_UPDATE_FILENAME.matches(fileName)) {
                "Offline update filename must match nanokvm_X.Y.Z.tar.gz"
            }
            require(fileName.length <= MAX_OFFLINE_UPDATE_FILENAME_ASCII_BYTES) {
                "Offline update filename exceeds $MAX_OFFLINE_UPDATE_FILENAME_ASCII_BYTES bytes"
            }
            require(contentLength in 1..MAX_CONTENT_LENGTH_BYTES) {
                "Offline update content length must be between 1 and $MAX_CONTENT_LENGTH_BYTES bytes"
            }
            return NanoKvmOfflineUpdatePackage(fileName, contentLength, stream)
        }
    }
}

/** Upload progress containing byte counts only—never a path, URI, token, or server response. */
public data class NanoKvmOfflineUpdateProgress(
    public val bytesTransferred: Long,
    public val totalBytes: Long,
) {
    init {
        require(totalBytes > 0) { "Total bytes must be positive" }
        require(bytesTransferred in 0..totalBytes) { "Transferred bytes are outside the package" }
    }
}

/** Receipt for an acknowledged install request. NanoKVM normally restarts immediately afterward. */
public data class NanoKvmOfflineUpdateReceipt(
    public val fileName: String,
    public val uploadedBytes: Long,
)

/** Safe, bounded classifications for a failed or uncertain offline-update attempt. */
public sealed interface NanoKvmOfflineUpdateFailure {
    /** Whether reconnect-and-readback is required before any fresh user decision. */
    public val outcomeUnknown: Boolean

    /** The local stream could not be opened or read; no path or provider error is retained. */
    public data object LocalSourceUnavailable : NanoKvmOfflineUpdateFailure {
        override val outcomeUnknown: Boolean = false
    }

    /**
     * The appliance returned a JSON API rejection. Server text is deliberately not retained, so
     * preflight rejection cannot be distinguished safely from a failure during installation.
     */
    public data class ApiRejected(public val code: Int) : NanoKvmOfflineUpdateFailure {
        override val outcomeUnknown: Boolean = true
    }

    /** The appliance returned a non-success HTTP status. Response content is not retained. */
    public data class HttpRejected(
        public val statusCode: Int,
        override val outcomeUnknown: Boolean,
    ) : NanoKvmOfflineUpdateFailure

    /** A successful HTTP response had no trustworthy NanoKVM envelope. */
    public data object InvalidResponseOutcomeUnknown : NanoKvmOfflineUpdateFailure {
        override val outcomeUnknown: Boolean = true
    }

    /** The connection failed after dispatch; the package may already be installing. */
    public data object TransportOutcomeUnknown : NanoKvmOfflineUpdateFailure {
        override val outcomeUnknown: Boolean = true
    }
}

/**
 * Offline-update failure with no retained server body, local path, URI, or low-level exception.
 */
public class NanoKvmOfflineUpdateException internal constructor(
    public val failure: NanoKvmOfflineUpdateFailure,
) : NanoKvmException(failure.safeMessage())

internal class NanoKvmOfflineUpdateRequestBody(
    private val source: NanoKvmOfflineUpdateStream,
    private val length: Long,
    private val progress: (NanoKvmOfflineUpdateProgress) -> Unit,
    private val mediaType: MediaType,
) : RequestBody() {
    private val writeAuthorization = AtomicReference(Unit)
    private val cancelled = AtomicBoolean(false)
    private val activeInput = AtomicReference<InputStream?>(null)

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    /** Prevents authentication follow-ups, redirects, and custom interceptors from replaying it. */
    override fun isOneShot(): Boolean = true

    /** Closes a provider stream that may otherwise remain blocked after coroutine cancellation. */
    internal fun cancelSource() {
        cancelled.set(true)
        activeInput.getAndSet(null)?.closeWithoutReporting()
    }

    override fun writeTo(sink: BufferedSink) {
        check(writeAuthorization.getAndSet(null) != null) {
            "Offline update request body cannot be replayed"
        }
        notifyProgress(0)

        val input = try {
            source.open()
        } catch (_: IOException) {
            throw OfflineUpdateSourceIOException()
        } catch (_: RuntimeException) {
            throw OfflineUpdateSourceIOException()
        }
        if (!activeInput.compareAndSet(null, input)) {
            input.closeWithoutReporting()
            throw OfflineUpdateSourceIOException()
        }
        if (cancelled.get()) {
            activeInput.compareAndSet(input, null)
            input.closeWithoutReporting()
            throw OfflineUpdateSourceIOException()
        }

        try {
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var transferred = 0L
            while (transferred < length) {
                val remaining = length - transferred
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val read = try {
                    input.read(buffer, 0, requested)
                } catch (_: IOException) {
                    throw OfflineUpdateSourceIOException()
                } catch (_: RuntimeException) {
                    throw OfflineUpdateSourceIOException()
                }
                if (read < 0) throw OfflineUpdateSourceIOException()
                if (read == 0) throw OfflineUpdateSourceIOException()
                if (read > requested) throw OfflineUpdateSourceIOException()

                // Sink failures are left as transport I/O so callers receive the deliberately
                // ambiguous classification instead of a misleading local-source error.
                sink.write(buffer, 0, read)
                transferred += read
                if (transferred < length) notifyProgress(transferred)
            }

            val extra = try {
                input.read()
            } catch (_: IOException) {
                throw OfflineUpdateSourceIOException()
            } catch (_: RuntimeException) {
                throw OfflineUpdateSourceIOException()
            }
            if (extra >= 0) throw OfflineUpdateSourceIOException()
            notifyProgress(length)
        } finally {
            activeInput.compareAndSet(input, null)
            input.closeWithoutReporting()
        }
    }

    private fun notifyProgress(transferred: Long) {
        try {
            progress(NanoKvmOfflineUpdateProgress(transferred, length))
        } catch (_: RuntimeException) {
            // Progress is observational. A callback bug must not interrupt an install mid-upload.
        }
    }
}

internal class OfflineUpdateSourceIOException : IOException("Offline update source is unavailable")

private fun InputStream.closeWithoutReporting() {
    try {
        close()
    } catch (_: IOException) {
        // Closing a caller-owned stream cannot change bytes already dispatched.
    } catch (_: RuntimeException) {
        // Provider-specific close failures are likewise non-authoritative and redacted.
    }
}

private fun NanoKvmOfflineUpdateFailure.safeMessage(): String = when (this) {
    NanoKvmOfflineUpdateFailure.LocalSourceUnavailable ->
        "Offline update package source is unavailable"
    is NanoKvmOfflineUpdateFailure.ApiRejected ->
        "NanoKVM reported an offline-update failure (API code $code); outcome is unknown"
    is NanoKvmOfflineUpdateFailure.HttpRejected ->
        "NanoKVM returned HTTP $statusCode for the offline update"
    NanoKvmOfflineUpdateFailure.InvalidResponseOutcomeUnknown ->
        "NanoKVM returned an invalid offline-update response; outcome is unknown"
    NanoKvmOfflineUpdateFailure.TransportOutcomeUnknown ->
        "Offline-update connection failed after dispatch; outcome is unknown"
}

private val OFFLINE_UPDATE_FILENAME: Regex =
    Regex("nanokvm_[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}\\.tar\\.gz")
private const val MAX_OFFLINE_UPDATE_FILENAME_ASCII_BYTES: Int = 64
private const val STREAM_BUFFER_BYTES: Int = 32 * 1024
