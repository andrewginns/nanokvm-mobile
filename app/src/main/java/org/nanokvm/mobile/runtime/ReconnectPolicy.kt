package org.nanokvm.mobile.runtime

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.NanoKvmException

internal data class ReconnectFailure(
    val cause: Throwable,
    val httpStatus: Int? = null,
)

internal enum class ReconnectFailureDisposition {
    RETRY,
    TERMINAL,
}

internal fun ReconnectFailure.disposition(): ReconnectFailureDisposition {
    httpStatus?.let { status ->
        return when (status) {
            in 500..599 -> ReconnectFailureDisposition.RETRY
            else -> ReconnectFailureDisposition.TERMINAL
        }
    }
    return when {
        cause is CancellationException -> ReconnectFailureDisposition.TERMINAL
        cause.hasCause<SSLException>() || cause.hasCause<CertificateException>() -> {
            ReconnectFailureDisposition.TERMINAL
        }
        cause is AuthenticationExpiredException ||
            cause is ApiResponseException ||
            cause is IllegalArgumentException -> ReconnectFailureDisposition.TERMINAL
        cause is HttpResponseException -> if (cause.statusCode in 500..599) {
            ReconnectFailureDisposition.RETRY
        } else {
            ReconnectFailureDisposition.TERMINAL
        }
        cause is NanoKvmException -> ReconnectFailureDisposition.TERMINAL
        cause is IOException -> ReconnectFailureDisposition.RETRY
        else -> ReconnectFailureDisposition.TERMINAL
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

internal sealed interface ReconnectAttemptResult {
    data object Connected : ReconnectAttemptResult
    data class Failed(val failure: ReconnectFailure) : ReconnectAttemptResult
}

internal sealed interface ReconnectRunResult {
    data object Connected : ReconnectRunResult
    data class Terminal(val failure: ReconnectFailure) : ReconnectRunResult
    data class Exhausted(val failure: ReconnectFailure, val attempts: Int) : ReconnectRunResult
}

internal data class ReconnectProgress(
    val attempt: Int,
    val maximumAttempts: Int,
    val delayMillis: Long,
)

/** Foreground reconnect schedule. No input or control command is retained or replayed. */
internal class ReconnectPolicy(
    private val baseDelaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
    private val jitterFraction: Double = 0.20,
    private val jitterSample: () -> Double = { Random.Default.nextDouble() },
) {
    init {
        require(baseDelaysMillis.isNotEmpty()) { "Reconnect policy must contain at least one attempt" }
        require(baseDelaysMillis.all { it >= 0L }) { "Reconnect delays must not be negative" }
        require(jitterFraction in 0.0..1.0) { "Reconnect jitter must be between zero and one" }
    }

    val maximumAttempts: Int get() = baseDelaysMillis.size

    suspend fun execute(
        immediateFirstAttempt: Boolean,
        wait: suspend (Long) -> Unit = { delay(it) },
        onWaiting: (ReconnectProgress) -> Unit = {},
        attempt: suspend (attemptNumber: Int) -> ReconnectAttemptResult,
    ): ReconnectRunResult {
        var lastFailure: ReconnectFailure? = null
        baseDelaysMillis.indices.forEach { index ->
            val attemptNumber = index + 1
            val delayMillis = if (immediateFirstAttempt && index == 0) {
                0L
            } else {
                jittered(baseDelaysMillis[index])
            }
            onWaiting(ReconnectProgress(attemptNumber, maximumAttempts, delayMillis))
            if (delayMillis > 0L) wait(delayMillis)

            when (val result = attempt(attemptNumber)) {
                ReconnectAttemptResult.Connected -> return ReconnectRunResult.Connected
                is ReconnectAttemptResult.Failed -> {
                    lastFailure = result.failure
                    if (result.failure.disposition() == ReconnectFailureDisposition.TERMINAL) {
                        return ReconnectRunResult.Terminal(result.failure)
                    }
                }
            }
        }
        return ReconnectRunResult.Exhausted(checkNotNull(lastFailure), maximumAttempts)
    }

    private fun jittered(baseDelayMillis: Long): Long {
        if (baseDelayMillis == 0L || jitterFraction == 0.0) return baseDelayMillis
        val sample = jitterSample().coerceIn(0.0, 1.0)
        val multiplier = (1.0 - jitterFraction) + (2.0 * jitterFraction * sample)
        return (baseDelayMillis * multiplier).roundToLong().coerceAtLeast(0L)
    }
}
