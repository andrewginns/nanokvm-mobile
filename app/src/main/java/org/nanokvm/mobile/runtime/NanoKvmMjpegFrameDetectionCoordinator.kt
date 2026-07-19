package org.nanokvm.mobile.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the app-local MJPEG frame-detection intent and its replay-free appliance writes.
 *
 * The NanoKVM API does not expose readable state for this setting. Consequently an app-settings
 * observation only updates [preferenceEnabled]; only [setEnabledByUser] sends the explicit toggle.
 * Startup pauses share the same ordered queue, are generation-bound, and are never retried.
 */
internal class NanoKvmMjpegFrameDetectionCoordinator(
    private val scope: CoroutineScope,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    private val onAuthenticationExpired: (NanoKvmSessionBinding) -> Unit,
    private val onRejected: (String) -> Unit,
) {
    private val lock = Any()
    private val lifecycle = SessionBoundFeatureLifecycle<NanoKvmMjpegFrameDetectionGateway>()
    private val startupGate = MjpegFrameDetectionStartupGate()
    private val jobs = linkedSetOf<Job>()
    private var commandTail: Job? = null

    @Volatile
    private var preferenceEnabled = false

    fun install(activeSession: AuthenticatedNanoKvmSession, sessionGeneration: Long) {
        val binding = NanoKvmSessionBinding(
            profileId = activeSession.profileId,
            authority = activeSession.authority,
            sessionGeneration = sessionGeneration,
        )
        install(binding) {
            activeSession.createMjpegFrameDetectionGateway(
                sessionGeneration = sessionGeneration,
                currentBinding = currentBinding,
            )
        }
    }

    internal fun install(
        binding: NanoKvmSessionBinding,
        createGateway: () -> NanoKvmMjpegFrameDetectionGateway,
    ) {
        val staleJobs = synchronized(lock) {
            val detached = jobs.toList()
            jobs.clear()
            commandTail = null
            lifecycle.clear()
            startupGate.installGeneration(binding.sessionGeneration)
            lifecycle.install(binding, createGateway)
            detached
        }
        staleJobs.forEach {
            it.cancel(CancellationException("MJPEG frame-detection session replaced"))
        }
    }

    /** Updates local intent only. Settings collection must never replay a server write. */
    fun setPreference(enabled: Boolean) {
        preferenceEnabled = enabled
    }

    /** Enqueues one and only one write attempt for this explicit user change. */
    fun setEnabledByUser(enabled: Boolean) {
        preferenceEnabled = enabled
        val installed = installedGateway() ?: run {
            if (currentBinding() != null) {
                onRejected("MJPEG frame detection is unavailable for this session.")
            }
            return
        }
        enqueue(installed.binding) { installed.gateway.setEnabled(enabled) }
    }

    /** Re-arms a pause when a video session is recreated without changing login generation. */
    fun onVideoSessionStarting() {
        synchronized(lock) {
            lifecycle.binding()?.sessionGeneration?.let(startupGate::leaveMjpeg)
        }
    }

    /** Coalesces MJPEG connecting/fallback/streaming callbacks into one bounded pause request. */
    fun onMjpegActivation() {
        val binding = currentBinding() ?: return
        val installed = synchronized(lock) {
            val installedBinding = lifecycle.binding()
            if (
                installedBinding != binding ||
                !startupGate.claimPause(binding.sessionGeneration, preferenceEnabled)
            ) {
                return
            }
            lifecycle.resolve(binding)?.let { InstalledGateway(binding, it) }
        } ?: return
        enqueue(installed.binding) { installed.gateway.pauseForMjpegStartup() }
    }

    /** A non-MJPEG activation or stopped stream starts a new pause-eligible activation. */
    fun onMjpegInactive() {
        synchronized(lock) {
            lifecycle.binding()?.sessionGeneration?.let(startupGate::leaveMjpeg)
        }
    }

    fun clear() {
        val detached = synchronized(lock) {
            lifecycle.clear()
            startupGate.clear()
            commandTail = null
            jobs.toList().also { jobs.clear() }
        }
        detached.forEach {
            it.cancel(CancellationException("MJPEG frame-detection session invalidated"))
        }
    }

    private fun installedGateway(): InstalledGateway? {
        val binding = currentBinding() ?: return null
        return synchronized(lock) {
            lifecycle.resolve(binding)?.let { InstalledGateway(binding, it) }
        }
    }

    private fun enqueue(
        binding: NanoKvmSessionBinding,
        operation: suspend () -> MjpegFrameDetectionResult,
    ) {
        val created = synchronized(lock) {
            val previous = commandTail
            scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                val result = operation()
                handleResult(binding, result)
            }.also { job ->
                commandTail = job
                jobs += job
                job.invokeOnCompletion {
                    synchronized(lock) {
                        jobs -= job
                        if (commandTail === job) commandTail = null
                    }
                }
            }
        }
        created.start()
    }

    private fun handleResult(
        binding: NanoKvmSessionBinding,
        result: MjpegFrameDetectionResult,
    ) {
        if (result is MjpegFrameDetectionResult.Acknowledged || currentBinding() != binding) return
        val failure = (result as MjpegFrameDetectionResult.Rejected).failure
        when (failure) {
            MjpegFrameDetectionFailure.SESSION_CHANGED -> Unit
            MjpegFrameDetectionFailure.AUTHENTICATION_EXPIRED -> onAuthenticationExpired(binding)
            MjpegFrameDetectionFailure.SERVER_REJECTED,
            MjpegFrameDetectionFailure.CONNECTION,
            MjpegFrameDetectionFailure.INVALID_RESPONSE,
            MjpegFrameDetectionFailure.UNEXPECTED -> onRejected(
                "NanoKVM did not acknowledge the MJPEG frame-detection request. " +
                    "The request was not retried.",
            )
        }
    }

    private data class InstalledGateway(
        val binding: NanoKvmSessionBinding,
        val gateway: NanoKvmMjpegFrameDetectionGateway,
    )
}
