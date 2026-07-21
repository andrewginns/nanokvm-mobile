package org.nanokvm.mobile.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.runtime.MouseButton
import kotlin.math.roundToInt

class ExternalPointerInputRouterTest {
    @Test
    fun `all five mouse buttons reconcile once in deterministic order`() {
        val router = absoluteRouter()
        val point = position(10f, 20f)
        router.route(sample(ExternalPointerPhase.Move, point))

        val buttons = linkedSetOf(
            MouseButton.Left,
            MouseButton.Right,
            MouseButton.Middle,
            MouseButton.Back,
            MouseButton.Forward,
        )
        assertEquals(
            buttons.map { ExternalPointerCommand.Button(it, pressed = true) },
            router.route(sample(ExternalPointerPhase.Press, point, buttons)),
        )
        assertEquals(
            buttons.map { ExternalPointerCommand.Button(it, pressed = false) },
            router.route(sample(ExternalPointerPhase.Release, point)),
        )
    }

    @Test
    fun `duplicate Android button state does not double dispatch`() {
        val router = absoluteRouter()
        val point = position(1f, 2f)
        val pressed = setOf(MouseButton.Left)

        assertEquals(2, router.route(sample(ExternalPointerPhase.Press, point, pressed)).size)
        assertTrue(router.route(sample(ExternalPointerPhase.Press, point, pressed)).isEmpty())
        assertEquals(
            listOf(ExternalPointerCommand.Button(MouseButton.Left, pressed = false)),
            router.route(sample(ExternalPointerPhase.Release, point)),
        )
        assertTrue(router.route(sample(ExternalPointerPhase.Release, point)).isEmpty())
    }

    @Test
    fun `press outside absolute content remains suppressed after pointer enters`() {
        val router = absoluteRouter { position ->
            position.takeIf { it.x >= 0f }?.let { ExternalAbsolutePosition(it.x.toInt(), it.y.toInt()) }
        }
        val pressed = setOf(MouseButton.Left)

        assertTrue(
            router.route(
                sample(ExternalPointerPhase.Press, position(-2f, 4f), pressed),
            ).isEmpty(),
        )
        assertEquals(
            listOf(ExternalPointerCommand.MoveAbsolute(2, 4, emptySet())),
            router.route(sample(ExternalPointerPhase.Move, position(2f, 4f), pressed)),
        )
        assertTrue(router.route(sample(ExternalPointerPhase.Release, position(2f, 4f))).isEmpty())
    }

    @Test
    fun `button pressed inside is released even when pointer leaves content`() {
        val router = absoluteRouter { position ->
            position.takeIf { it.x >= 0f }?.let { ExternalAbsolutePosition(it.x.toInt(), it.y.toInt()) }
        }
        val pressed = setOf(MouseButton.Right)
        router.route(sample(ExternalPointerPhase.Press, position(3f, 4f), pressed))

        assertEquals(
            listOf(ExternalPointerCommand.Button(MouseButton.Right, pressed = false)),
            router.route(sample(ExternalPointerPhase.Release, position(-1f, 4f))),
        )
    }

    @Test
    fun `absolute drag carries the reconciled button state`() {
        val router = absoluteRouter()
        val pressed = setOf(MouseButton.Middle)
        router.route(sample(ExternalPointerPhase.Press, position(1f, 1f), pressed))

        assertEquals(
            listOf(ExternalPointerCommand.MoveAbsolute(200, 300, pressed)),
            router.route(sample(ExternalPointerPhase.Move, position(2f, 3f), pressed)),
        )
    }

    @Test
    fun `relative motion preserves fractions and splits HID-sized reports without loss`() {
        val router = relativeRouter()
        assertTrue(
            router.route(
                relativeSample(deltaX = 0.4f, deltaY = -0.4f),
            ).isEmpty(),
        )
        assertEquals(
            listOf(ExternalPointerCommand.MoveRelative(1, -1, emptySet())),
            router.route(relativeSample(deltaX = 0.7f, deltaY = -0.7f)),
        )

        assertEquals(
            listOf(
                ExternalPointerCommand.MoveRelative(127, -127, emptySet()),
                ExternalPointerCommand.MoveRelative(127, -73, emptySet()),
                ExternalPointerCommand.MoveRelative(46, 0, emptySet()),
            ),
            router.route(relativeSample(deltaX = 300f, deltaY = -200f)),
        )
    }

