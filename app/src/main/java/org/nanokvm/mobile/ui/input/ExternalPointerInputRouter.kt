package org.nanokvm.mobile.ui.input

import org.nanokvm.mobile.data.normalizeScrollSensitivity
import org.nanokvm.mobile.runtime.MouseButton

/**
 * Pure, stateful translation from external pointing-device samples to remote HID commands.
 *
 * Compose reports the complete button state rather than a single changed button. Reconciliation
 * here makes duplicate Android down/button-press events harmless and ensures every forwarded press
 * has exactly one matching release. Touch gestures deliberately bypass this router.
 */
internal class ExternalPointerInputRouter(
    private val mode: ExternalPointerRoutingMode,
    scrollSensitivity: Float,
    private val mapAbsolutePosition: (ExternalPointerPosition) -> ExternalAbsolutePosition? = {
        null
    },
) {
    private val observedButtons = linkedSetOf<MouseButton>()
    private val remoteButtons = linkedSetOf<MouseButton>()
    private val suppressedButtons = linkedSetOf<MouseButton>()
    private val relativeMotion = RelativeMotionAccumulator()
    private val scroll = ExternalWheelAccumulator(scrollSensitivity)
    private var lastAbsolutePosition: ExternalAbsolutePosition? = null
    private var lifecycleReleased = false

    fun route(sample: ExternalPointerSample): List<ExternalPointerCommand> {
        if (sample.phase == ExternalPointerPhase.Cancel) return releaseAll()
        lifecycleReleased = false

        val commands = mutableListOf<ExternalPointerCommand>()
        val position = sample.position.takeIf(ExternalPointerPosition::isFinite)
        val mappedPosition = position?.let(mapAbsolutePosition)
        val positionAcceptsNewPress = when (mode) {
            ExternalPointerRoutingMode.Absolute -> mappedPosition != null
            ExternalPointerRoutingMode.Relative -> position != null
        }

        routeMotion(sample, mappedPosition, commands)
        reconcileButtons(sample.pressedButtons, positionAcceptsNewPress, commands)

        val wheelSteps = scroll.add(
            horizontalTicks = sample.horizontalScroll,
            verticalTicks = sample.verticalScroll,
        )
        if (wheelSteps.horizontal != 0) {
            commands += ExternalPointerCommand.ScrollHorizontal(wheelSteps.horizontal)
        }
        if (wheelSteps.vertical != 0) {
            commands += ExternalPointerCommand.ScrollVertical(wheelSteps.vertical)
        }
        return commands
    }

    /**
     * Releases all remote input exactly once for a cancellation/disposal boundary and forgets all
     * local gesture state. A later pointer event starts a fresh lifecycle.
     */
    fun releaseAll(): List<ExternalPointerCommand> {
        if (lifecycleReleased) return emptyList()
        lifecycleReleased = true
        observedButtons.clear()
        remoteButtons.clear()
        suppressedButtons.clear()
        relativeMotion.reset()
        scroll.reset()
        lastAbsolutePosition = null
        return listOf(ExternalPointerCommand.ReleaseAll)
    }

    private fun routeMotion(
        sample: ExternalPointerSample,
        mappedPosition: ExternalAbsolutePosition?,
        commands: MutableList<ExternalPointerCommand>,
    ) {
        when (mode) {
            ExternalPointerRoutingMode.Absolute -> {
                if (sample.phase == ExternalPointerPhase.Exit || mappedPosition == null) return
                if (mappedPosition == lastAbsolutePosition) return
                lastAbsolutePosition = mappedPosition
                commands += ExternalPointerCommand.MoveAbsolute(
                    x = mappedPosition.x,
                    y = mappedPosition.y,
                    buttons = remoteButtons.toSet(),
                )
            }

            ExternalPointerRoutingMode.Relative -> {
                if (
                    sample.phase == ExternalPointerPhase.Enter ||
                    sample.phase == ExternalPointerPhase.Exit
                ) {
                    relativeMotion.reset()
                    return
                }
                relativeMotion.add(sample.deltaX, sample.deltaY).forEach { delta ->
                    commands += ExternalPointerCommand.MoveRelative(
                        deltaX = delta.x,
                        deltaY = delta.y,
                        buttons = remoteButtons.toSet(),
                    )
                }
            }
        }
    }

    private fun reconcileButtons(
        sampledButtons: Set<MouseButton>,
        positionAcceptsNewPress: Boolean,
        commands: MutableList<ExternalPointerCommand>,
    ) {
        val currentButtons = MOUSE_BUTTON_ORDER.filterTo(linkedSetOf()) { it in sampledButtons }

        // Release first if an unusual event replaces one pressed button with another atomically.
        MOUSE_BUTTON_ORDER.forEach { button ->
            if (button !in observedButtons || button in currentButtons) return@forEach
            suppressedButtons -= button
            if (remoteButtons.remove(button)) {
                commands += ExternalPointerCommand.Button(button, pressed = false)
            }
        }
        MOUSE_BUTTON_ORDER.forEach { button ->
            if (button in observedButtons || button !in currentButtons) return@forEach
            if (positionAcceptsNewPress) {
                remoteButtons += button
                commands += ExternalPointerCommand.Button(button, pressed = true)
            } else {
                // Do not begin a drag later merely because a button pressed in a letterbox area
                // moves over the remote image. It remains suppressed until its physical release.
                suppressedButtons += button
            }
        }

        observedButtons.clear()
        observedButtons += currentButtons
    }

    private companion object {
        val MOUSE_BUTTON_ORDER = listOf(
            MouseButton.Left,
            MouseButton.Right,
            MouseButton.Middle,
            MouseButton.Back,
            MouseButton.Forward,
        )
    }
}

