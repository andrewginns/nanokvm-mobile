package org.nanokvm.mobile.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nanokvm.mobile.BuildConfig
import org.nanokvm.mobile.R
import java.security.MessageDigest

private const val AboutDocumentChunkCharacters = 4_000

internal data class AboutReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val exactSourceUrl: String,
    val signingCertificateSha256: List<String>,
    val isDevelopmentBuild: Boolean,
)

internal enum class BundledAboutDocument(
    @StringRes val titleResource: Int,
    val assetPath: String,
    val testTag: String,
) {
    Gpl(
        R.string.about_document_gpl,
        "about/GPL-3.0-or-later.txt",
        "about-document-gpl",
    ),
    ProjectNotice(
        R.string.about_document_project_notice,
        "about/NOTICE.txt",
        "about-document-project-notice",
    ),
    ThirdPartyNotices(
        R.string.about_document_third_party,
        "about/THIRD_PARTY_NOTICES.md",
        "about-document-third-party",
    ),
    WebRtcWrapperLicense(
        R.string.about_document_webrtc_wrapper,
        "open_source_licenses/WEBRTC_SDK_ANDROID_LICENSE.txt",
        "about-document-webrtc-wrapper",
    ),
    CompleteWebRtcNotices(
        R.string.about_document_webrtc,
        "open_source_licenses/WEBRTC.md",
        "about-document-webrtc",
    ),
    Privacy(
        R.string.about_document_privacy,
        "about/PRIVACY.md",
        "about-document-privacy",
    ),
    Security(
        R.string.about_document_security,
        "about/SECURITY.md",
        "about-document-security",
    ),
}

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val releaseInfo = remember(context.applicationContext) {
        AboutReleaseInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            exactSourceUrl = BuildConfig.RELEASE_SOURCE_URL,
            signingCertificateSha256 = installedSigningCertificateSha256(context),
            isDevelopmentBuild = !BuildConfig.PUBLIC_DISTRIBUTION_BUILD,
        )
    }
    AboutDialog(releaseInfo = releaseInfo, onDismiss = onDismiss)
}

