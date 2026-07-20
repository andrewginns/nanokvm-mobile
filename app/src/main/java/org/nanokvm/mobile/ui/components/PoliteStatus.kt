package org.nanokvm.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Announces an asynchronous status update without moving accessibility focus.
 *
 * Descendant text is merged into the live-region node so cards and plain messages expose the same
 * predictable semantics to TalkBack and other accessibility services.
 */
@Composable
internal fun PoliteStatus(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        content()
    }
}
