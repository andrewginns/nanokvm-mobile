package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmApi

internal enum class MjpegFrameDetectionFailure {
    SESSION_CHANGED,
    AUTHENTICATION_EXPIRED,
    SERVER_REJECTED,
    CONNECTION,
    INVALID_RESPONSE,
    UNEXPECTED,
}

/** A write-only setting: success means the exact request was acknowledged, never read back. */
internal sealed interface MjpegFrameDetectionResult {
    data object Acknowledged : MjpegFrameDetectionResult
    data class Rejected(val failure: MjpegFrameDetectionFailure) : MjpegFrameDetectionResult
}

internal interface NanoKvmMjpegFrameDetectionPort {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun pause(durationSeconds: Int)
}

internal class NanoKvmProtocolMjpegFrameDetectionPort(
    private val api: NanoKvmApi,
) : NanoKvmMjpegFrameDetectionPort {
    override suspend fun setEnabled(enabled: Boolean) = api.setMjpegFrameDetectionEnabled(enabled)

    override suspend fun pause(durationSeconds: Int) =
        api.temporarilyPauseMjpegFrameDetection(durationSeconds)
}

/**
 * Session-bound, replay-free owner for NanoKVM's MJPEG frame-difference writes. The appliance has
 * no corresponding read endpoint, so the Android preference remains the explicitly labelled
 * source of intent and a failure never changes that preference silently.
 */
internal class NanoKvmMjpegFrameDetectionGateway(
    private val port: NanoKvmMjpegFrameDetectionPort,
    private val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
) {
    private val operationMutex = Mutex()

    suspend fun setEnabled(enabled: Boolean): MjpegFrameDetectionResult =
        dispatch { port.setEnabled(enabled) }

    suspend fun pauseForMjpegStartup(durationSeconds: Int = DEFAULT_STARTUP_PAUSE_SECONDS):
        MjpegFrameDetectionResult {
        require(durationSeconds in 1..MAXIMUM_PAUSE_SECONDS) {
            "Frame-detection pause must be between 1 and 30 seconds"
        }
        return dispatch { port.pause(durationSeconds) }
    }

    private suspend fun dispatch(block: suspend () -> Unit): MjpegFrameDetectionResult =
        operationMutex.withLock {
            if (currentBinding() != binding) {
                return@withLock MjpegFrameDetectionResult.Rejected(
                    MjpegFrameDetectionFailure.SESSION_CHANGED,
                )
            }
            try {
                block()
                if (currentBinding() == binding) {
                    MjpegFrameDetectionResult.Acknowledged
                } else {
                    MjpegFrameDetectionResult.Rejected(
                        MjpegFrameDetectionFailure.SESSION_CHANGED,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                MjpegFrameDetectionResult.Rejected(error.toFrameDetectionFailure())
            }
        }

    companion object {
        const val DEFAULT_STARTUP_PAUSE_SECONDS = 10
        private const val MAXIMUM_PAUSE_SECONDS = 30
    }
}

/**
 * Synchronous duplicate gate for video callbacks.
 *
 * A fallback commonly emits both `FallingBack(to=MJPEG)` and `Streaming(MJPEG)`. Only the first
 * callback may request the ten-second appliance pause. A new non-MJPEG activation or authenticated
 * generation re-arms the gate; disabled preference observations never consume the opportunity.
 */
internal class MjpegFrameDetectionStartupGate {
    private var generation: Long? = null
    private var pauseIssuedForActivation = false

    @Synchronized
    fun installGeneration(sessionGeneration: Long) {
        require(sessionGeneration >= 0L)
        generation = sessionGeneration
        pauseIssuedForActivation = false
    }

    @Synchronized
    fun leaveMjpeg(sessionGeneration: Long) {
        if (generation == sessionGeneration) pauseIssuedForActivation = false
    }

    @Synchronized
    fun claimPause(sessionGeneration: Long, enabled: Boolean): Boolean {
        if (generation != sessionGeneration || !enabled || pauseIssuedForActivation) return false
        pauseIssuedForActivation = true
        return true
    }

    @Synchronized
    fun clear() {
        generation = null
        pauseIssuedForActivation = false
    }
}

internal fun AuthenticatedNanoKvmSession.createMjpegFrameDetectionGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
): NanoKvmMjpegFrameDetectionGateway = NanoKvmMjpegFrameDetectionGateway(
    port = NanoKvmProtocolMjpegFrameDetectionPort(client.api),
    binding = NanoKvmSessionBinding(profileId, authority, sessionGeneration),
    currentBinding = currentBinding,
)

private fun Throwable.toFrameDetectionFailure(): MjpegFrameDetectionFailure = when (this) {
    is AuthenticationExpiredException -> MjpegFrameDetectionFailure.AUTHENTICATION_EXPIRED
    is ApiResponseException -> MjpegFrameDetectionFailure.SERVER_REJECTED
    is InvalidApiResponseException -> MjpegFrameDetectionFailure.INVALID_RESPONSE
    is IOException, is HttpResponseException -> MjpegFrameDetectionFailure.CONNECTION
    else -> MjpegFrameDetectionFailure.UNEXPECTED
}
