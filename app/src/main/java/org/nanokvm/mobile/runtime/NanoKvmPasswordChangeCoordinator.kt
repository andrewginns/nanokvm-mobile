package org.nanokvm.mobile.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ProfilesRepository
import org.nanokvm.mobile.security.CredentialPromptKind
import org.nanokvm.mobile.security.CredentialPromptRequest
import org.nanokvm.mobile.security.CredentialPromptResult
import org.nanokvm.mobile.security.SavedCredentials
import org.nanokvm.mobile.security.StagedCredential

/** Why a password mutation crossed the local authenticated-session boundary. */
internal enum class NanoKvmPasswordChangeSessionEndReason {
    ACKNOWLEDGED,
    OUTCOME_REQUIRES_MANUAL_VERIFICATION,
}

/** Local work which failed after the appliance may already have changed its credentials. */
internal enum class NanoKvmPasswordChangeLocalFailure {
    PROFILE_UPDATE,
    CREDENTIAL_COMMIT,
    CREDENTIAL_DELETE,
    SESSION_END,
}

internal fun interface NanoKvmPasswordChangeSessionTerminator {
    suspend fun endSession(reason: NanoKvmPasswordChangeSessionEndReason)
}

/**
 * A redacted result for one user-authorized password-change attempt.
 *
 * No result retains a username, password, destination, server response, Throwable, or staged
 * credential. [Rejected] is the only server outcome which preserves the authenticated session.
 */
internal sealed interface NanoKvmPasswordChangeResult {
    data class AuthenticationRequired(
        val request: CredentialPromptRequest,
    ) : NanoKvmPasswordChangeResult

    data class Changed(
        val replacementCredentialRequested: Boolean,
        val localFailures: Set<NanoKvmPasswordChangeLocalFailure>,
    ) : NanoKvmPasswordChangeResult

    data class Rejected(
        val errorKind: NanoKvmAdministrationError.Kind,
    ) : NanoKvmPasswordChangeResult

    /** The appliance rejected the mutation because the authenticated token is no longer valid. */
    data object AuthenticationExpired : NanoKvmPasswordChangeResult

    data class ManualVerificationRequired(
        val localFailures: Set<NanoKvmPasswordChangeLocalFailure>,
    ) : NanoKvmPasswordChangeResult

    data object InvalidRequest : NanoKvmPasswordChangeResult
    data object AuthenticationCancelled : NanoKvmPasswordChangeResult
    data object AuthenticationFailed : NanoKvmPasswordChangeResult
    data object LocalPreparationFailed : NanoKvmPasswordChangeResult
    data object StaleSession : NanoKvmPasswordChangeResult
    data object Busy : NanoKvmPasswordChangeResult
    data object IgnoredAuthenticationResult : NanoKvmPasswordChangeResult
}

