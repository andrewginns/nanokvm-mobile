package org.nanokvm.mobile.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes REST controls and invalidates queued work when the session generation changes. */
internal class ControlCommandGate {
    private val stateLock = Any()
    private val executionMutex = Mutex()
    private val claimedKeys = mutableSetOf<String>()
    private var generation = 0L

    internal data class Lease(
        val key: String,
        val generation: Long,
    )

    fun claim(key: String): Lease? = synchronized(stateLock) {
        if (!claimedKeys.add(key)) return@synchronized null
        Lease(key, generation)
    }

    fun release(lease: Lease) {
        synchronized(stateLock) { claimedKeys.remove(lease.key) }
    }

    fun invalidate() {
        synchronized(stateLock) { generation++ }
    }

    suspend fun executeIfCurrent(
        lease: Lease,
        block: suspend () -> Unit,
    ): Boolean = executionMutex.withLock {
        val current = synchronized(stateLock) { lease.generation == generation }
        if (!current) return@withLock false
        block()
        true
    }
}
