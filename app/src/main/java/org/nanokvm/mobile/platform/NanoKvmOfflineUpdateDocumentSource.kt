package org.nanokvm.mobile.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateSource
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateStreamOpener

/** Redacted document-selection failures safe for UI state or diagnostics. */
internal enum class NanoKvmOfflineUpdateDocumentError {
    NOT_A_CONTENT_DOCUMENT,
    METADATA_UNAVAILABLE,
    INVALID_FILE_NAME,
    INVALID_CONTENT_LENGTH,
    PROVIDER_UNAVAILABLE,
}

internal sealed interface NanoKvmOfflineUpdateDocumentSelectionResult {
    class Success internal constructor(
        val source: NanoKvmOfflineUpdateSource,
    ) : NanoKvmOfflineUpdateDocumentSelectionResult {
        override fun toString(): String = "Success(source=<redacted>)"
    }

    data class Failure(
        val error: NanoKvmOfflineUpdateDocumentError,
    ) : NanoKvmOfflineUpdateDocumentSelectionResult
}

/**
 * Converts one temporary Storage Access Framework grant into a transient, one-shot source.
 *
 * This adapter never requests or persists URI permission. The URI exists only inside the opener
 * held by the transient source and is dropped on clear/consume; no path or provider text escapes.
 */
internal object NanoKvmOfflineUpdateDocumentSource {
    fun select(
        contentResolver: ContentResolver,
        uri: Uri,
    ): NanoKvmOfflineUpdateDocumentSelectionResult {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return failure(NanoKvmOfflineUpdateDocumentError.NOT_A_CONTENT_DOCUMENT)
        }

        val metadata = try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex < 0 || sizeIndex < 0 || cursor.isNull(nameIndex) ||
                    cursor.isNull(sizeIndex)
                ) {
                    return@use null
                }
                val fileName = cursor.getString(nameIndex) ?: return@use null
                DocumentMetadata(
                    fileName = fileName,
                    contentLength = cursor.getLong(sizeIndex),
                )
            }
        } catch (_: RuntimeException) {
            return failure(NanoKvmOfflineUpdateDocumentError.PROVIDER_UNAVAILABLE)
        } ?: return failure(NanoKvmOfflineUpdateDocumentError.METADATA_UNAVAILABLE)

        val source = try {
            NanoKvmOfflineUpdateSource.create(
                fileName = metadata.fileName,
                contentLength = metadata.contentLength,
                opener = NanoKvmOfflineUpdateStreamOpener {
                    try {
                        contentResolver.openInputStream(uri)
                            ?: throw IOException("Document provider returned no stream")
                    } catch (error: IOException) {
                        throw error
                    } catch (_: RuntimeException) {
                        throw IOException("Document provider is unavailable")
                    }
                },
            )
        } catch (_: IllegalArgumentException) {
            val error = if (metadata.fileName.matches(FILE_NAME_SHAPE)) {
                NanoKvmOfflineUpdateDocumentError.INVALID_CONTENT_LENGTH
            } else {
                NanoKvmOfflineUpdateDocumentError.INVALID_FILE_NAME
            }
            return failure(error)
        }
        return NanoKvmOfflineUpdateDocumentSelectionResult.Success(source)
    }

    private fun failure(
        error: NanoKvmOfflineUpdateDocumentError,
    ): NanoKvmOfflineUpdateDocumentSelectionResult.Failure =
        NanoKvmOfflineUpdateDocumentSelectionResult.Failure(error)

    private data class DocumentMetadata(
        val fileName: String,
        val contentLength: Long,
    )

    private val FILE_NAME_SHAPE =
        Regex("nanokvm_[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}\\.tar\\.gz")
}
