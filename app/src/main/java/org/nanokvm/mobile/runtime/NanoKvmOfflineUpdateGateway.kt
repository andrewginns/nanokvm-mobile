package org.nanokvm.mobile.runtime

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.NanoKvmOfflineUpdateException
import org.nanokvm.protocol.NanoKvmOfflineUpdateFailure

internal enum class NanoKvmOfflineUpdatePhase {
    HIDDEN,
    INACTIVE,
    UNSUPPORTED,
    EMPTY,
    REVIEW_REQUIRED,
    UPLOADING,
    ACKNOWLEDGED_RESTARTING,
    DEFINITE_FAILURE,
    OUTCOME_UNKNOWN,
    AUTHENTICATION_EXPIRED,
    SESSION_CHANGED,
}

/** Redacted classifications safe to retain in Compose state and diagnostics. */
internal enum class NanoKvmOfflineUpdateError {
    INVALID_SELECTION,
    STALE_REVIEW,
    UNSUPPORTED,
    SOURCE_UNAVAILABLE,
    AUTHENTICATION_EXPIRED,
    SERVER_REJECTED,
    INVALID_RESPONSE,
    CONNECTION,
    CANCELLED_AFTER_DISPATCH,
    SESSION_CHANGED,
    UNEXPECTED,
}

internal enum class NanoKvmOfflineUpdateGuidance {
    NONE,
    SELECT_PACKAGE,
    SELECT_PACKAGE_AGAIN,
    RECONNECT_AND_VERIFY_VERSION,
    REAUTHENTICATE,
}

/**
 * Exact high-risk facts reviewed before dispatch. Identity, not value equality, authorizes start.
 */
internal class NanoKvmOfflineUpdateReview internal constructor(
    val destinationAuthority: String,
    val installedVersion: String?,
    val packageVersion: String,
    val packageSizeBytes: Long,
    internal val binding: NanoKvmSessionBinding,
) {
    val expectedFileName: String = "nanokvm_$packageVersion.tar.gz"
    val interruptsRemoteControl: Boolean = true
    val restartsNanoKvmServices: Boolean = true
    val automaticRetryAllowed: Boolean = false

    override fun toString(): String =
        "NanoKvmOfflineUpdateReview(destination=<redacted>, installedVersion=$installedVersion, " +
            "packageVersion=$packageVersion, packageSizeBytes=$packageSizeBytes)"
}

