package org.nanokvm.mobile

import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nanokvm.mobile.runtime.KeyboardLayout
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.runtime.RemoteKey
import org.nanokvm.mobile.runtime.VideoSurfaceSink
import org.nanokvm.mobile.ui.components.PointerMode
import org.nanokvm.mobile.ui.components.RemoteViewport
import org.nanokvm.mobile.ui.components.ViewportAction
import org.nanokvm.mobile.ui.components.ViewportCommand
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

class RemoteViewportAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val input = RecordingRemoteInputSink()
    private val videoSurface = NoOpVideoSurfaceSink()

    @Test
    fun customActionsProvideAlternativesForPointerClicksRemoteScrollAndPadMovement() {
        renderViewport()

        performCustomAction("remote-input-layer", R.string.console_move_pointer_left)
        performCustomAction("remote-input-layer", R.string.console_move_pointer_right)
        performCustomAction("remote-input-layer", R.string.console_move_pointer_up)
        performCustomAction("remote-input-layer", R.string.console_move_pointer_down)
        performCustomAction("remote-input-layer", R.string.console_click_pointer)
        performCustomAction("remote-input-layer", R.string.console_right_click_pointer)

        assertEquals(4, input.relativeMoves.size)
        assertTrue(input.relativeMoves[0].first < 0 && input.relativeMoves[0].second == 0)
        assertTrue(input.relativeMoves[1].first > 0 && input.relativeMoves[1].second == 0)
        assertTrue(input.relativeMoves[2].first == 0 && input.relativeMoves[2].second < 0)
        assertTrue(input.relativeMoves[3].first == 0 && input.relativeMoves[3].second > 0)
        assertEquals(
            listOf(
                MouseButton.Left to true,
                MouseButton.Left to false,
                MouseButton.Right to true,
                MouseButton.Right to false,
            ),
            input.mouseButtonEvents,
        )

        performCustomAction("remote-scroll-pad", R.string.console_scroll_remote_up)
        performCustomAction("remote-scroll-pad", R.string.console_scroll_remote_down)
        performCustomAction("remote-scroll-pad", R.string.console_scroll_remote_left)
        performCustomAction("remote-scroll-pad", R.string.console_scroll_remote_right)

        assertEquals(listOf(1, -1), input.verticalScrollSteps)
        assertEquals(listOf(1, -1), input.horizontalScrollSteps)

        val bottomBounds = viewNavigationPanelBounds()
        performCustomAction("view-navigation-move-handle", R.string.console_move_view_pad_top)
        val topBounds = viewNavigationPanelBounds()
        assertTrue("Move-to-top must raise the view pad", topBounds.top < bottomBounds.top)

        performCustomAction("view-navigation-move-handle", R.string.console_move_view_pad_down)
        val steppedDownBounds = viewNavigationPanelBounds()
        assertTrue("Move-down must lower the view pad", steppedDownBounds.top > topBounds.top)

        performCustomAction("view-navigation-move-handle", R.string.console_move_view_pad_bottom)
        val returnedBottomBounds = viewNavigationPanelBounds()
        assertEquals(bottomBounds.top, returnedBottomBounds.top, 1f)

        performCustomAction("view-navigation-move-handle", R.string.console_move_view_pad_up)
        val steppedUpBounds = viewNavigationPanelBounds()
        assertTrue("Move-up must raise the view pad", steppedUpBounds.top < returnedBottomBounds.top)
    }

    @Test
    fun zoomSurvivesSavedStateRestorationUntilAnExplicitFitRequest() {
        val restorationTester = StateRestorationTester(composeRule)
        val command = mutableStateOf<ViewportCommand?>(null)
        var reportedZoom = 0f
        restorationTester.setContent {
            NanoKvmTheme {
                RemoteViewport(
                    input = input,
                    videoSurface = videoSurface,
                    remoteWidth = 1_920,
                    remoteHeight = 1_080,
                    videoSurfaceGeneration = 1L,
                    pointerMode = PointerMode.Trackpad,
                    fitRequest = 0,
                    viewportCommand = command.value,
                    viewNavigationVisible = true,
                    onZoomChanged = { reportedZoom = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
        val fittedZoom = reportedZoom

        composeRule.runOnIdle {
            command.value = ViewportCommand(sequence = 1, action = ViewportAction.ZoomIn)
        }
        composeRule.waitForIdle()
        val zoomed = reportedZoom
        assertTrue("An explicit zoom command must change the saved transform", zoomed > fittedZoom)

        composeRule.runOnIdle { command.value = null }
        composeRule.waitForIdle()
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals(zoomed, reportedZoom, 0.001f)
    }

    @Test
    fun zoomPanAndDirectMappingSurviveRestoreAfterHandledNonZeroFitRequest() {
        val restorationTester = StateRestorationTester(composeRule)
        val fitRequest = mutableIntStateOf(0)
        val command = mutableStateOf<ViewportCommand?>(null)
        var reportedZoom = 0f
        restorationTester.setContent {
            NanoKvmTheme {
                RemoteViewport(
                    input = input,
                    videoSurface = videoSurface,
                    remoteWidth = 1_920,
                    remoteHeight = 1_080,
                    videoSurfaceGeneration = 1L,
                    pointerMode = PointerMode.Direct,
                    fitRequest = fitRequest.intValue,
                    viewportCommand = command.value,
                    viewNavigationVisible = true,
                    onZoomChanged = { reportedZoom = it },
                    modifier = Modifier.requiredSize(width = 360.dp, height = 500.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { fitRequest.intValue = 7 }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            command.value = ViewportCommand(sequence = 1, action = ViewportAction.ZoomIn)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            command.value = ViewportCommand(sequence = 2, action = ViewportAction.FocusTop)
        }
        composeRule.waitForIdle()
        val zoomBeforeRestore = reportedZoom
        assertTrue("The test must establish a zoomed viewport", zoomBeforeRestore > 1f)

        composeRule.runOnIdle { command.value = null }
        composeRule.waitForIdle()
        tapRemoteInputCentre()
        val mappingBeforeRestore = input.absoluteMoves.last()
        assertTrue(
            "The test must establish non-centred vertical panning",
            mappingBeforeRestore.second < HID_ABSOLUTE_MIDPOINT - 500,
        )

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals(zoomBeforeRestore, reportedZoom, 0.001f)
        tapRemoteInputCentre()
        val mappingAfterRestore = input.absoluteMoves.last()
        assertEquals(mappingBeforeRestore.first, mappingAfterRestore.first)
        assertEquals(mappingBeforeRestore.second, mappingAfterRestore.second)
    }

    private fun renderViewport() {
        composeRule.setContent {
            NanoKvmTheme {
                RemoteViewport(
                    input = input,
                    videoSurface = videoSurface,
                    remoteWidth = 1_920,
                    remoteHeight = 1_080,
                    videoSurfaceGeneration = 1L,
                    pointerMode = PointerMode.Trackpad,
                    fitRequest = 0,
                    viewNavigationVisible = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun performCustomAction(tag: String, @StringRes labelResource: Int) {
        val label = composeRule.activity.getString(labelResource)
        val actions = composeRule.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        val requestedAction = actions.firstOrNull { it.label == label }
        assertNotNull("Expected accessibility action '$label' on '$tag'", requestedAction)

        var actionResult = false
        composeRule.runOnIdle { actionResult = requestedAction!!.action() }
        assertTrue("Accessibility action '$label' should succeed", actionResult)
        composeRule.waitForIdle()
    }

    private fun tapRemoteInputCentre() {
        val moveCountBeforeTap = input.absoluteMoves.size
        composeRule.onNodeWithTag("remote-input-layer").performTouchInput {
            click(Offset(centerX, centerY))
        }
        // RemoteViewport also supports double tap, so Compose deliberately defers the single-tap
        // callback until its double-tap window expires. waitForIdle() does not promise to wait for
        // that wall-clock pointer timeout on every API level.
        composeRule.waitUntil(timeoutMillis = 2_000) {
            input.absoluteMoves.size > moveCountBeforeTap
        }
    }

    private fun viewNavigationPanelBounds(): Rect = composeRule
        .onNodeWithTag("view-navigation-panel")
        .fetchSemanticsNode()
        .boundsInRoot
}

private class RecordingRemoteInputSink : RemoteInputSink {
    val absoluteMoves = mutableListOf<Pair<Int, Int>>()
    val relativeMoves = mutableListOf<Pair<Int, Int>>()
    val mouseButtonEvents = mutableListOf<Pair<MouseButton, Boolean>>()
    val verticalScrollSteps = mutableListOf<Int>()
    val horizontalScrollSteps = mutableListOf<Int>()

    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) {
        absoluteMoves += x to y
    }

    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        relativeMoves += deltaX to deltaY
    }

    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        mouseButtonEvents += button to pressed
    }

    override fun scrollWheel(steps: Int) {
        verticalScrollSteps += steps
    }

    override fun scrollHorizontal(steps: Int) {
        horizontalScrollSteps += steps
    }

    override fun typeCommittedText(text: String, layout: KeyboardLayout) = Unit

    override fun key(key: RemoteKey, pressed: Boolean) = Unit

    override fun releaseAllInput() = Unit
}

private const val HID_ABSOLUTE_MIDPOINT = 16_384

private class NoOpVideoSurfaceSink : VideoSurfaceSink {
    override fun attachVideoSurface(surface: Surface, width: Int, height: Int) = Unit

    override fun resizeVideoSurface(width: Int, height: Int) = Unit

    override fun detachVideoSurface(surface: Surface) = Unit
}
