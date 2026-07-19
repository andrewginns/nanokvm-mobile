package org.nanokvm.video

import java.util.concurrent.Executor

/**
 * Delivers at most one current value plus one pending newest value.
 *
 * Superseded or explicitly cleared values are handed to [discard]. Once [deliver] begins it
 * owns the value even if it throws, so it must release any resources that it did not transfer.
 */
internal class LatestFrameDispatcher<T : Any>(
    private val executor: Executor,
    private val deliver: (T) -> Unit,
    private val discard: (T) -> Unit = {},
) {
    private val lock = Any()
    private var pending: T? = null
    private var scheduled = false

    fun offer(value: T) {
        var replaced: T? = null
        val needsSchedule = synchronized(lock) {
            replaced = pending
            pending = value
            if (scheduled) {
                false
            } else {
                scheduled = true
                true
            }
        }
        replaced?.let(::discardSafely)
        if (needsSchedule) scheduleDrain()
    }

    fun clear() {
        val removed = synchronized(lock) {
            val value = pending
            pending = null
            value
        }
        removed?.let(::discardSafely)
    }

    private fun scheduleDrain() {
        try {
            executor.execute(::drain)
        } catch (_: Throwable) {
            val removed = synchronized(lock) {
                scheduled = false
                val value = pending
                pending = null
                value
            }
            removed?.let(::discardSafely)
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val value = pending
                if (value == null) {
                    scheduled = false
                    return
                }
                pending = null
                value
            }
            try {
                deliver(next)
            } catch (_: Throwable) {
                // deliver() owns next once invoked; continue so a bad callback cannot wedge the slot.
            }
        }
    }

    private fun discardSafely(value: T) {
        runCatching { discard(value) }
    }
}
