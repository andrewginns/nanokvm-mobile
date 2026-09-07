package org.nanokvm.mobile.ui.input

import org.nanokvm.mobile.runtime.MouseButton
import org.nanokvm.mobile.runtime.RemoteInputSink

/** Synchronous UI-thread handoff when another action can change the host's text/caret context. */
class KeyboardContextBoundary {
    private var onInvalidated: (() -> Unit)? = null

    fun register(callback: () -> Unit): () -> Unit {
        onInvalidated = callback
        return {
            // Disposing an older editor must not detach the replacement editor.
            if (onInvalidated === callback) onInvalidated = null
        }
    }

    fun invalidate() {
        onInvalidated?.invoke()
    }
}

/** Keyboard edits keep their context; pointer actions and input releases end it before dispatch. */
internal class ContextInvalidatingInputSink(
    private val delegate: RemoteInputSink,
    private val boundary: KeyboardContextBoundary,
) : RemoteInputSink by delegate {
    override fun moveAbsolute(x: Int, y: Int, buttons: Set<MouseButton>) {
        if (buttons.isNotEmpty()) boundary.invalidate()
        delegate.moveAbsolute(x, y, buttons)
    }

    override fun moveRelative(deltaX: Int, deltaY: Int, buttons: Set<MouseButton>) {
        if (buttons.isNotEmpty()) boundary.invalidate()
        delegate.moveRelative(deltaX, deltaY, buttons)
    }

    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        if (pressed) boundary.invalidate()
        delegate.mouseButton(button, pressed)
    }

    override fun scrollWheel(steps: Int) {
        if (steps != 0) boundary.invalidate()
        delegate.scrollWheel(steps)
    }

    override fun scrollHorizontal(steps: Int) {
        if (steps != 0) boundary.invalidate()
        delegate.scrollHorizontal(steps)
    }

    override fun releaseAllInput() {
        boundary.invalidate()
        delegate.releaseAllInput()
    }
}
