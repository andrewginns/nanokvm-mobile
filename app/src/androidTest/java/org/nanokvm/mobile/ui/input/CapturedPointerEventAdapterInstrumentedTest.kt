package org.nanokvm.mobile.ui.input

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.nanokvm.mobile.runtime.MouseButton

@RunWith(AndroidJUnit4::class)
class CapturedPointerEventAdapterInstrumentedTest {
    @Test
    fun relativeAxesAndAllButtonsRouteThroughTheSharedHidRouter() {
        val event = mouseEvent(
            action = MotionEvent.ACTION_MOVE,
            relativeX = 4f,
            relativeY = -3f,
            buttons = MotionEvent.BUTTON_PRIMARY or
                MotionEvent.BUTTON_SECONDARY or
                MotionEvent.BUTTON_TERTIARY or
                MotionEvent.BUTTON_BACK or
                MotionEvent.BUTTON_FORWARD,
        )
        val router = ExternalPointerInputRouter(
            mode = ExternalPointerRoutingMode.Relative,
            scrollSensitivity = 1f,
        )

        val commands = event.toCapturedPointerSamples().flatMap(router::route)

        assertEquals(
            ExternalPointerCommand.MoveRelative(4, -3, emptySet()),
            commands.first(),
        )
        assertEquals(
            listOf(
                MouseButton.Left,
                MouseButton.Right,
                MouseButton.Middle,
                MouseButton.Back,
                MouseButton.Forward,
            ).map { ExternalPointerCommand.Button(it, pressed = true) },
            commands.drop(1),
        )
        event.recycle()
    }

    @Test
    fun capturedWheelUsesTheSameRemoteDirectionAsComposeMouseInput() {
        val event = mouseEvent(
            action = MotionEvent.ACTION_SCROLL,
            horizontalScroll = -1f,
            verticalScroll = 1f,
        )
        val router = ExternalPointerInputRouter(
            mode = ExternalPointerRoutingMode.Relative,
            scrollSensitivity = 1f,
        )

        val commands = event.toCapturedPointerSamples().flatMap(router::route)

        assertEquals(
            listOf(
                ExternalPointerCommand.ScrollHorizontal(1),
                ExternalPointerCommand.ScrollVertical(1),
            ),
            commands,
        )
        event.recycle()
    }

    @Test
    fun everyBatchedRelativeSampleIsPreserved() {
        val event = mouseEvent(
            action = MotionEvent.ACTION_MOVE,
            relativeX = 0.6f,
            relativeY = -0.6f,
        )
        val laterCoordinates = coordinates(relativeX = 0.6f, relativeY = -0.6f)
        event.addBatch(2L, arrayOf(laterCoordinates), 0)
        val router = ExternalPointerInputRouter(
            mode = ExternalPointerRoutingMode.Relative,
            scrollSensitivity = 1f,
        )

        val commands = event.toCapturedPointerSamples().flatMap(router::route)

        assertEquals(
            listOf(ExternalPointerCommand.MoveRelative(1, -1, emptySet())),
            commands,
        )
        event.recycle()
    }

    private fun mouseEvent(
        action: Int,
        relativeX: Float = 0f,
        relativeY: Float = 0f,
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
        buttons: Int = 0,
    ): MotionEvent {
        val pointerProperties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        return MotionEvent.obtain(
            1L,
            1L,
            action,
            1,
            arrayOf(pointerProperties),
            arrayOf(
                coordinates(
                    relativeX = relativeX,
                    relativeY = relativeY,
                    horizontalScroll = horizontalScroll,
                    verticalScroll = verticalScroll,
                ),
            ),
            0,
            buttons,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE_RELATIVE,
            0,
        )
    }

    private fun coordinates(
        relativeX: Float,
        relativeY: Float,
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
    ) = MotionEvent.PointerCoords().apply {
        setAxisValue(MotionEvent.AXIS_RELATIVE_X, relativeX)
        setAxisValue(MotionEvent.AXIS_RELATIVE_Y, relativeY)
        setAxisValue(MotionEvent.AXIS_HSCROLL, horizontalScroll)
        setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
    }
}
