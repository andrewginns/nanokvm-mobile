package org.nanokvm.mobile.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.nanokvm.mobile.data.DEFAULT_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.normalizeScrollSensitivity
import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink
import org.nanokvm.mobile.ui.components.remoteKeyForAndroidKeyCode

/**
 * Hosts the focused Android [View] that owns API-26 pointer capture.
 *
 * This view is deliberately UI-free. The caller owns the explicit capture/release controls and
 * visible state indicator through [PointerCaptureController.state].
 */
@Composable
fun PointerCaptureHost(
    controller: PointerCaptureController,
    input: RemoteInputSink,
    modifier: Modifier = Modifier,
    scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                controller.release(PointerCaptureReleaseReason.AppBackgrounded)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release(PointerCaptureReleaseReason.Disposed)
        }
    }

    AndroidView(
        factory = { context -> CapturedPointerView(context) },
        update = { view ->
            view.bind(controller, input, normalizeScrollSensitivity(scrollSensitivity))
        },
        modifier = modifier,
    )
}

@SuppressLint("ViewConstructor")
private class CapturedPointerView(context: Context) : View(context), PointerCaptureHostBinding {
    private var controller: PointerCaptureController? = null
    private var input: RemoteInputSink? = null
    private var sensitivity: Float = DEFAULT_SCROLL_SENSITIVITY
    private var escapeKeyUpPending = false
    private var router = ExternalPointerInputRouter(
        mode = ExternalPointerRoutingMode.Relative,
        scrollSensitivity = sensitivity,
    )
    private val verifyCapture = Runnable {
        val activeController = controller ?: return@Runnable
        if (activeController.state == PointerCaptureState.Requesting && !hasPointerCapture()) {
            router.releaseAll().forEach(::dispatch)
            activeController.requestRejected()
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnCapturedPointerListener { _, event -> routeCapturedEvent(event) }
    }

    fun bind(
        controller: PointerCaptureController,
        input: RemoteInputSink,
        sensitivity: Float,
    ) {
        if (
            this.controller === controller &&
            this.input === input &&
            this.sensitivity == sensitivity
        ) {
            return
        }
        this.controller?.detachHost(this)
        router.releaseAll().forEach(::dispatch)
        this.controller = controller
        this.input = input
        this.sensitivity = sensitivity
        router = ExternalPointerInputRouter(
            mode = ExternalPointerRoutingMode.Relative,
            scrollSensitivity = sensitivity,
        )
        controller.attachHost(this)
    }

    override fun requestCapture(): PointerCaptureUnavailableReason? {
        if (!isAttachedToWindow || !hasWindowFocus()) {
            return PointerCaptureUnavailableReason.HostUnavailable
        }
        if (!requestFocus()) return PointerCaptureUnavailableReason.FocusDenied

        return runCatching {
            removeCallbacks(verifyCapture)
            requestPointerCapture()
            postDelayed(verifyCapture, CAPTURE_REQUEST_TIMEOUT_MILLIS)
        }.fold(
            onSuccess = { null },
            onFailure = { PointerCaptureUnavailableReason.RequestRejected },
        )
    }

    override fun releaseCapture() {
        removeCallbacks(verifyCapture)
        if (hasPointerCapture()) {
            releasePointerCapture()
        }
        router.releaseAll().forEach(::dispatch)
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        removeCallbacks(verifyCapture)
        if (hasCapture) {
            // A confirmation can race the timeout or a user cancellation. Re-open the router
            // lifecycle so any keys/buttons acquired by a late-but-valid capture are neutralized
            // on teardown; reject capture immediately when no owner request remains.
            router.beginLifecycle()
            if (controller?.captureChanged(captured = true) != true) {
                if (hasPointerCapture()) {
                    releasePointerCapture()
                }
                router.releaseAll().forEach(::dispatch)
            }
            return
        }
        router.releaseAll().forEach(::dispatch)
        controller?.captureChanged(captured = false)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            controller?.release(PointerCaptureReleaseReason.FocusLost)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val captureCanRelease = controller?.state.let {
            it == PointerCaptureState.Active || it == PointerCaptureState.Requesting
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && captureCanRelease) {
            escapeKeyUpPending = true
            if (event.repeatCount == 0) controller?.handleEscape()
            return true
        }
        if (controller?.state != PointerCaptureState.Active || event.isSystem) {
            return super.onKeyDown(keyCode, event)
        }
        val remoteKey = remoteKeyForAndroidKeyCode(keyCode)
            ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) input?.key(remoteKey, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && escapeKeyUpPending) {
            escapeKeyUpPending = false
            return true
        }
        if (controller?.state != PointerCaptureState.Active || event.isSystem) {
            return super.onKeyUp(keyCode, event)
        }
        val remoteKey = remoteKeyForAndroidKeyCode(keyCode)
            ?: return super.onKeyUp(keyCode, event)
        input?.key(remoteKey, false)
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(verifyCapture)
        controller?.detachHost(this)
        controller = null
        input = null
        super.onDetachedFromWindow()
    }

