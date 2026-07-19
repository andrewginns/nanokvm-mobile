package org.nanokvm.mobile.runtime

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmWifiAccessPointAuthorization
import org.nanokvm.protocol.NanoKvmWifiCredentials
import org.nanokvm.protocol.NanoKvmWifiOperationException
import org.nanokvm.protocol.TlsMode

internal enum class NanoKvmWifiAccessPointOnboardingError {
    SESSION_CHANGED,
    INVALID_REQUEST,
    CONNECTION,
    REJECTED,
    INVALID_RESPONSE,
    UNEXPECTED,
}

internal sealed interface NanoKvmWifiAccessPointOnboardingResult {
    data object Applied : NanoKvmWifiAccessPointOnboardingResult
    data class Indeterminate(
        val error: NanoKvmWifiAccessPointOnboardingError,
    ) : NanoKvmWifiAccessPointOnboardingResult
    data class Rejected(
        val error: NanoKvmWifiAccessPointOnboardingError,
    ) : NanoKvmWifiAccessPointOnboardingResult
}

internal interface NanoKvmWifiAccessPointOnboardingSession : Closeable {
    suspend fun verifyAccessPointPassword(
        password: CharArray,
    ): NanoKvmWifiAccessPointAuthorization

    suspend fun connect(
        credentials: NanoKvmWifiCredentials,
        authorization: NanoKvmWifiAccessPointAuthorization,
    )
}

internal fun interface NanoKvmWifiAccessPointOnboardingSessionFactory {
    fun create(endpoint: NanoKvmEndpoint): NanoKvmWifiAccessPointOnboardingSession
}

internal object NanoKvmProtocolWifiAccessPointOnboardingSessionFactory :
    NanoKvmWifiAccessPointOnboardingSessionFactory {
    override fun create(endpoint: NanoKvmEndpoint): NanoKvmWifiAccessPointOnboardingSession {
        val client = NanoKvmClient.create(endpoint, TlsMode.SystemTrusted)
        return object : NanoKvmWifiAccessPointOnboardingSession {
            override suspend fun verifyAccessPointPassword(
                password: CharArray,
            ): NanoKvmWifiAccessPointAuthorization =
                client.api.verifyWifiAccessPointPassword(password)

            override suspend fun connect(
                credentials: NanoKvmWifiCredentials,
                authorization: NanoKvmWifiAccessPointAuthorization,
            ) = client.api.connectWifiInAccessPointMode(credentials, authorization)

            override fun close() = client.close()
        }
    }
}

/**
 * One foreground profile-screen AP-onboarding generation.
 *
 * The endpoint and both passwords are caller-supplied, used for one verify/connect sequence, and
 * never retained in a state model. A lost connection response is reported as indeterminate and is
 * never replayed; NanoKVM 2.4.3 has no scan route, so the target SSID is always manual.
 */
internal class NanoKvmWifiAccessPointOnboardingGateway(
    private val generation: Long,
    private val currentGeneration: () -> Long?,
    private val sessionFactory: NanoKvmWifiAccessPointOnboardingSessionFactory =
        NanoKvmProtocolWifiAccessPointOnboardingSessionFactory,
) {
    private val operationMutex = Mutex()

    suspend fun connect(
        endpointInput: String,
        apPassword: CharArray,
        targetSsid: String,
        targetPassword: CharArray,
    ): NanoKvmWifiAccessPointOnboardingResult = operationMutex.withLock {
        var writeDispatched = false
        try {
            if (currentGeneration() != generation) {
                return@withLock NanoKvmWifiAccessPointOnboardingResult.Rejected(
                    NanoKvmWifiAccessPointOnboardingError.SESSION_CHANGED,
                )
            }
            val endpoint = try {
                NanoKvmEndpoint.parse(endpointInput)
            } catch (_: IllegalArgumentException) {
                return@withLock NanoKvmWifiAccessPointOnboardingResult.Rejected(
                    NanoKvmWifiAccessPointOnboardingError.INVALID_REQUEST,
                )
            }
            val session = try {
                sessionFactory.create(endpoint)
            } catch (_: Throwable) {
                return@withLock NanoKvmWifiAccessPointOnboardingResult.Rejected(
                    NanoKvmWifiAccessPointOnboardingError.CONNECTION,
                )
            }
            session.use { activeSession ->
                val authorization = activeSession.verifyAccessPointPassword(apPassword)
                authorization.use {
                    if (currentGeneration() != generation) {
                        return@withLock NanoKvmWifiAccessPointOnboardingResult.Rejected(
                            NanoKvmWifiAccessPointOnboardingError.SESSION_CHANGED,
                        )
                    }
                    NanoKvmWifiCredentials(targetSsid, targetPassword).use { credentials ->
                        writeDispatched = true
                        activeSession.connect(credentials, authorization)
                    }
                }
            }
            if (currentGeneration() == generation) {
                NanoKvmWifiAccessPointOnboardingResult.Applied
            } else {
                NanoKvmWifiAccessPointOnboardingResult.Indeterminate(
                    NanoKvmWifiAccessPointOnboardingError.SESSION_CHANGED,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val safeError = error.toOnboardingError()
            if (writeDispatched && !error.isDefiniteOnboardingRejection()) {
                NanoKvmWifiAccessPointOnboardingResult.Indeterminate(safeError)
            } else {
                NanoKvmWifiAccessPointOnboardingResult.Rejected(safeError)
            }
        } finally {
            apPassword.fill('\u0000')
            targetPassword.fill('\u0000')
        }
    }
}

private fun Throwable.toOnboardingError(): NanoKvmWifiAccessPointOnboardingError = when (this) {
    is IllegalArgumentException -> NanoKvmWifiAccessPointOnboardingError.INVALID_REQUEST
    is NanoKvmWifiOperationException -> when {
        apiCode != null || httpStatus in 400..499 ->
            NanoKvmWifiAccessPointOnboardingError.REJECTED
        else -> NanoKvmWifiAccessPointOnboardingError.CONNECTION
    }
    is InvalidApiResponseException -> NanoKvmWifiAccessPointOnboardingError.INVALID_RESPONSE
    is IOException -> NanoKvmWifiAccessPointOnboardingError.CONNECTION
    else -> NanoKvmWifiAccessPointOnboardingError.UNEXPECTED
}

private fun Throwable.isDefiniteOnboardingRejection(): Boolean =
    this is IllegalArgumentException ||
        (this is NanoKvmWifiOperationException &&
            (apiCode != null || httpStatus in 400..499))
