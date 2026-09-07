package org.nanokvm.mobile.runtime

import java.util.concurrent.atomic.AtomicBoolean

/** One uninterrupted local typing context, shared with its queued transport operations. */
class RemoteTextEditContext {
    private val valid = AtomicBoolean(true)

    val isValid: Boolean
        get() = valid.get()

    fun invalidate() {
        valid.set(false)
    }
}
