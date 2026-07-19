package org.nanokvm.mobile.runtime

/**
 * Lock-confined ownership state for one paced paste operation.
 *
 * The tracker deliberately contains no clipboard text. Callers must synchronize access using their
 * session state lock.
 */
internal class PasteOperationTracker {
    private var nextToken = 0L
    private var active: Snapshot? = null

    data class Snapshot(
        val token: Long,
        val sentKeystrokes: Int,
        val totalKeystrokes: Int,
        val phase: RemotePastePhase,
        val userInitiatedCancellation: Boolean,
    )

    fun start(totalKeystrokes: Int): Snapshot? {
        require(totalKeystrokes >= 0)
        if (active != null) return null
        return Snapshot(
            token = ++nextToken,
            sentKeystrokes = 0,
            totalKeystrokes = totalKeystrokes,
            phase = RemotePastePhase.Typing,
            userInitiatedCancellation = false,
        ).also { active = it }
    }

    fun progress(token: Long, sentKeystrokes: Int, totalKeystrokes: Int): Snapshot? {
        val current = active?.takeIf { it.token == token } ?: return null
        return current.copy(
            sentKeystrokes = sentKeystrokes,
            totalKeystrokes = totalKeystrokes,
        ).also { active = it }
    }

    fun cancel(token: Long, userInitiated: Boolean): Snapshot? {
        val current = active?.takeIf { it.token == token } ?: return null
        return current.copy(
            phase = RemotePastePhase.Cancelling,
            userInitiatedCancellation = current.userInitiatedCancellation || userInitiated,
        ).also { active = it }
    }

    fun snapshot(token: Long): Snapshot? = active?.takeIf { it.token == token }

    /** Clears ownership only for [token], returning the final acknowledged progress. */
    fun finish(token: Long): Snapshot? {
        val current = active?.takeIf { it.token == token } ?: return null
        active = null
        return current
    }
}