/** UI state contains no URI, filesystem path, archive bytes, opener, or server-provided text. */
internal data class NanoKvmOfflineUpdateUiState(
    val phase: NanoKvmOfflineUpdatePhase = NanoKvmOfflineUpdatePhase.HIDDEN,
    val review: NanoKvmOfflineUpdateReview? = null,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val error: NanoKvmOfflineUpdateError? = null,
    val guidance: NanoKvmOfflineUpdateGuidance = NanoKvmOfflineUpdateGuidance.NONE,
) {
    init {
        require(bytesTransferred >= 0L && totalBytes >= 0L)
        require(totalBytes == 0L || bytesTransferred <= totalBytes)
        require(review == null || phase == NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED)
    }

    val progressFraction: Float?
        get() = totalBytes.takeIf { it > 0L }
            ?.let { (bytesTransferred.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }

    override fun toString(): String =
        "NanoKvmOfflineUpdateUiState(phase=$phase, review=${review != null}, " +
            "bytesTransferred=$bytesTransferred, totalBytes=$totalBytes, error=$error, " +
            "guidance=$guidance)"
}

/**
 * Foreground-, surface-, destination-, and generation-bound owner of one offline update attempt.
 *
 * Selection and approval are identity-scoped. The source is consumed before the coroutine starts,
 * every upload is dispatched at most once, and no failure state retains a replayable action.
 */
internal class NanoKvmOfflineUpdateGateway internal constructor(
    private val port: NanoKvmOfflineUpdatePort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    private val scope: CoroutineScope,
    private val destinationAuthority: String,
    private val installedVersion: String?,
    private val supported: Boolean,
    private val onAuthenticationExpired: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(NanoKvmOfflineUpdateUiState())
    val state: StateFlow<NanoKvmOfflineUpdateUiState> = mutableState.asStateFlow()

    private var closed = false
    private var foreground = false
    private var surfaceVisible = false
    private var selectedSource: NanoKvmOfflineUpdateSource? = null
    private var pendingReview: NanoKvmOfflineUpdateReview? = null
    private var activeUpload: ActiveUpload? = null
    /**
     * A dispatched upload may have reached the appliance even when its request is cancelled or its
     * response is lost. Visibility and foreground changes must not erase that fact: doing so would
     * make the same package selectable again without the required reconnect/version verification.
     */
    private var retainedUnknownOutcome: NanoKvmOfflineUpdateError? = null

    init {
        require(destinationAuthority.isNotBlank()) { "Destination authority must not be blank" }
        require(destinationAuthority == binding.authority) {
            "Displayed destination must match the authenticated session binding"
        }
    }

    fun setForeground(isForeground: Boolean) {
        val detached = synchronized(lock) {
            if (closed || foreground == isForeground) return
            foreground = isForeground
            if (isForeground) {
                if (surfaceVisible) mutableState.value = baseReadyStateLocked()
                null
            } else {
                clearForUiLifecycleLocked(NanoKvmOfflineUpdatePhase.INACTIVE)
            }
        }
        detached?.cancelAndClear("Offline update cleared in background")
    }

    fun setSurfaceVisible(visible: Boolean) {
        val detached = synchronized(lock) {
            if (closed || surfaceVisible == visible) return
            surfaceVisible = visible
            if (visible) {
                mutableState.value = baseReadyStateLocked()
                null
            } else {
                clearForUiLifecycleLocked(NanoKvmOfflineUpdatePhase.HIDDEN)
            }
        }
        detached?.cancelAndClear("Offline update surface hidden")
    }

    /** Takes ownership of [source], including on local rejection. */
    fun select(source: NanoKvmOfflineUpdateSource): Boolean {
        var prior: NanoKvmOfflineUpdateSource? = null
        val accepted = synchronized(lock) {
            if (retainedUnknownOutcome != null) return@synchronized false
            val rejection = acceptanceErrorLocked()
            if (rejection != null || activeUpload != null) {
                if (activeUpload == null) {
                    mutableState.value = rejectionState(rejection ?: NanoKvmOfflineUpdateError.UNEXPECTED)
                }
                false
            } else {
                prior = selectedSource
                val review = NanoKvmOfflineUpdateReview(
                    destinationAuthority = destinationAuthority,
                    installedVersion = installedVersion,
                    packageVersion = source.packageVersion,
                    packageSizeBytes = source.contentLength,
                    binding = binding,
                )
                selectedSource = source
                pendingReview = review
                mutableState.value = NanoKvmOfflineUpdateUiState(
                    phase = NanoKvmOfflineUpdatePhase.REVIEW_REQUIRED,
                    review = review,
                    totalBytes = source.contentLength,
                )
                true
            }
        }
        prior?.close()
        if (!accepted) source.close()
        return accepted
    }

    /** Clears any prior selection and reports only a redacted local validation failure. */
    fun rejectDocumentSelection() {
        synchronized(lock) {
            if (closed || activeUpload != null || retainedUnknownOutcome != null) return
            selectedSource?.close()
            selectedSource = null
            pendingReview = null
            mutableState.value = rejectionState(NanoKvmOfflineUpdateError.INVALID_SELECTION)
        }
    }

    /** Dispatches only the exact review object currently shown by this gateway. */
    fun confirmAndStart(review: NanoKvmOfflineUpdateReview): Boolean {
        var jobToStart: Job? = null
        var monitorToStart: Job? = null
        val accepted = synchronized(lock) {
            if (retainedUnknownOutcome != null) return@synchronized false
            val rejection = acceptanceErrorLocked()
            val currentReview = pendingReview
            val source = selectedSource
            if (rejection != null || currentReview !== review || source == null ||
                review.binding != binding
            ) {
                selectedSource?.close()
                selectedSource = null
                pendingReview = null
                mutableState.value = rejectionState(
                    rejection ?: NanoKvmOfflineUpdateError.STALE_REVIEW,
                )
                false
            } else {
                val payload = try {
                    source.consume()
                } catch (_: IllegalStateException) {
                    selectedSource = null
                    pendingReview = null
                    mutableState.value = rejectionState(
                        NanoKvmOfflineUpdateError.INVALID_SELECTION,
                    )
                    return@synchronized false
                }
                selectedSource = null
                pendingReview = null
                val active = ActiveUpload(payload)
                val operation = scope.launch(start = CoroutineStart.LAZY) { runUpload(active) }
                val monitor = scope.launch(start = CoroutineStart.LAZY) { monitorBinding(active) }
                active.job = operation
                active.monitor = monitor
                activeUpload = active
                mutableState.value = NanoKvmOfflineUpdateUiState(
                    phase = NanoKvmOfflineUpdatePhase.UPLOADING,
                    totalBytes = payload.contentLength,
                )
                jobToStart = operation
                monitorToStart = monitor
                true
            }
        }
        if (accepted) {
            jobToStart?.start()
            monitorToStart?.start()
        }
        return accepted
    }

    /** Cancels without retaining a replay action; a dispatched upload becomes outcome-unknown. */
    fun cancelUpload() {
        val detached = synchronized(lock) {
            val active = activeUpload
            if (active == null) {
                selectedSource?.close()
                selectedSource = null
                pendingReview = null
                mutableState.value = baseReadyStateLocked()
                null
            } else {
                activeUpload = null
                mutableState.value = if (active.dispatched.get()) {
                    retainUnknownOutcomeLocked(
                        NanoKvmOfflineUpdateError.CANCELLED_AFTER_DISPATCH,
                    )
                } else {
                    baseReadyStateLocked()
                }
                active
            }
        }
        detached?.cancelAndClear("Offline update cancelled")
    }

    /** Called when the backend replaces or clears the authenticated session generation. */
    fun invalidateSession() {
        val detached = synchronized(lock) {
            if (closed) return
            retainedUnknownOutcome = null
            clearLocked(
                if (surfaceVisible) {
                    NanoKvmOfflineUpdatePhase.SESSION_CHANGED
                } else {
                    NanoKvmOfflineUpdatePhase.HIDDEN
                },
                NanoKvmOfflineUpdateError.SESSION_CHANGED,
            )
        }
        detached?.cancelAndClear("Offline update session invalidated")
    }

    override fun close() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            retainedUnknownOutcome = null
            clearLocked(NanoKvmOfflineUpdatePhase.HIDDEN)
        }
        detached?.cancelAndClear("Offline update gateway closed")
    }

    private suspend fun runUpload(active: ActiveUpload) {
        if (!operationStillAllowed(active)) {
            completeWithoutDispatch(active)
            return
        }
        active.dispatched.set(true)
        try {
            port.upload(active.payload) { transferred, total ->
                recordProgress(active, transferred, total)
            }
            completeAcknowledged(active)
        } catch (_: CancellationException) {
            completeCancellation(active)
        } catch (error: Throwable) {
            completeFailure(active, error)
        } finally {
            active.payload.close()
            active.monitor?.cancel()
        }
    }

    private suspend fun monitorBinding(active: ActiveUpload) {
        while (scope.isActive) {
            delay(BINDING_MONITOR_MILLIS)
            if (currentBinding() != binding) {
                val detached = synchronized(lock) {
                    if (activeUpload !== active) return
                    activeUpload = null
                    mutableState.value = NanoKvmOfflineUpdateUiState(
                        phase = NanoKvmOfflineUpdatePhase.SESSION_CHANGED,
                        error = NanoKvmOfflineUpdateError.SESSION_CHANGED,
                        guidance = NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION,
                    )
                    active
                }
                detached.cancelAndClear("Offline update destination generation changed")
                return
            }
        }
    }

    private fun recordProgress(active: ActiveUpload, transferred: Long, total: Long) {
        synchronized(lock) {
            if (activeUpload !== active) return
            if (total != active.payload.contentLength || transferred !in 0..total) {
                active.invalidProgress.set(true)
                return
            }
            mutableState.value = NanoKvmOfflineUpdateUiState(
                phase = NanoKvmOfflineUpdatePhase.UPLOADING,
                bytesTransferred = transferred,
                totalBytes = total,
            )
        }
    }

    private fun completeAcknowledged(active: ActiveUpload) {
        synchronized(lock) {
            if (activeUpload !== active) return
            activeUpload = null
            mutableState.value = when {
                currentBinding() != binding -> NanoKvmOfflineUpdateUiState(
                    phase = NanoKvmOfflineUpdatePhase.SESSION_CHANGED,
                    error = NanoKvmOfflineUpdateError.SESSION_CHANGED,
                    guidance = NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION,
                )
                active.invalidProgress.get() ->
                    unknownState(NanoKvmOfflineUpdateError.INVALID_RESPONSE)
                else -> NanoKvmOfflineUpdateUiState(
                    phase = NanoKvmOfflineUpdatePhase.ACKNOWLEDGED_RESTARTING,
                    bytesTransferred = active.payload.contentLength,
                    totalBytes = active.payload.contentLength,
                    guidance = NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION,
                )
            }
        }
    }

    private fun completeFailure(active: ActiveUpload, error: Throwable) {
        val authenticationExpired = synchronized(lock) {
            if (activeUpload !== active) return
            activeUpload = null
            val failureState = error.toUiState()
            mutableState.value = if (failureState.phase == NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN) {
                retainUnknownOutcomeLocked(
                    failureState.error ?: NanoKvmOfflineUpdateError.UNEXPECTED,
                )
            } else {
                retainedUnknownOutcome = null
                failureState
            }
            error is AuthenticationExpiredException
        }
        if (authenticationExpired && currentBinding() == binding) onAuthenticationExpired()
    }

    private fun completeCancellation(active: ActiveUpload) {
        synchronized(lock) {
            if (activeUpload !== active) return
            activeUpload = null
            mutableState.value = if (active.dispatched.get()) {
                retainUnknownOutcomeLocked(
                    NanoKvmOfflineUpdateError.CANCELLED_AFTER_DISPATCH,
                )
            } else {
                baseReadyStateLocked()
            }
        }
    }

    private fun completeWithoutDispatch(active: ActiveUpload) {
        synchronized(lock) {
            if (activeUpload !== active) return
            activeUpload = null
            mutableState.value = if (currentBinding() == binding) {
                baseReadyStateLocked()
            } else {
                NanoKvmOfflineUpdateUiState(
                    phase = NanoKvmOfflineUpdatePhase.SESSION_CHANGED,
                    error = NanoKvmOfflineUpdateError.SESSION_CHANGED,
                )
            }
        }
        active.payload.close()
        active.monitor?.cancel()
    }

    private fun operationStillAllowed(active: ActiveUpload): Boolean = synchronized(lock) {
        activeUpload === active && !closed && foreground && surfaceVisible && supported &&
            currentBinding() == binding
    }

    private fun acceptanceErrorLocked(): NanoKvmOfflineUpdateError? = when {
        closed || !foreground || !surfaceVisible -> NanoKvmOfflineUpdateError.SESSION_CHANGED
        !supported -> NanoKvmOfflineUpdateError.UNSUPPORTED
        currentBinding() != binding -> NanoKvmOfflineUpdateError.SESSION_CHANGED
        else -> null
    }

    private fun baseReadyStateLocked(): NanoKvmOfflineUpdateUiState = when {
        !surfaceVisible -> NanoKvmOfflineUpdateUiState(NanoKvmOfflineUpdatePhase.HIDDEN)
        !foreground -> NanoKvmOfflineUpdateUiState(NanoKvmOfflineUpdatePhase.INACTIVE)
        !supported -> NanoKvmOfflineUpdateUiState(NanoKvmOfflineUpdatePhase.UNSUPPORTED)
        currentBinding() != binding -> NanoKvmOfflineUpdateUiState(
            phase = NanoKvmOfflineUpdatePhase.SESSION_CHANGED,
            error = NanoKvmOfflineUpdateError.SESSION_CHANGED,
        )
        retainedUnknownOutcome != null -> unknownState(checkNotNull(retainedUnknownOutcome))
        else -> NanoKvmOfflineUpdateUiState(
            phase = NanoKvmOfflineUpdatePhase.EMPTY,
            guidance = NanoKvmOfflineUpdateGuidance.SELECT_PACKAGE,
        )
    }

    private fun clearLocked(
        phase: NanoKvmOfflineUpdatePhase,
        error: NanoKvmOfflineUpdateError? = null,
    ): ActiveUpload? {
        selectedSource?.close()
        selectedSource = null
        pendingReview = null
        val active = activeUpload
        activeUpload = null
        mutableState.value = NanoKvmOfflineUpdateUiState(phase = phase, error = error)
        return active
    }

    private fun clearForUiLifecycleLocked(phase: NanoKvmOfflineUpdatePhase): ActiveUpload? {
        val active = activeUpload
        val unknownError = when {
            active?.dispatched?.get() == true ->
                NanoKvmOfflineUpdateError.CANCELLED_AFTER_DISPATCH
            mutableState.value.phase == NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN ->
                mutableState.value.error ?: NanoKvmOfflineUpdateError.UNEXPECTED
            else -> retainedUnknownOutcome
        }
        val detached = clearLocked(phase)
        retainedUnknownOutcome = unknownError
        return detached
    }

    private fun retainUnknownOutcomeLocked(
        error: NanoKvmOfflineUpdateError,
    ): NanoKvmOfflineUpdateUiState {
        retainedUnknownOutcome = error
        return unknownState(error)
    }

    private fun rejectionState(error: NanoKvmOfflineUpdateError): NanoKvmOfflineUpdateUiState =
        NanoKvmOfflineUpdateUiState(
            phase = when (error) {
                NanoKvmOfflineUpdateError.SESSION_CHANGED ->
                    NanoKvmOfflineUpdatePhase.SESSION_CHANGED
                NanoKvmOfflineUpdateError.UNSUPPORTED ->
                    NanoKvmOfflineUpdatePhase.UNSUPPORTED
                else -> NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE
            },
            error = error,
            guidance = when (error) {
                NanoKvmOfflineUpdateError.SESSION_CHANGED,
                NanoKvmOfflineUpdateError.UNSUPPORTED -> NanoKvmOfflineUpdateGuidance.NONE
                else -> NanoKvmOfflineUpdateGuidance.SELECT_PACKAGE_AGAIN
            },
        )

    private fun Throwable.toUiState(): NanoKvmOfflineUpdateUiState = when (this) {
        is AuthenticationExpiredException -> NanoKvmOfflineUpdateUiState(
            phase = NanoKvmOfflineUpdatePhase.AUTHENTICATION_EXPIRED,
            error = NanoKvmOfflineUpdateError.AUTHENTICATION_EXPIRED,
            guidance = NanoKvmOfflineUpdateGuidance.REAUTHENTICATE,
        )
        is NanoKvmOfflineUpdateException -> when (val safeFailure = failure) {
            NanoKvmOfflineUpdateFailure.LocalSourceUnavailable ->
                rejectionState(NanoKvmOfflineUpdateError.SOURCE_UNAVAILABLE)
            is NanoKvmOfflineUpdateFailure.HttpRejected -> if (safeFailure.outcomeUnknown) {
                unknownState(NanoKvmOfflineUpdateError.CONNECTION)
            } else {
                rejectionState(NanoKvmOfflineUpdateError.SERVER_REJECTED)
            }
            is NanoKvmOfflineUpdateFailure.ApiRejected ->
                unknownState(NanoKvmOfflineUpdateError.SERVER_REJECTED)
            NanoKvmOfflineUpdateFailure.InvalidResponseOutcomeUnknown ->
                unknownState(NanoKvmOfflineUpdateError.INVALID_RESPONSE)
            NanoKvmOfflineUpdateFailure.TransportOutcomeUnknown ->
                unknownState(NanoKvmOfflineUpdateError.CONNECTION)
        }
        is IllegalArgumentException, is IllegalStateException ->
            rejectionState(NanoKvmOfflineUpdateError.INVALID_SELECTION)
        is IOException -> unknownState(NanoKvmOfflineUpdateError.CONNECTION)
        else -> unknownState(NanoKvmOfflineUpdateError.UNEXPECTED)
    }

    private fun unknownState(error: NanoKvmOfflineUpdateError): NanoKvmOfflineUpdateUiState =
        NanoKvmOfflineUpdateUiState(
            phase = NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN,
            error = error,
            guidance = NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION,
        )

    private fun ActiveUpload.cancelAndClear(reason: String) {
        payload.close()
        monitor?.cancel(CancellationException(reason))
        job?.cancel(CancellationException(reason))
    }

    private class ActiveUpload(
        val payload: NanoKvmOfflineUpdatePayload,
    ) {
        val dispatched = AtomicBoolean(false)
        val invalidProgress = AtomicBoolean(false)
        var job: Job? = null
        var monitor: Job? = null

        override fun toString(): String =
            "ActiveUpload(dispatched=${dispatched.get()}, payload=<redacted>)"
    }

    private companion object {
        const val BINDING_MONITOR_MILLIS = 100L
    }
}