    @Test
    fun `relative enter never turns an absolute hover jump into remote motion`() {
        val router = relativeRouter()

        assertTrue(
            router.route(
                relativeSample(
                    phase = ExternalPointerPhase.Enter,
                    deltaX = 500f,
                    deltaY = 500f,
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `wheel axes preserve fractions apply sensitivity and use remote direction`() {
        val router = relativeRouter(sensitivity = 0.5f)

        assertTrue(
            router.route(
                relativeSample(
                    phase = ExternalPointerPhase.Scroll,
                    horizontalScroll = 1f,
                    verticalScroll = -1f,
                ),
            ).isEmpty(),
        )
        assertEquals(
            listOf(
                ExternalPointerCommand.ScrollHorizontal(-1),
                ExternalPointerCommand.ScrollVertical(1),
            ),
            router.route(
                relativeSample(
                    phase = ExternalPointerPhase.Scroll,
                    horizontalScroll = 1f,
                    verticalScroll = -1f,
                ),
            ),
        )

        val fast = relativeRouter(sensitivity = 3f)
        assertEquals(
            listOf(
                ExternalPointerCommand.ScrollHorizontal(3),
                ExternalPointerCommand.ScrollVertical(-3),
            ),
            fast.route(
                relativeSample(
                    phase = ExternalPointerPhase.Scroll,
                    horizontalScroll = -1f,
                    verticalScroll = 1f,
                ),
            ),
        )
    }

    @Test
    fun `non-finite movement and wheel axes are ignored independently`() {
        val router = relativeRouter()

        assertEquals(
            listOf(ExternalPointerCommand.ScrollVertical(-1)),
            router.route(
                relativeSample(
                    deltaX = Float.NaN,
                    deltaY = 2f,
                    horizontalScroll = Float.POSITIVE_INFINITY,
                    verticalScroll = 1f,
                ),
            ),
        )
    }

    @Test
    fun `cancel and lifecycle disposal release all input without replay`() {
        val router = relativeRouter()
        router.route(
            relativeSample(
                phase = ExternalPointerPhase.Press,
                pressedButtons = setOf(MouseButton.Forward),
            ),
        )

        assertEquals(
            listOf(ExternalPointerCommand.ReleaseAll),
            router.route(
                relativeSample(
                    phase = ExternalPointerPhase.Cancel,
                    pressedButtons = setOf(MouseButton.Forward),
                ),
            ),
        )
        assertTrue(router.releaseAll().isEmpty())
    }

    @Test
    fun `late confirmed ownership opens a fresh teardown lifecycle`() {
        val router = relativeRouter()

        assertEquals(
            listOf(ExternalPointerCommand.ReleaseAll),
            router.releaseAll(),
        )
        assertTrue(router.releaseAll().isEmpty())

        router.beginLifecycle()

        assertEquals(
            listOf(ExternalPointerCommand.ReleaseAll),
            router.releaseAll(),
        )
        assertTrue(router.releaseAll().isEmpty())
    }

    @Test
    fun `wheel remains available over an absolute letterbox area`() {
        val router = absoluteRouter { null }

        assertEquals(
            listOf(ExternalPointerCommand.ScrollVertical(1)),
            router.route(
                sample(
                    phase = ExternalPointerPhase.Scroll,
                    position = position(-100f, -100f),
                    verticalScroll = -1f,
                ),
            ),
        )
    }

    private fun absoluteRouter(
        mapper: (ExternalPointerPosition) -> ExternalAbsolutePosition? = {
            ExternalAbsolutePosition((it.x * 100f).roundToInt(), (it.y * 100f).roundToInt())
        },
    ): ExternalPointerInputRouter = ExternalPointerInputRouter(
        mode = ExternalPointerRoutingMode.Absolute,
        scrollSensitivity = 1f,
        mapAbsolutePosition = mapper,
    )

    private fun relativeRouter(sensitivity: Float = 1f): ExternalPointerInputRouter =
        ExternalPointerInputRouter(
            mode = ExternalPointerRoutingMode.Relative,
            scrollSensitivity = sensitivity,
        )

    private fun sample(
        phase: ExternalPointerPhase,
        position: ExternalPointerPosition,
        pressedButtons: Set<MouseButton> = emptySet(),
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
    ) = ExternalPointerSample(
        phase = phase,
        position = position,
        pressedButtons = pressedButtons,
        horizontalScroll = horizontalScroll,
        verticalScroll = verticalScroll,
    )

    private fun relativeSample(
        phase: ExternalPointerPhase = ExternalPointerPhase.Move,
        deltaX: Float = 0f,
        deltaY: Float = 0f,
        pressedButtons: Set<MouseButton> = emptySet(),
        horizontalScroll: Float = 0f,
        verticalScroll: Float = 0f,
    ) = ExternalPointerSample(
        phase = phase,
        position = position(20f, 30f),
        deltaX = deltaX,
        deltaY = deltaY,
        pressedButtons = pressedButtons,
        horizontalScroll = horizontalScroll,
        verticalScroll = verticalScroll,
    )

    private fun position(x: Float, y: Float) = ExternalPointerPosition(x, y)
}
