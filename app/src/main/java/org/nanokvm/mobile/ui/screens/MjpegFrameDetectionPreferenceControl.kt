package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R

internal const val MJPEG_FRAME_DETECTION_TOGGLE_TAG = "mjpegFrameDetectionToggle"

/** Compact, accessible preference row hosted by the existing video-settings dialog. */
@Composable
internal fun MjpegFrameDetectionPreferenceControl(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stringResource(
        if (enabled) {
            R.string.console_mjpeg_frame_detection_state_on
        } else {
            R.string.console_mjpeg_frame_detection_state_off
        },
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = state }
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onEnabledChange,
            )
            .testTag(MJPEG_FRAME_DETECTION_TOGGLE_TAG)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.console_mjpeg_frame_detection),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.console_mjpeg_frame_detection_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