internal enum class ExternalPointerRoutingMode { Absolute, Relative }

internal enum class ExternalPointerPhase {
    Enter,
    Move,
    Press,
    Release,
    Scroll,
    Exit,
    Cancel,
    Unknown,
}

internal data class ExternalPointerPosition(
    val x: Float,
    val y: Float,
) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite()
}

internal data class ExternalAbsolutePosition(
    val x: Int,
    val y: Int,
)

internal data class ExternalPointerSample(
    val phase: ExternalPointerPhase,
    val position: ExternalPointerPosition,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val pressedButtons: Set<MouseButton> = emptySet(),
    /** Compose wheel-tick units: positive X is right and positive Y is down. */
    val horizontalScroll: Float = 0f,
    val verticalScroll: Float = 0f,
)

internal sealed interface ExternalPointerCommand {
    data class MoveAbsolute(
        val x: Int,
        val y: Int,
        val buttons: Set<MouseButton>,
    ) : ExternalPointerCommand

    data class MoveRelative(
        val deltaX: Int,
        val deltaY: Int,
        val buttons: Set<MouseButton>,
    ) : ExternalPointerCommand

    data class Button(
        val button: MouseButton,
        val pressed: Boolean,
    ) : ExternalPointerCommand

    data class ScrollVertical(val steps: Int) : ExternalPointerCommand

    data class ScrollHorizontal(val steps: Int) : ExternalPointerCommand

    data object ReleaseAll : ExternalPointerCommand
}

private data class RelativeMotionDelta(
    val x: Int,
    val y: Int,
)

private class RelativeMotionAccumulator {
    private var remainderX = 0f
    private var remainderY = 0f

    fun add(deltaX: Float, deltaY: Float): List<RelativeMotionDelta> {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return emptyList()
        remainderX += deltaX
        remainderY += deltaY
        var integralX = remainderX.toInt()
        var integralY = remainderY.toInt()
        remainderX -= integralX
        remainderY -= integralY
        if (integralX == 0 && integralY == 0) return emptyList()

        return buildList {
            while (integralX != 0 || integralY != 0) {
                val stepX = integralX.coerceIn(-MAX_RELATIVE_HID_DELTA, MAX_RELATIVE_HID_DELTA)
                val stepY = integralY.coerceIn(-MAX_RELATIVE_HID_DELTA, MAX_RELATIVE_HID_DELTA)
                add(RelativeMotionDelta(stepX, stepY))
                integralX -= stepX
                integralY -= stepY
            }
        }
    }

    fun reset() {
        remainderX = 0f
        remainderY = 0f
    }
}

internal data class ExternalWheelSteps(
    val horizontal: Int = 0,
    val vertical: Int = 0,
)

internal class ExternalWheelAccumulator(sensitivity: Float) {
    private val sensitivity = normalizeScrollSensitivity(sensitivity)
    private var horizontalRemainder = 0f
    private var verticalRemainder = 0f

    fun add(horizontalTicks: Float, verticalTicks: Float): ExternalWheelSteps {
        if (horizontalTicks.isFinite()) horizontalRemainder -= horizontalTicks * sensitivity
        if (verticalTicks.isFinite()) verticalRemainder -= verticalTicks * sensitivity
        val horizontalSteps = horizontalRemainder.toInt()
        val verticalSteps = verticalRemainder.toInt()
        horizontalRemainder -= horizontalSteps
        verticalRemainder -= verticalSteps
        return ExternalWheelSteps(horizontalSteps, verticalSteps)
    }

    fun reset() {
        horizontalRemainder = 0f
        verticalRemainder = 0f
    }
}

private const val MAX_RELATIVE_HID_DELTA = 127