internal fun interface NanoKvmPasswordMutation {
    suspend fun changePassword(
        username: String,
        password: CharArray,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationAccountSnapshot>
}

/**
 * Owns exactly one password-change lane for one authenticated destination generation.
 *
 * Saving a replacement is deliberately two phase: [begin] creates the Android-authentication
 * request without touching the appliance; [completeAuthentication] encrypts an in-memory staged
 * credential and only then performs the one-shot server mutation. Pending and dispatched
 * operations are never replayed. Every password array accepted by this class is cleared on every
 * terminal path.
 */
internal class NanoKvmPasswordChangeCoordinator internal constructor(
    private val binding: NanoKvmSessionBinding,
    private val profile: HostProfile,
    private val mutation: NanoKvmPasswordMutation,
    private val currentBinding: () -> NanoKvmSessionBinding?,
    private val savedCredentials: SavedCredentials,
    private val profilesRepository: ProfilesRepository,
    private val sessionTerminator: NanoKvmPasswordChangeSessionTerminator,
    private val onAuthenticationExpired: suspend () -> Unit = {},
) {
    private val stateLock = Any()
    private var pendingAuthentication: PendingPasswordChange? = null
    private var operationRunning = false
    private var invalidated = false

    constructor(
        gateway: NanoKvmAdministrationGateway,
        profile: HostProfile,
        currentBinding: () -> NanoKvmSessionBinding?,
        savedCredentials: SavedCredentials,
        profilesRepository: ProfilesRepository,
        sessionTerminator: NanoKvmPasswordChangeSessionTerminator,
        onAuthenticationExpired: suspend () -> Unit = {},
    ) : this(
        binding = gateway.binding,
        profile = profile,
        mutation = NanoKvmPasswordMutation(gateway::changePassword),
        currentBinding = currentBinding,
        savedCredentials = savedCredentials,
        profilesRepository = profilesRepository,
        sessionTerminator = sessionTerminator,
        onAuthenticationExpired = onAuthenticationExpired,
    )

    init {
        require(profile.id == binding.profileId) { "Profile does not match the password gateway" }
        require(profile.authority == binding.authority) {
            "Profile destination does not match the password gateway"
        }
    }

    /**
     * Takes ownership of [password]. When protected saving is requested, [authenticationRequest]
     * must be a fresh app-wide Save request. Its ID becomes the sole public handle for the pending
     * secret; the pending username and password remain private and redacted.
     */
    suspend fun begin(
        username: String,
        password: CharArray,
        saveProtectedCredential: Boolean,
        authenticationRequest: CredentialPromptRequest? = null,
    ): NanoKvmPasswordChangeResult {
        if (!validUsername(username) || !validPassword(password)) {
            password.clearSecret()
            return NanoKvmPasswordChangeResult.InvalidRequest
        }
        if (
            saveProtectedCredential &&
            (authenticationRequest == null || authenticationRequest.kind != CredentialPromptKind.Save)
        ) {
            password.clearSecret()
            return NanoKvmPasswordChangeResult.InvalidRequest
        }
        if (!admitOperation()) {
            password.clearSecret()
            return admissionFailure()
        }

        val candidate = PendingPasswordChange(
            username = username,
            password = password,
            updatedProfile = profile.copy(username = username),
            saveProtectedCredential = saveProtectedCredential,
            authenticationRequestId = authenticationRequest?.id,
        )
        var retainedForAuthentication = false
        return try {
            if (!isCurrent()) {
                NanoKvmPasswordChangeResult.StaleSession
            } else if (saveProtectedCredential) {
                try {
                    savedCredentials.prepareToSave(candidate.updatedProfile)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return NanoKvmPasswordChangeResult.LocalPreparationFailed
                }
                if (!isCurrent() || isInvalidated()) {
                    NanoKvmPasswordChangeResult.StaleSession
                } else {
                    val retained = synchronized(stateLock) {
                        if (invalidated || currentBinding() != binding) {
                            false
                        } else {
                            pendingAuthentication = candidate
                            operationRunning = false
                            true
                        }
                    }
                    if (retained) {
                        retainedForAuthentication = true
                        NanoKvmPasswordChangeResult.AuthenticationRequired(
                            checkNotNull(authenticationRequest),
                        )
                    } else {
                        NanoKvmPasswordChangeResult.StaleSession
                    }
                }
            } else {
                dispatch(candidate, stagedCredential = null)
            }
        } finally {
            if (!retainedForAuthentication) {
                candidate.clear()
                finishOperation()
            }
        }
    }

    /** Completes, cancels, or rejects exactly the matching pending Android-auth request once. */
    suspend fun completeAuthentication(
        result: CredentialPromptResult,
    ): NanoKvmPasswordChangeResult {
        val candidate = synchronized(stateLock) {
            val pending = pendingAuthentication
            if (pending == null || pending.authenticationRequestId != result.requestId) {
                null
            } else {
                pendingAuthentication = null
                operationRunning = true
                pending
            }
        } ?: return NanoKvmPasswordChangeResult.IgnoredAuthenticationResult

        if (result !is CredentialPromptResult.Authenticated) {
            candidate.clear()
            finishOperation()
            return if (result is CredentialPromptResult.Cancelled) {
                NanoKvmPasswordChangeResult.AuthenticationCancelled
            } else {
                NanoKvmPasswordChangeResult.AuthenticationFailed
            }
        }

        var stagedCredential: StagedCredential? = null
        return try {
            if (!isCurrent() || isInvalidated()) {
                NanoKvmPasswordChangeResult.StaleSession
            } else {
                stagedCredential = try {
                    savedCredentials.stageCredential(candidate.updatedProfile, candidate.password)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    return NanoKvmPasswordChangeResult.LocalPreparationFailed
                }
                if (!isCurrent() || isInvalidated()) {
                    NanoKvmPasswordChangeResult.StaleSession
                } else {
                    dispatch(candidate, checkNotNull(stagedCredential))
                }
            }
        } finally {
            stagedCredential?.clear()
            candidate.clear()
            finishOperation()
        }
    }

    /** Invalidates pending work and immediately clears any secret waiting for Android auth. */
    fun invalidate() {
        val pending = synchronized(stateLock) {
            invalidated = true
            pendingAuthentication.also { pendingAuthentication = null }
        }
        pending?.clear()
    }

    private suspend fun dispatch(
        candidate: PendingPasswordChange,
        stagedCredential: StagedCredential?,
    ): NanoKvmPasswordChangeResult {
        if (!isCurrent() || isInvalidated()) return NanoKvmPasswordChangeResult.StaleSession
        val result = try {
            mutation.changePassword(candidate.username, candidate.password)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                completeIndeterminate()
            }
            throw error
        }
        return when (result) {
            NanoKvmAdministrationMutationResult.CredentialsChanged ->
                completeAcknowledged(candidate, stagedCredential)
            is NanoKvmAdministrationMutationResult.Rejected ->
                if (result.error.kind == NanoKvmAdministrationError.Kind.SESSION_CHANGED) {
                    NanoKvmPasswordChangeResult.StaleSession
                } else if (
                    result.error.kind == NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED
                ) {
                    if (!isCurrent() || isInvalidated()) {
                        NanoKvmPasswordChangeResult.StaleSession
                    } else {
                        withContext(NonCancellable) {
                            runCatching { onAuthenticationExpired() }
                        }
                        NanoKvmPasswordChangeResult.AuthenticationExpired
                    }
                } else {
                    NanoKvmPasswordChangeResult.Rejected(result.error.kind)
                }
            is NanoKvmAdministrationMutationResult.Indeterminate -> completeIndeterminate()
            else -> completeIndeterminate()
        }
    }

    private suspend fun completeAcknowledged(
        candidate: PendingPasswordChange,
        stagedCredential: StagedCredential?,
    ): NanoKvmPasswordChangeResult = withContext(NonCancellable) {
        val failures = linkedSetOf<NanoKvmPasswordChangeLocalFailure>()
        val profileUpdated = runCatching {
            profilesRepository.upsert(candidate.updatedProfile)
        }.onFailure {
            failures += NanoKvmPasswordChangeLocalFailure.PROFILE_UPDATE
        }.isSuccess

        if (candidate.saveProtectedCredential && profileUpdated && stagedCredential != null) {
            runCatching {
                savedCredentials.commit(stagedCredential)
            }.onFailure {
                failures += NanoKvmPasswordChangeLocalFailure.CREDENTIAL_COMMIT
                deleteCredentialRecordingFailure(failures)
            }
        } else {
            // A failed profile update would make the username-bound staged envelope unusable.
            // Removing the prior password is safer than retaining a credential the server rejected.
            deleteCredentialRecordingFailure(failures)
        }

        runCatching {
            sessionTerminator.endSession(NanoKvmPasswordChangeSessionEndReason.ACKNOWLEDGED)
        }.onFailure {
            failures += NanoKvmPasswordChangeLocalFailure.SESSION_END
        }
        NanoKvmPasswordChangeResult.Changed(
            replacementCredentialRequested = candidate.saveProtectedCredential,
            localFailures = failures,
        )
    }

    private suspend fun completeIndeterminate(): NanoKvmPasswordChangeResult =
        withContext(NonCancellable) {
            val failures = linkedSetOf<NanoKvmPasswordChangeLocalFailure>()
            deleteCredentialRecordingFailure(failures)
            runCatching {
                sessionTerminator.endSession(
                    NanoKvmPasswordChangeSessionEndReason.OUTCOME_REQUIRES_MANUAL_VERIFICATION,
                )
            }.onFailure {
                failures += NanoKvmPasswordChangeLocalFailure.SESSION_END
            }
            NanoKvmPasswordChangeResult.ManualVerificationRequired(failures)
        }

    private suspend fun deleteCredentialRecordingFailure(
        failures: MutableSet<NanoKvmPasswordChangeLocalFailure>,
    ) {
        runCatching {
            savedCredentials.delete(profile.id)
        }.onFailure {
            failures += NanoKvmPasswordChangeLocalFailure.CREDENTIAL_DELETE
        }
    }

    private fun admitOperation(): Boolean = synchronized(stateLock) {
        if (
            invalidated ||
            operationRunning ||
            pendingAuthentication != null ||
            currentBinding() != binding
        ) {
            false
        } else {
            operationRunning = true
            true
        }
    }

    private fun admissionFailure(): NanoKvmPasswordChangeResult = synchronized(stateLock) {
        if (invalidated || currentBinding() != binding) {
            NanoKvmPasswordChangeResult.StaleSession
        } else {
            NanoKvmPasswordChangeResult.Busy
        }
    }

    private fun finishOperation() {
        synchronized(stateLock) {
            operationRunning = false
        }
    }

    private fun isCurrent(): Boolean = currentBinding() == binding

    private fun isInvalidated(): Boolean = synchronized(stateLock) { invalidated }

    private class PendingPasswordChange(
        val username: String,
        val password: CharArray,
        val updatedProfile: HostProfile,
        val saveProtectedCredential: Boolean,
        val authenticationRequestId: Long?,
    ) {
        fun clear() = password.clearSecret()

        override fun toString(): String =
            "PendingPasswordChange(username=<redacted>, password=<redacted>, " +
                "saveProtectedCredential=$saveProtectedCredential, " +
                "authenticationRequestId=$authenticationRequestId)"
    }
}

