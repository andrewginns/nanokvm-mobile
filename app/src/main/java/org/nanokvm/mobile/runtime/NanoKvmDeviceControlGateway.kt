package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmHdmiState
import org.nanokvm.protocol.NanoKvmMemoryLimitPreset
import org.nanokvm.protocol.NanoKvmMemoryLimitState
import org.nanokvm.protocol.NanoKvmMouseJigglerMode
import org.nanokvm.protocol.NanoKvmMouseJigglerState
import org.nanokvm.protocol.NanoKvmSwapSizePreset
import org.nanokvm.protocol.NanoKvmSwapState

internal data class NanoKvmDeviceControlError(val kind: Kind) {
    enum class Kind {
        SESSION_CHANGED,
        INVALID_REQUEST,
        UNSUPPORTED,
        AUTHENTICATION_EXPIRED,
        CONNECTION,
        SERVER_REJECTED,
        INVALID_RESPONSE,
        UNEXPECTED,
    }
}

internal sealed interface NanoKvmDeviceControlReadResult<out State> {
    data class Success<State>(val state: State) : NanoKvmDeviceControlReadResult<State>
    data class Failure(val error: NanoKvmDeviceControlError) :
        NanoKvmDeviceControlReadResult<Nothing>
}

internal enum class NanoKvmDeviceControlObservation { DESIRED_STATE, OTHER_STATE }

/** Each mutation is dispatched at most once; reconciliation is always a read. */
internal sealed interface NanoKvmDeviceControlMutationResult<out State> {
    data class Applied<State>(val state: State) : NanoKvmDeviceControlMutationResult<State>
    data class AlreadySatisfied<State>(val state: State) : NanoKvmDeviceControlMutationResult<State>
    data class Accepted<State>(
        val state: State?,
        val refreshError: NanoKvmDeviceControlError?,
    ) : NanoKvmDeviceControlMutationResult<State>

    data class Reconciled<State>(
        val state: State,
        val observation: NanoKvmDeviceControlObservation,
        val dispatchError: NanoKvmDeviceControlError,
    ) : NanoKvmDeviceControlMutationResult<State>

    data class Indeterminate<State>(
        val state: State?,
        val dispatchError: NanoKvmDeviceControlError,
        val refreshError: NanoKvmDeviceControlError?,
    ) : NanoKvmDeviceControlMutationResult<State>

    data class Rejected(val error: NanoKvmDeviceControlError) :
        NanoKvmDeviceControlMutationResult<Nothing>

    /** A disruptive one-shot command was acknowledged; the caller must reconnect and verify. */
    data object DisruptiveCommandAccepted : NanoKvmDeviceControlMutationResult<Nothing>
}

