package org.nanokvm.mobile.runtime

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import org.nanokvm.protocol.NanoKvmOfflineUpdatePackage

/** Document-neutral opener for one transient offline-update selection. */
internal fun interface NanoKvmOfflineUpdateStreamOpener {
    @Throws(IOException::class)
    fun open(): InputStream
}

/**
 * A validated, transient package selection.
 *
 * This object must never enter saved state or persistence. It contains no Android URI or path, and
 * its potentially URI-capturing opener is atomically removed by [consume] or [close].
 */
internal class NanoKvmOfflineUpdateSource private constructor(
    internal val packageVersion: String,
    internal val contentLength: Long,
    private val source: AtomicReference<SourceAccess?>,
) : AutoCloseable {
    internal fun consume(): NanoKvmOfflineUpdatePayload {
        val access = source.getAndSet(null)
            ?: throw IllegalStateException("Offline update source has already been consumed")
        return NanoKvmOfflineUpdatePayload(
            packageVersion = packageVersion,
            contentLength = contentLength,
            source = AtomicReference(access),
        )
    }

    override fun close() {
        source.set(null)
    }

    override fun toString(): String =
        "NanoKvmOfflineUpdateSource(version=$packageVersion, contentLength=$contentLength, " +
            "source=<redacted>)"

    internal companion object {
        private val FILE_NAME =
            Regex("nanokvm_([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})\\.tar\\.gz")

        fun create(
            fileName: String,
            contentLength: Long,
            opener: NanoKvmOfflineUpdateStreamOpener,
        ): NanoKvmOfflineUpdateSource {
            val match = FILE_NAME.matchEntire(fileName)
                ?: throw IllegalArgumentException(
                    "Offline update filename must match nanokvm_X.Y.Z.tar.gz",
                )
            require(fileName.length <= 64) { "Offline update filename is too long" }
            require(contentLength in 1..NanoKvmOfflineUpdatePackage.MAX_CONTENT_LENGTH_BYTES) {
                "Offline update content length is outside the supported bound"
            }
            val version = match.groupValues.drop(1).joinToString(".")
            return NanoKvmOfflineUpdateSource(
                packageVersion = version,
                contentLength = contentLength,
                source = AtomicReference(SourceAccess(fileName, opener)),
            )
        }
    }

    internal class SourceAccess(
        val fileName: String,
        val opener: NanoKvmOfflineUpdateStreamOpener,
    ) {
        override fun toString(): String = "SourceAccess(<redacted>)"
    }

    internal class NanoKvmOfflineUpdatePayload internal constructor(
        val packageVersion: String,
        val contentLength: Long,
        private val source: AtomicReference<SourceAccess?>,
    ) : AutoCloseable {
        internal fun consumeForProtocol(): NanoKvmOfflineUpdateProtocolSource {
            val access = source.getAndSet(null)
                ?: throw IllegalStateException("Offline update payload has already been consumed")
            return NanoKvmOfflineUpdateProtocolSource(
                fileName = access.fileName,
                contentLength = contentLength,
                opener = AtomicReference(access.opener),
            )
        }

        override fun close() {
            source.set(null)
        }

        override fun toString(): String =
            "NanoKvmOfflineUpdatePayload(version=$packageVersion, " +
                "contentLength=$contentLength, source=<redacted>)"
    }

    internal class NanoKvmOfflineUpdateProtocolSource internal constructor(
        val fileName: String,
        val contentLength: Long,
        private val opener: AtomicReference<NanoKvmOfflineUpdateStreamOpener?>,
    ) : AutoCloseable {
        @Throws(IOException::class)
        fun openOnce(): InputStream {
            val selected = opener.getAndSet(null)
                ?: throw IOException("Offline update document is no longer available")
            return try {
                selected.open()
            } catch (error: IOException) {
                throw error
            } catch (_: RuntimeException) {
                throw IOException("Offline update document is unavailable")
            }
        }

        override fun close() {
            opener.set(null)
        }

        override fun toString(): String =
            "NanoKvmOfflineUpdateProtocolSource(fileName=<redacted>, " +
                "contentLength=$contentLength, opener=<redacted>)"
    }
}

internal typealias NanoKvmOfflineUpdatePayload =
    NanoKvmOfflineUpdateSource.NanoKvmOfflineUpdatePayload

internal typealias NanoKvmOfflineUpdateProtocolSource =
    NanoKvmOfflineUpdateSource.NanoKvmOfflineUpdateProtocolSource
