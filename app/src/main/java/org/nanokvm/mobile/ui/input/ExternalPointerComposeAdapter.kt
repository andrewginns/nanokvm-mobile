package org.nanokvm.mobile.ui.input

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink

/**
 * Raw Compose adapter for external mice. It intentionally consumes only [PointerType.Mouse]
 * samples at the Initial pass so the viewport's touch tap/drag detectors cannot double-dispatch a
 * physical mouse click. Trackpads reinterpreted by Android as touch continue through touch input.
 */
internal suspend fun PointerInputScope.routeExternalMouseInput(
    router: ExternalPointerInputRouter,
    dispatch: (ExternalPointerCommand) -> Unit,
) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val mouseChanges = event.changes.filter { it.type == PointerType.Mouse }
                val primaryChange = mouseChanges.firstOrNull() ?: continue
                val scrollDelta = mouseChanges.fold(androidx.compose.ui.geometry.Offset.Zero) {
                        total,
                        change,
                    ->
                    total + change.scrollDelta
                }
                val positionDelta = primaryChange.positionChangeIgnoreConsumed()
                val sample = ExternalPointerSample(
                    phase = event.toExternalPointerPhase(),
                    position = ExternalPointerPosition(
                        x = primaryChange.position.x,
                        y = primaryChange.position.y,
                    ),
                    deltaX = positionDelta.x,
                    deltaY = positionDelta.y,
                    pressedButtons = event.toRemoteMouseButtons(),
                    horizontalScroll = scrollDelta.x,
                    verticalScroll = scrollDelta.y,
                )
                router.route(sample).forEach(dispatch)
                mouseChanges.forEach(PointerInputChange::consume)
            }
        }
    } finally {
        router.releaseAll().forEach(dispatch)
    }
}

internal fun ExternalPointerCommand.dispatchTo(input: RemoteInputSink) {
    when (this) {
        is ExternalPointerCommand.MoveAbsolute -> input.moveAbsolute(x, y, buttons)
        is ExternalPointerCommand.MoveRelative -> input.moveRelative(deltaX, deltaY, buttons)
        is ExternalPointerCommand.Button -> input.mouseButton(button, pressed)
        is ExternalPointerCommand.ScrollVertical -> input.scrollWheel(steps)
        is ExternalPointerCommand.ScrollHorizontal -> input.scrollHorizontal(steps)
        ExternalPointerCommand.ReleaseAll -> input.releaseAllInput()
    }
}

private fun PointerEvent.toRemoteMouseButtons(): Set<MouseButton> = buildSet {
    if (buttons.isPrimaryPressed) add(MouseButton.Left)
    if (buttons.isSecondaryPressed) add(MouseButton.Right)
    if (buttons.isTertiaryPressed) add(MouseButton.Middle)
    if (buttons.isBackPressed) add(MouseButton.Back)
    if (buttons.isForwardPressed) add(MouseButton.Forward)
}

private fun PointerEvent.toExternalPointerPhase(): ExternalPointerPhase = when (type) {
    PointerEventType.Enter -> ExternalPointerPhase.Enter
    PointerEventType.Move -> ExternalPointerPhase.Move
    PointerEventType.Press -> ExternalPointerPhase.Press
    PointerEventType.Release -> ExternalPointerPhase.Release
    PointerEventType.Scroll -> ExternalPointerPhase.Scroll
    PointerEventType.Exit -> ExternalPointerPhase.Exit
    else -> ExternalPointerPhase.Unknown
}
