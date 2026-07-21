package org.nanokvm.mobile.ui.input

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface PointerCaptureState {
    data object Idle : PointerCaptureState
    data object Requesting : PointerCaptureState
    data object Active : PointerCaptureState
    data class Unavailable(val reason: PointerCaptureUnavailableReason) : PointerCaptureState
}

enum class PointerCaptureUnavailableReason {
    HostUnavailable,
    FocusDenied,
    RequestRejected,
}

enum class PointerCaptureReleaseReason {
    User,
    Escape,
    Back,
    FocusLost,
    AppBackgrounded,
    KeyboardOpened,
    PointerModeChanged,
    SessionChanged,
    Disposed,
    System,
}

/**
 * Transient owner for Android pointer capture.
 *
 * The controller is intentionally not saveable: a restored screen must require a fresh focused
 * user action before capturing an external pointer again. [request] and [release] are safe to call
 * repeatedly, and a release callback from Android cannot produce a second remote-input release.
 */
@Stable
class PointerCaptureController {
    var state: PointerCaptureState by mutableStateOf(PointerCaptureState.Idle)
        private set

    var lastReleaseReason: PointerCaptureReleaseReason? by mutableStateOf(null)
        private set

    private var host: PointerCaptureHostBinding? = null

    fun request() {
        if (state == PointerCaptureState.Active || state == PointerCaptureState.Requesting) return
        val attachedHost = host
        if (attachedHost == null) {
            state = PointerCaptureState.Unavailable(
                PointerCaptureUnavailableReason.HostUnavailable,
            )
            return
        }

        lastReleaseReason = null
        state = PointerCaptureState.Requesting
        attachedHost.requestCapture()?.let { reason ->
            if (state == PointerCaptureState.Requesting) {
                state = PointerCaptureState.Unavailable(reason)
            }
        }
    }

    fun release(reason: PointerCaptureReleaseReason = PointerCaptureReleaseReason.User) {
        if (state == PointerCaptureState.Idle) return
        state = PointerCaptureState.Idle
        lastReleaseReason = reason
        host?.releaseCapture()
    }

    /** Returns true only when Escape was consumed to recover local pointer ownership. */
    fun handleEscape(): Boolean {
        if (state != PointerCaptureState.Active && state != PointerCaptureState.Requesting) {
            return false
        }
        release(PointerCaptureReleaseReason.Escape)
        return true
    }

    internal fun attachHost(binding: PointerCaptureHostBinding) {
        if (host === binding) return
        if (host != null) release(PointerCaptureReleaseReason.Disposed)
        host = binding
        if (
            state is PointerCaptureState.Unavailable &&
            (state as PointerCaptureState.Unavailable).reason ==
            PointerCaptureUnavailableReason.HostUnavailable
        ) {
            state = PointerCaptureState.Idle
        }
    }

    internal fun detachHost(binding: PointerCaptureHostBinding) {
        if (host !== binding) return
        release(PointerCaptureReleaseReason.Disposed)
        host = null
    }

    /** Returns whether a newly reported Android capture still has a live owner request. */
    internal fun captureChanged(captured: Boolean): Boolean {
        if (captured) {
            if (state == PointerCaptureState.Idle) return false
            // Android can confirm capture after our timeout has exposed a recoverable Unavailable
            // state. The actual platform ownership is authoritative, so make it releasable again.
            state = PointerCaptureState.Active
            return true
        }
        if (state == PointerCaptureState.Idle) return false
        state = PointerCaptureState.Idle
        lastReleaseReason = PointerCaptureReleaseReason.System
        return true
    }

    internal fun requestRejected() {
        if (state == PointerCaptureState.Requesting) {
            state = PointerCaptureState.Unavailable(
                PointerCaptureUnavailableReason.RequestRejected,
            )
        }
    }
}

internal interface PointerCaptureHostBinding {
    /** Returns null when Android accepted the asynchronous request, otherwise a failure reason. */
    fun requestCapture(): PointerCaptureUnavailableReason?
    fun releaseCapture()
}
