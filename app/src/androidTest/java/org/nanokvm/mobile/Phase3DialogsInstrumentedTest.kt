package org.nanokvm.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.Phase3FeatureUiState
import org.nanokvm.mobile.runtime.Phase3ImageMountMode
import org.nanokvm.mobile.runtime.Phase3MediaImageUiState
import org.nanokvm.mobile.runtime.Phase3VirtualMediaUiState
import org.nanokvm.mobile.runtime.Phase3WakeOnLanTargetUiState
import org.nanokvm.mobile.ui.screens.VirtualMediaDialog
import org.nanokvm.mobile.ui.screens.WakeOnLanDialog
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class Phase3DialogsInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun virtualMediaLargeCatalogComposesBoundedRowsAndMountsLastStableKey() {
        val images = List(1_024) { index ->
            Phase3MediaImageUiState(
                id = 100_000L + index,
                displayName = "Image $index.iso",
                mounted = false,
            )
        }
        val lastImage = images.last()
        var mountedImageId: Long? = null
        var mountedMode: Phase3ImageMountMode? = null

        composeRule.setContent {
            NanoKvmTheme {
                VirtualMediaDialog(
                    state = Phase3FeatureUiState(
                        available = true,
                        virtualMedia = Phase3VirtualMediaUiState(
                            loaded = true,
                            images = images,
                            networkEnabled = true,
                            mediaEnabled = true,
                            diskEnabled = true,
                            remoteTransferEnabled = true,
                        ),
                    ),
                    onDismiss = {},
                    onRefresh = {},
                    onMount = { image, mode ->
                        mountedImageId = image.id
                        mountedMode = mode
                    },
                    onRestore = {},
                    onDelete = {},
                    onSetHidMode = {},
                    onSetNetworkEnabled = {},
                    onSetDiskEnabled = {},
                    onStartTransfer = {},
                )
            }
        }

        composeRule.onNodeWithTag("phase3-virtual-media-list")
            .performScrollToKey(lastImage.id)
        composeRule.waitForIdle()

        val composedRows = composeRule.onAllNodesWithTag("phase3-media-image")
            .fetchSemanticsNodes()
            .size
        assertTrue(composedRows in 1 until images.size)
        composeRule.onNodeWithTag("phase3-media-mount-${lastImage.id}")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(lastImage.id, mountedImageId)
            assertEquals(Phase3ImageMountMode.MassStorage, mountedMode)
        }
    }

    @Test
    fun wakeOnLanLargeCatalogComposesBoundedRowsAndWakesLastStableKey() {
        val targets = List(512) { index ->
            val highByte = (index / 256).toString(16).padStart(2, '0')
            val lowByte = (index % 256).toString(16).padStart(2, '0')
            Phase3WakeOnLanTargetUiState(
                id = 200_000L + index,
                macAddress = "02:00:00:00:$highByte:$lowByte",
                name = "Target $index",
            )
        }
        val lastTarget = targets.last()
        var wokenMacAddress: String? = null

        composeRule.setContent {
            NanoKvmTheme {
                WakeOnLanDialog(
                    state = Phase3FeatureUiState(
                        available = true,
                        wakeOnLanLoaded = true,
                        wakeOnLanTargets = targets,
                    ),
                    onDismiss = {},
                    onRefresh = {},
                    onWake = { wokenMacAddress = it },
                    onRename = { _, _ -> },
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("phase3-wol-list")
            .performScrollToKey(lastTarget.id)
        composeRule.waitForIdle()

        val composedRows = composeRule.onAllNodesWithTag("phase3-wol-target")
            .fetchSemanticsNodes()
            .size
        assertTrue(composedRows in 1 until targets.size)
        composeRule.onNodeWithTag("phase3-wol-wake-${lastTarget.id}")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(lastTarget.macAddress, wokenMacAddress)
        }
    }
}