/** Session-bound domain gateway for persistent appliance and capture controls. */
internal class NanoKvmDeviceControlGateway internal constructor(
    private val port: NanoKvmDeviceControlPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
) {
    private val operationMutex = Mutex()

    suspend fun refreshHdmi(): NanoKvmDeviceControlReadResult<NanoKvmHdmiState> =
        operationMutex.withLock { readBound(port::hdmiState) }

    suspend fun refreshMouseJiggler():
        NanoKvmDeviceControlReadResult<NanoKvmMouseJigglerState> =
        operationMutex.withLock { readBound(port::mouseJigglerState) }

    suspend fun refreshMemoryLimit():
        NanoKvmDeviceControlReadResult<NanoKvmMemoryLimitState> =
        operationMutex.withLock { readBound(port::memoryLimitState) }

    suspend fun refreshSwap(): NanoKvmDeviceControlReadResult<NanoKvmSwapState> =
        operationMutex.withLock { readBound(port::swapState) }

    suspend fun setHdmiEnabled(
        enabled: Boolean,
    ): NanoKvmDeviceControlMutationResult<NanoKvmHdmiState> = operationMutex.withLock {
        mutateWithReadback(
            read = port::hdmiState,
            desired = { it.enabled == enabled },
            dispatch = { port.setHdmiEnabled(enabled) },
        )
    }

    suspend fun setMouseJiggler(
        enabled: Boolean,
        mode: NanoKvmMouseJigglerMode,
    ): NanoKvmDeviceControlMutationResult<NanoKvmMouseJigglerState> =
        operationMutex.withLock {
            if (mode is NanoKvmMouseJigglerMode.Other) {
                return@withLock rejected(NanoKvmDeviceControlError.Kind.INVALID_REQUEST)
            }
            mutateWithReadback(
                read = port::mouseJigglerState,
                writable = { state -> state.mode !is NanoKvmMouseJigglerMode.Other },
                desired = { state ->
                    state.enabled == enabled && (!enabled || state.mode == mode)
                },
                dispatch = {
                    if (enabled) port.enableMouseJiggler(mode) else port.disableMouseJiggler()
                },
            )
        }

    suspend fun setMemoryLimitEnabled(
        enabled: Boolean,
    ): NanoKvmDeviceControlMutationResult<NanoKvmMemoryLimitState> =
        operationMutex.withLock {
            val preset = NanoKvmMemoryLimitPreset.TAILSCALE_RECOMMENDED
            mutateWithReadback(
                read = port::memoryLimitState,
                writable = { state ->
                    state.preset != null || (!state.enabled && state.limitMegabytes == 0L)
                },
                desired = { state ->
                    state.enabled == enabled &&
                        (!enabled || state.limitMegabytes == preset.megabytes)
                },
                dispatch = {
                    if (enabled) port.setMemoryLimit(preset) else port.disableMemoryLimit()
                },
            )
        }

    suspend fun setSwapSize(
        preset: NanoKvmSwapSizePreset,
    ): NanoKvmDeviceControlMutationResult<NanoKvmSwapState> = operationMutex.withLock {
        mutateWithReadback(
            read = port::swapState,
            writable = { state -> state.preset != null },
            desired = { it.sizeMegabytes == preset.megabytes },
            dispatch = { port.setSwapSize(preset) },
        )
    }

    suspend fun resetHdmi(): NanoKvmDeviceControlMutationResult<Nothing> =
        operationMutex.withLock { dispatchDisruptive(port::resetHdmi) }

    suspend fun enableApplianceTls(): NanoKvmDeviceControlMutationResult<Nothing> =
        operationMutex.withLock { dispatchDisruptive(port::enableApplianceTls) }

    private suspend fun <State> mutateWithReadback(
        read: suspend () -> State,
        writable: (State) -> Boolean = { true },
        desired: (State) -> Boolean,
        dispatch: suspend () -> Unit,
    ): NanoKvmDeviceControlMutationResult<State> {
        requireCurrentBinding()?.let { return NanoKvmDeviceControlMutationResult.Rejected(it) }
        val before = when (val result = readBound(read)) {
            is NanoKvmDeviceControlReadResult.Success -> result.state
            is NanoKvmDeviceControlReadResult.Failure ->
                return NanoKvmDeviceControlMutationResult.Rejected(result.error)
        }
        if (!writable(before)) {
            return rejected(NanoKvmDeviceControlError.Kind.INVALID_REQUEST)
        }
        if (desired(before)) return NanoKvmDeviceControlMutationResult.AlreadySatisfied(before)
        requireCurrentBinding()?.let { return NanoKvmDeviceControlMutationResult.Rejected(it) }

        try {
            dispatch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isDefiniteDeviceControlRejection()) {
                return NanoKvmDeviceControlMutationResult.Rejected(error.toDeviceControlError())
            }
            return when (val refreshed = readBound(read)) {
                is NanoKvmDeviceControlReadResult.Success ->
                    NanoKvmDeviceControlMutationResult.Reconciled(
                        state = refreshed.state,
                        observation = if (desired(refreshed.state)) {
                            NanoKvmDeviceControlObservation.DESIRED_STATE
                        } else {
                            NanoKvmDeviceControlObservation.OTHER_STATE
                        },
                        dispatchError = error.toDeviceControlError(),
                    )
                is NanoKvmDeviceControlReadResult.Failure ->
                    NanoKvmDeviceControlMutationResult.Indeterminate(
                        state = null,
                        dispatchError = error.toDeviceControlError(),
                        refreshError = refreshed.error,
                    )
            }
        }

        return when (val refreshed = readBound(read)) {
            is NanoKvmDeviceControlReadResult.Success -> if (desired(refreshed.state)) {
                NanoKvmDeviceControlMutationResult.Applied(refreshed.state)
            } else {
                NanoKvmDeviceControlMutationResult.Accepted(
                    state = refreshed.state,
                    refreshError = null,
                )
            }
            is NanoKvmDeviceControlReadResult.Failure ->
                NanoKvmDeviceControlMutationResult.Accepted(
                    state = null,
                    refreshError = refreshed.error,
                )
        }
    }

    private suspend fun dispatchDisruptive(
        dispatch: suspend () -> Unit,
    ): NanoKvmDeviceControlMutationResult<Nothing> {
        requireCurrentBinding()?.let { return NanoKvmDeviceControlMutationResult.Rejected(it) }
        return try {
            dispatch()
            NanoKvmDeviceControlMutationResult.DisruptiveCommandAccepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isDefiniteDeviceControlRejection()) {
                NanoKvmDeviceControlMutationResult.Rejected(error.toDeviceControlError())
            } else {
                NanoKvmDeviceControlMutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.toDeviceControlError(),
                    refreshError = null,
                )
            }
        }
    }

    private suspend fun <State> readBound(
        read: suspend () -> State,
    ): NanoKvmDeviceControlReadResult<State> {
        requireCurrentBinding()?.let { return NanoKvmDeviceControlReadResult.Failure(it) }
        val state = try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return NanoKvmDeviceControlReadResult.Failure(error.toDeviceControlError())
        }
        requireCurrentBinding()?.let { return NanoKvmDeviceControlReadResult.Failure(it) }
        return NanoKvmDeviceControlReadResult.Success(state)
    }

    private fun requireCurrentBinding(): NanoKvmDeviceControlError? =
        if (currentBinding() == binding) null else {
            NanoKvmDeviceControlError(NanoKvmDeviceControlError.Kind.SESSION_CHANGED)
        }

    private fun rejected(
        kind: NanoKvmDeviceControlError.Kind,
    ): NanoKvmDeviceControlMutationResult.Rejected =
        NanoKvmDeviceControlMutationResult.Rejected(NanoKvmDeviceControlError(kind))
}

