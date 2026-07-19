package org.nanokvm.mobile.runtime

/** Immutable position in a [PollingBackoffPolicy] schedule. */
@ConsistentCopyVisibility
internal data class PollingBackoffState internal constructor(
    internal val failureLevel: Int = 0,
) {
    init {
        require(failureLevel >= 0) { "Polling backoff failure level must not be negative" }
    }
}

/**
 * Deterministic retry cadence for a foreground status poll.
 *
 * There is deliberately no jitter: a single app instance is the only caller for its appliance,
 * and keeping the policy deterministic makes its behavior straightforward to verify. Callers keep
 * a separate immutable [PollingBackoffState] for each independent poll stream.
 */
internal class PollingBackoffPolicy(
    healthyIntervalMillis: Long = 2_000L,
    maximumIntervalMillis: Long = 30_000L,
    multiplier: Int = 2,
) {
    private val scheduleMillis: List<Long>

    init {
        require(healthyIntervalMillis > 0L) { "Healthy polling interval must be positive" }
        require(maximumIntervalMillis >= healthyIntervalMillis) {
            "Maximum polling interval must not be shorter than the healthy interval"
        }
        require(multiplier >= 2) { "Polling backoff multiplier must be at least two" }

        scheduleMillis = buildList {
            add(healthyIntervalMillis)
            while (last() < maximumIntervalMillis) {
                val current = last()
                val multiplied = if (current > maximumIntervalMillis / multiplier.toLong()) {
                    maximumIntervalMillis
                } else {
                    (current * multiplier).coerceAtMost(maximumIntervalMillis)
                }
                add(multiplied)
            }
        }
    }

    val initialState: PollingBackoffState = PollingBackoffState()

    fun delayMillis(state: PollingBackoffState): Long =
        scheduleMillis[state.failureLevel.coerceAtMost(scheduleMillis.lastIndex)]

    fun afterFailure(state: PollingBackoffState): PollingBackoffState =
        PollingBackoffState(
            failureLevel = if (state.failureLevel >= scheduleMillis.lastIndex) {
                scheduleMillis.lastIndex
            } else {
                state.failureLevel + 1
            },
        )

    fun afterSuccess(): PollingBackoffState = initialState
}
