package org.nanokvm.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.runtime.CertificateDetails

private val CertificateContentMaxWidth = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertificateReviewScreen(
    profile: HostProfile,
    certificate: CertificateDetails,
    onTrustOnce: () -> Unit,
    onTrustAndRemember: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var fingerprintCopied by remember(certificate.sha256) { mutableStateOf(false) }
    val previousFingerprint = profile.trustedCertificateSha256
        ?.takeUnless { saved -> saved.equals(certificate.sha256, ignoreCase = true) }
    val isCertificateChange = previousFingerprint != null
    var previousFingerprintCopied by remember(previousFingerprint) { mutableStateOf(false) }
    val fingerprintClipLabel = stringResource(R.string.certificate_fingerprint_clip_label)
    val previousFingerprintClipLabel =
        stringResource(R.string.certificate_previous_fingerprint_clip_label)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.certificate_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.certificate_cancel_content_description),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CertificateContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Default.GppMaybe, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(
                                    if (isCertificateChange) {
                                        R.string.certificate_changed_title
                                    } else {
                                        R.string.certificate_private_title
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(certificate.reason, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    stringResource(
                        if (isCertificateChange) {
                            R.string.certificate_changed_warning
                        } else {
                            R.string.certificate_review_explanation
                        },
                        profile.authority,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        previousFingerprint?.let { savedFingerprint ->
                            CertificateFingerprintField(
                                label = stringResource(
                                    R.string.certificate_previous_fingerprint_label,
                                ),
                                value = savedFingerprint,
                                copied = previousFingerprintCopied,
                                copyDescription = stringResource(
                                    R.string.certificate_copy_previous_fingerprint_content_description,
                                ),
                                onCopy = {
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            previousFingerprintClipLabel,
                                            savedFingerprint,
                                        ),
                                    )
                                    previousFingerprintCopied = true
                                },
                            )
                        }
                        CertificateFingerprintField(
                            label = stringResource(
                                if (isCertificateChange) {
                                    R.string.certificate_new_fingerprint_label
                                } else {
                                    R.string.certificate_fingerprint_label
                                },
                            ),
                            value = certificate.sha256,
                            copied = fingerprintCopied,
                            copyDescription = stringResource(
                                R.string.certificate_copy_fingerprint_content_description,
                            ),
                            onCopy = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        fingerprintClipLabel,
                                        certificate.sha256,
                                    ),
                                )
                                fingerprintCopied = true
                            },
                        )
                        CertificateField(
                            label = stringResource(R.string.certificate_subject_label),
                            value = certificate.subject,
                        )
                        CertificateField(
                            label = stringResource(R.string.certificate_issuer_label),
                            value = certificate.issuer,
                        )
                        CertificateField(
                            label = stringResource(R.string.certificate_names_label),
                            value = certificate.subjectAlternativeNames
                                .joinToString()
                                .ifBlank { stringResource(R.string.certificate_names_empty) },
                        )
                        CertificateField(
                            label = stringResource(R.string.certificate_valid_from_label),
                            value = certificate.validFrom,
                        )
                        CertificateField(
                            label = stringResource(R.string.certificate_valid_until_label),
                            value = certificate.validUntil,
                        )
                    }
                }
                Button(
                    onClick = onTrustAndRemember,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(
                            if (isCertificateChange) {
                                R.string.certificate_replace_saved_action
                            } else {
                                R.string.certificate_trust_remember_action
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onTrustOnce,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(
                            if (isCertificateChange) {
                                R.string.certificate_connect_once_changed_action
                            } else {
                                R.string.certificate_connect_once_action
                            },
                        ),
                    )
                }
                Text(
                    stringResource(
                        if (isCertificateChange) {
                            R.string.certificate_changed_security_note
                        } else {
                            R.string.certificate_security_note
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CertificateFingerprintField(
    label: String,
    value: String,
    copied: Boolean,
    copyDescription: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (copied) {
                Text(
                    stringResource(R.string.certificate_fingerprint_copied),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.semantics { contentDescription = copyDescription },
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null)
        }
    }
}

@Composable
private fun CertificateField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