private fun CharArray.clearSecret() {
    fill('\u0000')
}

private fun validUsername(value: String): Boolean =
    value.isNotEmpty() &&
        value == value.trim() &&
        value.encodeToByteArray().size <= MAX_ACCOUNT_UTF8_BYTES &&
        value.none(Char::isISOControl) &&
        value.none { it in INVALID_ACCOUNT_CHARACTERS }

private fun validPassword(value: CharArray): Boolean {
    if (value.isEmpty() || value.size > MAX_ACCOUNT_CHARS) return false
    var utf8Bytes = 0
    var index = 0
    while (index < value.size) {
        val character = value[index]
        utf8Bytes += when {
            character.isHighSurrogate() -> {
                if (index + 1 >= value.size || !value[index + 1].isLowSurrogate()) return false
                index += 1
                4
            }
            character.isLowSurrogate() -> return false
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            else -> 3
        }
        if (character in INVALID_ACCOUNT_CHARACTERS) return false
        if (utf8Bytes > MAX_ACCOUNT_UTF8_BYTES) return false
        index += 1
    }
    return true
}

private const val MAX_ACCOUNT_CHARS = 256
private const val MAX_ACCOUNT_UTF8_BYTES = 256
private val INVALID_ACCOUNT_CHARACTERS = setOf('\'', '"', '\\', '/')
