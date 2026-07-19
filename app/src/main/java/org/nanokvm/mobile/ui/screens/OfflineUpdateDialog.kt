package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateError
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePhase
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateReview
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateUiState

/**
 * Standalone, subdued offline-update review and progress surface.
 *
 * Document selection remains an external callback so this composable never holds an Android URI.
 */
@Composable
internal fun OfflineUpdateDialog(
    state: NanoKvmOfflineUpdateUiState,
    onChoosePackage: () -> Unit,
    onConfirm: (NanoKvmOfflineUpdateReview) -> Unit,
    onCancelUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.phase == NanoKvmOfflineUpdatePhase.HIDDEN) return

    AlertDialog(
        modifier = Modifier.testTag("offline-update-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_update_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("offline-update-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OfflineUpdateBody(state)
            }
        },
        confirmButton = {
            when (state.phase) {
                NanoKvmOfflineUpdatePhase.EMPTY,
                NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE -> Button(
                    onClick = onChoosePackage,
                    modifier = Modifier.testTag("offline-update-choose"),
                ) {
                    Text(stringResource(R.string.offline_update_choose))
                }
                NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED -> state.review?.let { review ->
                    Button(
                        onClick = { onConfirm(review) },
                        modifier = Modifier.testTag("offline-update-confirm"),
                    ) {
                        Text(stringResource(R.string.offline_update_install_once))
                    }
                }
                NanoKvmOfflineUpdatePhase.UPLOADING -> TextButton(
                    onClick = onCancelUpload,
                    modifier = Modifier.testTag("offline-update-cancel"),
                ) {
                    Text(stringResource(R.string.offline_update_cancel))
                }
                else -> Button(onClick = onDismiss) {
                    Text(stringResource(R.string.offline_update_close))
                }
            }
        },
        dismissButton = {
            if (state.phase == NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED) {
                TextButton(onClick = onChoosePackage) {
                    Text(stringResource(R.string.offline_update_choose_different))
                }
            } else if (
                state.phase == NanoKvmOfflineUpdatePhase.EMPTY ||
                state.phase == NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.offline_update_close))
                }
            }
        },
    )
}

@Composable
private fun OfflineUpdateBody(state: NanoKvmOfflineUpdateUiState) {
    when (state.phase) {
        NanoKvmOfflineUpdatePhase.INACTIVE -> Text(
            stringResource(R.string.offline_update_inactive),
        )
        NanoKvmOfflineUpdatePhase.UNSUPPORTED -> Text(
            stringResource(R.string.offline_update_unsupported),
            color = MaterialTheme.colorScheme.error,
        )
        NanoKvmOfflineUpdatePhase.EMPTY -> {
            Text(stringResource(R.string.offline_update_select_explanation))
            Text(
                stringResource(R.string.offline_update_not_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED -> state.review?.let {
            OfflineUpdateReviewCard(it)
        }
        NanoKvmOfflineUpdatePhase.UPLOADING -> OfflineUpdateProgress(state)
        NanoKvmOfflineUpdatePhase.ACKNOWLEDGED_RESTARTING -> {
            Text(stringResource(R.string.offline_update_acknowledged))
            OfflineUpdateRecoveryWarning()
        }
        NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN -> {
            Text(
                stringResource(R.string.offline_update_unknown),
                color = MaterialTheme.colorScheme.error,
            )
            OfflineUpdateRecoveryWarning()
        }
        NanoKvmOfflineUpdatePhase.AUTHENTICATION_EXPIRED -> Text(
            stringResource(R.string.offline_update_auth_expired),
            color = MaterialTheme.colorScheme.error,
        )
        NanoKvmOfflineUpdatePhase.SESSION_CHANGED -> Text(
            stringResource(R.string.offline_update_session_changed),
            color = MaterialTheme.colorScheme.error,
        )
        NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE -> {
            Text(
                definiteFailureText(state.error),
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.offline_update_reselect_required))
        }
        NanoKvmOfflineUpdatePhase.HIDDEN -> Unit
    }
}

@Composable
private fun OfflineUpdateReviewCard(review: NanoKvmOfflineUpdateReview) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline-update-review"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                stringResource(R.string.offline_update_destination, review.destinationAuthority),
            )
            Text(
                review.installedVersion?.let {
                    stringResource(R.string.offline_update_installed_version, it)
                } ?: stringResource(R.string.offline_update_installed_unknown),
            )
            Text(stringResource(R.string.offline_update_package_version, review.packageVersion))
            Text(stringResource(R.string.offline_update_exact_file, review.expectedFileName))
            Text(
                stringResource(
                    R.string.offline_update_package_size,
                    formatOfflineUpdateBytes(review.packageSizeBytes),
                ),
            )
            Text(
                stringResource(R.string.offline_update_interruption_warning),
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.offline_update_one_shot_warning),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OfflineUpdateProgress(state: NanoKvmOfflineUpdateUiState) {
    val percent = ((state.progressFraction ?: 0f) * 100f).toInt().coerceIn(0, 100)
    LinearProgressIndicator(
        progress = { state.progressFraction ?: 0f },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("offline-update-progress"),
    )
    Text(stringResource(R.string.offline_update_progress_percent, percent))
    Text(
        stringResource(
            R.string.offline_update_progress_bytes,
            formatOfflineUpdateBytes(state.bytesTransferred),
            formatOfflineUpdateBytes(state.totalBytes),
        ),
    )
    Text(
        stringResource(R.string.offline_update_upload_not_install_progress),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.offline_update_cancel_uncertain),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun OfflineUpdateRecoveryWarning() {
    Text(
        stringResource(R.string.offline_update_verify_before_retry),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun definiteFailureText(error: NanoKvmOfflineUpdateError?): String = when (error) {
    NanoKvmOfflineUpdateError.SOURCE_UNAVAILABLE ->
        stringResource(R.string.offline_update_source_unavailable)
    NanoKvmOfflineUpdateError.SERVER_REJECTED ->
        stringResource(R.string.offline_update_server_rejected)
    NanoKvmOfflineUpdateError.INVALID_SELECTION,
    NanoKvmOfflineUpdateError.STALE_REVIEW ->
        stringResource(R.string.offline_update_invalid_selection)
    else -> stringResource(R.string.offline_update_failed)
}

internal fun formatOfflineUpdateBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> {
        val whole = bytes / (1024L * 1024L)
        val tenth = (bytes % (1024L * 1024L)) * 10L / (1024L * 1024L)
        "$whole.$tenth MiB"
    }
    bytes >= 1024L -> "${bytes / 1024L} KiB"
    else -> "$bytes B"
}