@Composable
internal fun AboutDialog(
    releaseInfo: AboutReleaseInfo,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var sourceOpenFailed by rememberSaveable(releaseInfo.exactSourceUrl) { mutableStateOf(false) }
    var selectedDocument by rememberSaveable { mutableStateOf<BundledAboutDocument?>(null) }

    AlertDialog(
        modifier = Modifier.testTag("about-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = {
            Text(stringResource(R.string.about_title, releaseInfo.versionName))
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .testTag("about-content"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(stringResource(R.string.about_description))
                }
                item {
                    Text(
                        stringResource(
                            R.string.about_version,
                            releaseInfo.versionName,
                            releaseInfo.versionCode,
                        ),
                        modifier = Modifier.testTag("about-version"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    Text(stringResource(R.string.about_copyright))
                }
                item {
                    Text(
                        stringResource(R.string.about_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        stringResource(R.string.about_independence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { HorizontalDivider() }
                item {
                    AboutSectionHeading(stringResource(R.string.about_source_heading))
                }
                item {
                    SelectionContainer {
                        Text(
                            releaseInfo.exactSourceUrl,
                            modifier = Modifier.testTag("about-source-url"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            sourceOpenFailed = runCatching {
                                uriHandler.openUri(releaseInfo.exactSourceUrl)
                            }.isFailure
                        },
                        modifier = Modifier.testTag("about-open-source"),
                    ) {
                        Text(stringResource(R.string.about_source_action, releaseInfo.versionName))
                    }
                }
                if (sourceOpenFailed) {
                    item {
                        Text(
                            stringResource(R.string.about_source_open_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item { HorizontalDivider() }
                item {
                    AboutSectionHeading(stringResource(R.string.about_signing_heading))
                }
                if (releaseInfo.signingCertificateSha256.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.about_signing_unavailable),
                            modifier = Modifier.testTag("about-signing-unavailable"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    itemsIndexed(releaseInfo.signingCertificateSha256) { index, fingerprint ->
                        SelectionContainer {
                            Text(
                                stringResource(
                                    R.string.about_signing_certificate,
                                    index + 1,
                                    releaseInfo.signingCertificateSha256.size,
                                    formatSha256Fingerprint(fingerprint),
                                ),
                                modifier = Modifier.testTag("about-signing-certificate-$index"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                if (releaseInfo.isDevelopmentBuild) {
                    item {
                        Text(
                            stringResource(R.string.about_signing_development_note),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    item {
                        Text(
                            stringResource(R.string.about_signing_release_note),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item { HorizontalDivider() }
                item {
                    AboutSectionHeading(stringResource(R.string.about_documents_heading))
                }
                items(BundledAboutDocument.entries) { document ->
                    TextButton(
                        onClick = { selectedDocument = document },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(document.testTag),
                    ) {
                        Text(stringResource(document.titleResource))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )

    selectedDocument?.let { document ->
        BundledAboutDocumentDialog(
            document = document,
            onDismiss = { selectedDocument = null },
        )
    }
}

@Composable
private fun AboutSectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BundledAboutDocumentDialog(
    document: BundledAboutDocument,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val content by produceState<AboutDocumentContent>(
        initialValue = AboutDocumentContent.Loading,
        key1 = document,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(document.assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                    AboutDocumentContent.Available(
                        chunkAboutDocumentText(reader.readText()),
                    )
                }
            }.getOrElse { AboutDocumentContent.Unavailable }
        }
    }

    AlertDialog(
        modifier = Modifier.testTag("about-document-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(document.titleResource)) },
        text = {
            when (val current = content) {
                AboutDocumentContent.Loading -> CircularProgressIndicator(
                    modifier = Modifier.testTag("about-document-loading"),
                )
                AboutDocumentContent.Unavailable -> Text(
                    stringResource(R.string.about_document_unavailable),
                    modifier = Modifier.testTag("about-document-unavailable"),
                    color = MaterialTheme.colorScheme.error,
                )
                is AboutDocumentContent.Available -> LazyColumn(
                    modifier = Modifier
                        .heightIn(min = 240.dp, max = 560.dp)
                        .testTag("about-document-content"),
                ) {
                    itemsIndexed(current.chunks) { index, chunk ->
                        SelectionContainer {
                            Text(
                                text = chunk,
                                modifier = Modifier.testTag("about-document-chunk-$index"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

private sealed interface AboutDocumentContent {
    data object Loading : AboutDocumentContent
    data object Unavailable : AboutDocumentContent
    data class Available(val chunks: List<String>) : AboutDocumentContent
}

internal fun chunkAboutDocumentText(
    text: String,
    maximumChunkCharacters: Int = AboutDocumentChunkCharacters,
): List<String> {
    require(maximumChunkCharacters > 0)
    if (text.isEmpty()) return listOf("")

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val hardEnd = (start + maximumChunkCharacters).coerceAtMost(text.length)
        val lastNewline = text.lastIndexOf('\n', startIndex = hardEnd - 1)
        val end = if (lastNewline >= start) lastNewline + 1 else hardEnd
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

internal fun sha256CertificateFingerprint(certificateBytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(certificateBytes)
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

internal fun formatSha256Fingerprint(compactFingerprint: String): String =
    compactFingerprint
        .filterNot { it == ':' || it.isWhitespace() }
        .uppercase()
        .chunked(2)
        .joinToString(":")

@Suppress("DEPRECATION")
internal fun installedSigningCertificateSha256(context: Context): List<String> {
    return try {
        val packageManager = context.packageManager
        val packageInfo = when {
            Build.VERSION.SDK_INT >= 33 -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
            Build.VERSION.SDK_INT >= 28 -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            else -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        signatures
            .map { signature -> sha256CertificateFingerprint(signature.toByteArray()) }
            .distinct()
            .sorted()
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}