private fun Throwable.toDeviceControlError(): NanoKvmDeviceControlError =
    NanoKvmDeviceControlError(
        when (this) {
            is IllegalArgumentException -> NanoKvmDeviceControlError.Kind.INVALID_REQUEST
            is AuthenticationExpiredException ->
                NanoKvmDeviceControlError.Kind.AUTHENTICATION_EXPIRED
            is ApiResponseException -> NanoKvmDeviceControlError.Kind.SERVER_REJECTED
            is InvalidApiResponseException -> NanoKvmDeviceControlError.Kind.INVALID_RESPONSE
            is HttpResponseException -> if (statusCode.isUnsupportedOptionalEndpoint()) {
                NanoKvmDeviceControlError.Kind.UNSUPPORTED
            } else {
                NanoKvmDeviceControlError.Kind.CONNECTION
            }
            is IOException -> NanoKvmDeviceControlError.Kind.CONNECTION
            else -> NanoKvmDeviceControlError.Kind.UNEXPECTED
        },
    )

private fun Throwable.isDefiniteDeviceControlRejection(): Boolean =
    this is IllegalArgumentException ||
        this is AuthenticationExpiredException ||
        this is ApiResponseException ||
        this is InvalidApiResponseException ||
        (this is HttpResponseException && statusCode.isUnsupportedOptionalEndpoint())

private fun Int.isUnsupportedOptionalEndpoint(): Boolean = this == 404 || this == 405 || this == 501
