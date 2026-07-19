package org.nanokvm.mobile.security

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Non-secret description of a system-authentication request. */
data class CredentialPromptRequest(
    val id: Long,
    val kind: CredentialPromptKind,
    val profileName: String,
)

enum class CredentialPromptKind { Unlock, Save }

sealed interface CredentialPromptResult {
    val requestId: Long

    data class Authenticated(override val requestId: Long) : CredentialPromptResult
    data class Cancelled(override val requestId: Long) : CredentialPromptResult
    data class Failed(override val requestId: Long, val message: String) : CredentialPromptResult
}

/**
 * Activity-retained prompt routing that stores only a typed request ID. It never retains a UI
 * callback, profile address, password, Activity, Fragment, Context, or BiometricPrompt.
 */
internal class CredentialAuthenticationCoordinator : ViewModel() {
    private var nextHostToken = 0L
    private var activeHostToken: Long? = null
    private var pendingRequest: CredentialPromptRequest? = null
    private val mutableResults = MutableSharedFlow<CredentialPromptResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val results: SharedFlow<CredentialPromptResult> = mutableResults.asSharedFlow()

    @Synchronized
    fun reserveHostToken(): Long = ++nextHostToken

    @Synchronized
    fun activateHost(hostToken: Long) {
        require(hostToken in 1..nextHostToken) { "Host token was not reserved by this coordinator" }
        activeHostToken = hostToken
    }

    @Synchronized
    fun begin(hostToken: Long, request: CredentialPromptRequest): PromptBeginResult {
        if (activeHostToken != hostToken) return PromptBeginResult.StaleHost
        val pending = pendingRequest
        if (pending != null) {
            return if (pending.id == request.id) {
                PromptBeginResult.AlreadyActive
            } else {
                PromptBeginResult.Busy
            }
        }
        pendingRequest = request
        return PromptBeginResult.Started
    }

    @Synchronized
    fun complete(hostToken: Long, result: CredentialPromptResult): Boolean {
        if (activeHostToken != hostToken) return false
        val pending = pendingRequest ?: return false
        if (pending.id != result.requestId) return false
        pendingRequest = null
        mutableResults.tryEmit(result)
        return true
    }

    @Synchronized
    fun pendingRequestId(hostToken: Long): Long? =
        pendingRequest?.id?.takeIf { activeHostToken == hostToken }

    @Synchronized
    fun cancel(hostToken: Long, message: String? = null): CredentialPromptRequest? {
        if (activeHostToken != hostToken) return null
        return removeAndReport(message)
    }

    @Synchronized
    fun cancelAll(): CredentialPromptRequest? = removeAndReport(null)

    @Synchronized
    override fun onCleared() {
        pendingRequest = null
        activeHostToken = null
    }

    private fun removeAndReport(message: String?): CredentialPromptRequest? {
        val request = pendingRequest ?: return null
        pendingRequest = null
        mutableResults.tryEmit(
            if (message == null) {
                CredentialPromptResult.Cancelled(request.id)
            } else {
                CredentialPromptResult.Failed(request.id, message)
            },
        )
        return request
    }
}

internal enum class PromptBeginResult { Started, AlreadyActive, Busy, StaleHost }