    private fun routeCapturedEvent(event: MotionEvent): Boolean {
        if (!event.isCapturedPointerSource()) return false
        event.toCapturedPointerSamples().forEach { sample ->
            router.route(sample).forEach(::dispatch)
        }
        return true
    }

    private fun dispatch(command: ExternalPointerCommand) {
        input?.let { command.dispatchTo(it) }
    }

}

internal fun MotionEvent.toCapturedPointerSamples(): List<ExternalPointerSample> = buildList {
    for (historyIndex in 0 until historySize) {
        add(
            capturedPointerSample(
                phase = if (actionMasked == MotionEvent.ACTION_SCROLL) {
                    ExternalPointerPhase.Scroll
                } else {
                    ExternalPointerPhase.Move
                },
                deltaX = getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex),
                deltaY = getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex),
                horizontalScroll = getHistoricalAxisValue(MotionEvent.AXIS_HSCROLL, historyIndex),
                verticalScroll = -getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, historyIndex),
            ),
        )
    }
    add(
        capturedPointerSample(
            phase = when (actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_BUTTON_PRESS,
                -> ExternalPointerPhase.Press

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_BUTTON_RELEASE,
                -> ExternalPointerPhase.Release

                MotionEvent.ACTION_SCROLL -> ExternalPointerPhase.Scroll
                MotionEvent.ACTION_CANCEL -> ExternalPointerPhase.Cancel
                else -> ExternalPointerPhase.Move
            },
            deltaX = getAxisValue(MotionEvent.AXIS_RELATIVE_X),
            deltaY = getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
            horizontalScroll = getAxisValue(MotionEvent.AXIS_HSCROLL),
            verticalScroll = -getAxisValue(MotionEvent.AXIS_VSCROLL),
        ),
    )
}

private fun MotionEvent.capturedPointerSample(
    phase: ExternalPointerPhase,
    deltaX: Float,
    deltaY: Float,
    horizontalScroll: Float,
    verticalScroll: Float,
) = ExternalPointerSample(
    phase = phase,
    position = ExternalPointerPosition(0f, 0f),
    deltaX = deltaX,
    deltaY = deltaY,
    pressedButtons = normalizedCapturedButtonState().toRemoteMouseButtons(),
    horizontalScroll = horizontalScroll,
    verticalScroll = verticalScroll,
)

private fun MotionEvent.normalizedCapturedButtonState(): Int = when (actionMasked) {
    MotionEvent.ACTION_BUTTON_PRESS -> buttonState or actionButton
    MotionEvent.ACTION_BUTTON_RELEASE -> buttonState and actionButton.inv()
    else -> buttonState
}

private fun MotionEvent.isCapturedPointerSource(): Boolean =
    isFromSource(InputDevice.SOURCE_MOUSE) ||
        isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
        isFromSource(InputDevice.SOURCE_TOUCHPAD)

internal fun Int.toRemoteMouseButtons(): Set<MouseButton> = buildSet {
    if (this@toRemoteMouseButtons and MotionEvent.BUTTON_PRIMARY != 0) add(MouseButton.Left)
    if (this@toRemoteMouseButtons and MotionEvent.BUTTON_SECONDARY != 0) add(MouseButton.Right)
    if (this@toRemoteMouseButtons and MotionEvent.BUTTON_TERTIARY != 0) add(MouseButton.Middle)
    if (this@toRemoteMouseButtons and MotionEvent.BUTTON_BACK != 0) add(MouseButton.Back)
    if (this@toRemoteMouseButtons and MotionEvent.BUTTON_FORWARD != 0) add(MouseButton.Forward)
}

private const val CAPTURE_REQUEST_TIMEOUT_MILLIS = 750L
