package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmDnsConfiguration
import org.nanokvm.protocol.NanoKvmDnsMode
import org.nanokvm.protocol.NanoKvmIpAddress
import org.nanokvm.protocol.NanoKvmOledSleepPreset
import org.nanokvm.protocol.NanoKvmWebTitle
import org.nanokvm.protocol.NanoKvmTailscaleActionApproval
import org.nanokvm.protocol.NanoKvmTailscaleCommand
import org.nanokvm.protocol.NanoKvmTailscaleLoginResult
import org.nanokvm.protocol.NanoKvmTailscaleLoginUrl
import org.nanokvm.protocol.NanoKvmTailscaleOperationException
import org.nanokvm.protocol.NanoKvmTailscaleState
import org.nanokvm.protocol.NanoKvmTailscaleStatus
import org.nanokvm.protocol.NanoKvmWifiCredentials
import org.nanokvm.protocol.NanoKvmWifiOperationException

/** Error information which is safe to retain in Compose state or diagnostics. */
internal data class NanoKvmAdministrationError(
    val kind: Kind,
) {
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

internal sealed interface NanoKvmAdministrationReadResult<out State> {
    data class Success<State>(val state: State) : NanoKvmAdministrationReadResult<State>
    data class Failure(val error: NanoKvmAdministrationError) :
        NanoKvmAdministrationReadResult<Nothing>
}

/** Operational consequence which should drive confirmation wording and recovery affordances. */
internal enum class NanoKvmAdministrationImpact {
    ROUTINE,
    SECURITY_ACCESS_CHANGE,
    NAME_OR_NETWORK_ACCESS_CHANGE,
    SERVICE_RESTART,
    APPLIANCE_REBOOT,
    CREDENTIALS_AND_SESSION,
}

/** Safe next-step guidance; it contains no server-provided text or endpoint information. */
internal enum class NanoKvmAdministrationGuidance {
    NONE,
    REFRESH_AUTHORITATIVE_STATE,
    REVIEW_AUTHORITATIVE_STATE,
    RECONNECT_AND_REFRESH,
    REDISCOVER_AND_RECONNECT,
    WAIT_FOR_REBOOT_AND_RECONNECT,
    CLEAR_SAVED_CREDENTIAL_AND_END_SESSION,
    VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
}

internal enum class NanoKvmAdministrationObservation {
    DESIRED_STATE,
    OTHER_STATE,
}

/**
 * At-most-once administration mutation result.
 *
 * Dispatch failures are reconciled only by reads; this type intentionally provides no retry
 * signal. UI code should require a new user confirmation before starting another mutation.
 */
internal sealed interface NanoKvmAdministrationMutationResult<out State> {
    val impact: NanoKvmAdministrationImpact
    val guidance: NanoKvmAdministrationGuidance

    data class Applied<State>(
        val state: State,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.NONE,
    ) : NanoKvmAdministrationMutationResult<State>

    data class AlreadySatisfied<State>(
        val state: State,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.NONE,
    ) : NanoKvmAdministrationMutationResult<State>

    /** The server acknowledged a setting, but readback did not confirm the requested state. */
    data class Accepted<State>(
        val state: State?,
        val refreshError: NanoKvmAdministrationError?,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance,
    ) : NanoKvmAdministrationMutationResult<State>

    /** A failed dispatch was followed by one authoritative read, never another mutation. */
    data class Reconciled<State>(
        val state: State,
        val observation: NanoKvmAdministrationObservation,
        val dispatchError: NanoKvmAdministrationError,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance,
    ) : NanoKvmAdministrationMutationResult<State>

    /** Neither the dispatch response nor an optional readback establishes the final outcome. */
    data class Indeterminate<State>(
        val state: State?,
        val dispatchError: NanoKvmAdministrationError,
        val refreshError: NanoKvmAdministrationError?,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance,
    ) : NanoKvmAdministrationMutationResult<State>

    data class Rejected(
        val error: NanoKvmAdministrationError,
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.NONE,
    ) : NanoKvmAdministrationMutationResult<Nothing>

    /**
     * Password change acknowledgement is a hard hand-off boundary. The caller must update or
     * remove any biometric-backed credential and terminate the current local session.
     */
    data object CredentialsChanged : NanoKvmAdministrationMutationResult<Nothing> {
        override val impact: NanoKvmAdministrationImpact =
            NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION
        override val guidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.CLEAR_SAVED_CREDENTIAL_AND_END_SESSION
        val mustInvalidateSavedCredential: Boolean = true
        val mustEndAuthenticatedSession: Boolean = true
    }

    /** A one-shot command was acknowledged and the existing session should be considered ending. */
    data class DisruptiveCommandAccepted(
        override val impact: NanoKvmAdministrationImpact,
        override val guidance: NanoKvmAdministrationGuidance,
    ) : NanoKvmAdministrationMutationResult<Nothing>
}

/** UI-ready account state. Password material never enters this model. */
internal class NanoKvmAdministrationAccountSnapshot internal constructor(
    val username: String,
    val passwordUpdated: Boolean,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationAccountSnapshot(username=<redacted>, " +
            "passwordUpdated=$passwordUpdated)"
}

internal class NanoKvmAdministrationUpdateSnapshot internal constructor(
    val currentVersion: String,
    val latestVersion: String?,
    val previewUpdatesEnabled: Boolean,
    internal val binding: NanoKvmSessionBinding,
) {
    val updateAvailable: Boolean = latestVersion != null && latestVersion != currentVersion

    override fun toString(): String =
        "NanoKvmAdministrationUpdateSnapshot(currentVersion=$currentVersion, " +
            "latestVersion=${latestVersion ?: "<unavailable>"}, " +
            "previewUpdatesEnabled=$previewUpdatesEnabled)"
}

internal class NanoKvmAdministrationOledSnapshot internal constructor(
    val exists: Boolean,
    val sleepSeconds: Int,
    val sleepPreset: NanoKvmOledSleepPreset?,
    internal val binding: NanoKvmSessionBinding,
)

internal class NanoKvmAdministrationSshSnapshot internal constructor(
    val enabled: Boolean,
    internal val binding: NanoKvmSessionBinding,
)

internal class NanoKvmAdministrationHostnameSnapshot internal constructor(
    val hostname: String,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationHostnameSnapshot(hostname=<redacted>)"
}

internal class NanoKvmAdministrationMdnsSnapshot internal constructor(
    val enabled: Boolean,
    internal val binding: NanoKvmSessionBinding,
)

internal class NanoKvmAdministrationTitleSnapshot internal constructor(
    val title: String,
    val isDefault: Boolean,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationTitleSnapshot(title=<redacted>, isDefault=$isDefault)"
}

internal enum class NanoKvmAdministrationDnsSelection {
    DHCP,
    MANUAL,
    OTHER,
}

internal class NanoKvmAdministrationDnsInfo internal constructor(
    val interfaceName: String,
    val networkType: String,
    val address: String,
    val subnetMask: String,
    val gateway: String,
    val searchDomains: List<String>,
) {
    override fun toString(): String =
        "NanoKvmAdministrationDnsInfo(interface=<redacted>, address=<redacted>, " +
            "searchDomains=${searchDomains.size})"
}

internal class NanoKvmAdministrationDnsSnapshot internal constructor(
    val selection: NanoKvmAdministrationDnsSelection,
    val otherMode: String?,
    val configuredServers: List<String>,
    val effectiveServers: List<String>,
    val dhcpServers: List<String>,
    val info: NanoKvmAdministrationDnsInfo,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationDnsSnapshot(selection=$selection, " +
            "configuredServers=${configuredServers.size}, " +
            "effectiveServers=${effectiveServers.size})"
}

internal class NanoKvmAdministrationWifiSnapshot internal constructor(
    val supported: Boolean,
    val accessPointMode: Boolean,
    val connected: Boolean,
    val ssid: String?,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationWifiSnapshot(supported=$supported, " +
            "accessPointMode=$accessPointMode, connected=$connected, ssid=<redacted>)"
}

internal enum class NanoKvmAdministrationTailscaleSelection {
    NOT_INSTALLED,
    NOT_RUNNING,
    NOT_LOGGED_IN,
    STOPPED,
    RUNNING,
    OTHER,
}

internal class NanoKvmAdministrationTailscaleSnapshot internal constructor(
    val selection: NanoKvmAdministrationTailscaleSelection,
    val reportedState: String?,
    val deviceName: String?,
    val ipv4: String?,
    val account: String?,
    internal val protocolStatus: NanoKvmTailscaleStatus,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmAdministrationTailscaleSnapshot(selection=$selection, identity=<redacted>)"
}

/** Login URLs remain ephemeral and are never copied into a state snapshot or diagnostic value. */
internal sealed interface NanoKvmAdministrationTailscaleLoginOutcome {
    data class AuthorizationRequired(
        val url: NanoKvmTailscaleLoginUrl,
    ) : NanoKvmAdministrationTailscaleLoginOutcome {
        override fun toString(): String =
            "NanoKvmAdministrationTailscaleLoginOutcome.AuthorizationRequired(url=<redacted>)"
    }

    data class Completed(
        val result: NanoKvmAdministrationMutationResult<NanoKvmAdministrationTailscaleSnapshot>,
    ) : NanoKvmAdministrationTailscaleLoginOutcome
}

/**
 * Session-bound, serialized administration domain gateway.
 *
 * Reads are discarded if the live destination changes while suspended. Mutations perform a fresh
 * destination comparison immediately before their single port call. Explicit setters always read
 * authoritative state first and never use toggle semantics.
 */
internal class NanoKvmAdministrationGateway internal constructor(
    private val port: NanoKvmAdministrationPort,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
) {
    private val operationMutex = Mutex()

    suspend fun refreshAccount():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationAccountSnapshot> =
        operationMutex.withLock { refreshAccountLocked() }

    suspend fun refreshUpdates():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationUpdateSnapshot> =
        operationMutex.withLock { refreshUpdatesLocked() }

    suspend fun refreshOled():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationOledSnapshot> =
        operationMutex.withLock { refreshOledLocked() }

    suspend fun refreshSsh():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationSshSnapshot> =
        operationMutex.withLock { refreshSshLocked() }

    suspend fun refreshHostname():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationHostnameSnapshot> =
        operationMutex.withLock { refreshHostnameLocked() }

    suspend fun refreshMdns():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationMdnsSnapshot> =
        operationMutex.withLock { refreshMdnsLocked() }

    suspend fun refreshWebTitle():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationTitleSnapshot> =
        operationMutex.withLock { refreshWebTitleLocked() }

    suspend fun refreshDns():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationDnsSnapshot> =
        operationMutex.withLock { refreshDnsLocked() }

    suspend fun refreshWifi():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationWifiSnapshot> =
        operationMutex.withLock { refreshWifiLocked() }

    suspend fun refreshTailscale():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationTailscaleSnapshot> =
        operationMutex.withLock { refreshTailscaleLocked() }

    /**
     * Takes ownership of [password] and clears the exact supplied array on every exit path.
     * It is never copied to a String, retained by this gateway, or included in a result.
     */
    suspend fun changePassword(
        username: String,
        password: CharArray,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationAccountSnapshot> =
        operationMutex.withLock {
            try {
                try {
                    validateChangedUsername(username)
                    validateChangedPassword(password)
                } catch (_: IllegalArgumentException) {
                    return@withLock rejected(
                        NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                        NanoKvmAdministrationError.Kind.INVALID_REQUEST,
                    )
                }
                requireCurrentBinding()?.let {
                    return@withLock rejected(
                        NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                        it,
                    )
                }

                try {
                    port.changePassword(username, password)
                    NanoKvmAdministrationMutationResult.CredentialsChanged
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (isDefiniteAdministrationRejection(error)) {
                        NanoKvmAdministrationMutationResult.Rejected(
                            error = error.toAdministrationError(),
                            impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                        )
                    } else {
                        reconcileIndeterminatePassword(error)
                    }
                }
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun setPreviewUpdatesEnabled(
        enabled: Boolean,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationUpdateSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.ROUTINE,
                readState = ::refreshUpdatesLocked,
                isDesired = { it.previewUpdatesEnabled == enabled },
                dispatch = { port.setPreviewUpdates(enabled) },
            )
        }

    suspend fun startOnlineUpdate():
        NanoKvmAdministrationMutationResult<NanoKvmAdministrationUpdateSnapshot> =
        operationMutex.withLock {
            val before = when (val read = refreshUpdatesLocked()) {
                is NanoKvmAdministrationReadResult.Success -> read.state
                is NanoKvmAdministrationReadResult.Failure ->
                    return@withLock NanoKvmAdministrationMutationResult.Rejected(
                        read.error,
                        NanoKvmAdministrationImpact.SERVICE_RESTART,
                    )
            }
            if (before.latestVersion != null && before.latestVersion == before.currentVersion) {
                return@withLock NanoKvmAdministrationMutationResult.AlreadySatisfied(
                    before,
                    NanoKvmAdministrationImpact.SERVICE_RESTART,
                )
            }
            requireCurrentBinding()?.let {
                return@withLock rejected(NanoKvmAdministrationImpact.SERVICE_RESTART, it)
            }

            try {
                port.startOnlineUpdate()
                NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted(
                    impact = NanoKvmAdministrationImpact.SERVICE_RESTART,
                    guidance = NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isDefiniteAdministrationRejection(error)) {
                    NanoKvmAdministrationMutationResult.Rejected(
                        error.toAdministrationError(),
                        NanoKvmAdministrationImpact.SERVICE_RESTART,
                    )
                } else {
                    reconcileOnlineUpdateFailure(error, before)
                }
            }
        }

    suspend fun rebootSystem(): NanoKvmAdministrationMutationResult<Nothing> =
        operationMutex.withLock {
            requireCurrentBinding()?.let {
                return@withLock rejected(NanoKvmAdministrationImpact.APPLIANCE_REBOOT, it)
            }
            try {
                port.rebootSystem()
                NanoKvmAdministrationMutationResult.DisruptiveCommandAccepted(
                    impact = NanoKvmAdministrationImpact.APPLIANCE_REBOOT,
                    guidance =
                        NanoKvmAdministrationGuidance.WAIT_FOR_REBOOT_AND_RECONNECT,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isDefiniteAdministrationRejection(error)) {
                    NanoKvmAdministrationMutationResult.Rejected(
                        error.toAdministrationError(),
                        NanoKvmAdministrationImpact.APPLIANCE_REBOOT,
                    )
                } else {
                    NanoKvmAdministrationMutationResult.Indeterminate(
                        state = null,
                        dispatchError = error.toAdministrationError(),
                        refreshError = null,
                        impact = NanoKvmAdministrationImpact.APPLIANCE_REBOOT,
                        guidance =
                            NanoKvmAdministrationGuidance.WAIT_FOR_REBOOT_AND_RECONNECT,
                    )
                }
            }
        }

    suspend fun setOledSleep(
        preset: NanoKvmOledSleepPreset,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationOledSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.ROUTINE,
                readState = ::refreshOledLocked,
                preflightError = {
                    if (it.exists) null else {
                        NanoKvmAdministrationError(NanoKvmAdministrationError.Kind.UNSUPPORTED)
                    }
                },
                isDesired = { it.sleepSeconds == preset.seconds },
                dispatch = { port.setOledSleep(preset) },
            )
        }

    suspend fun setSshEnabled(
        enabled: Boolean,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationSshSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE,
                readState = ::refreshSshLocked,
                isDesired = { it.enabled == enabled },
                dispatch = { port.setSshEnabled(enabled) },
            )
        }

    suspend fun setHostname(
        hostname: String,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationHostnameSnapshot> =
        operationMutex.withLock {
            val desired = try {
                validateHostname(hostname)
            } catch (_: IllegalArgumentException) {
                return@withLock rejected(
                    NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                    NanoKvmAdministrationError.Kind.INVALID_REQUEST,
                )
            }
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                readState = ::refreshHostnameLocked,
                isDesired = { it.hostname == desired },
                dispatch = { port.setHostname(desired) },
            )
        }

    suspend fun setMdnsEnabled(
        enabled: Boolean,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationMdnsSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                readState = ::refreshMdnsLocked,
                isDesired = { it.enabled == enabled },
                dispatch = { port.setMdnsEnabled(enabled) },
            )
        }

    suspend fun setCustomWebTitle(
        title: String,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationTitleSnapshot> =
        operationMutex.withLock {
            val desired = try {
                validateCustomTitle(title)
            } catch (_: IllegalArgumentException) {
                return@withLock rejected(
                    NanoKvmAdministrationImpact.ROUTINE,
                    NanoKvmAdministrationError.Kind.INVALID_REQUEST,
                )
            }
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.ROUTINE,
                readState = ::refreshWebTitleLocked,
                isDesired = { !it.isDefault && it.title == desired },
                dispatch = { port.setWebTitle(desired) },
            )
        }

    suspend fun resetWebTitle():
        NanoKvmAdministrationMutationResult<NanoKvmAdministrationTitleSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.ROUTINE,
                readState = ::refreshWebTitleLocked,
                isDesired = { it.isDefault },
                dispatch = { port.resetWebTitle() },
            )
        }

    suspend fun setManualDns(
        servers: List<String>,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationDnsSnapshot> =
        operationMutex.withLock {
            val desired = try {
                validateManualDns(servers)
            } catch (_: IllegalArgumentException) {
                return@withLock rejected(
                    NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                    NanoKvmAdministrationError.Kind.INVALID_REQUEST,
                )
            }
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                readState = ::refreshDnsLocked,
                isDesired = {
                    it.selection == NanoKvmAdministrationDnsSelection.MANUAL &&
                        it.configuredServers == desired
                },
                dispatch = { port.setManualDns(desired) },
            )
        }

    suspend fun setDhcpDns():
        NanoKvmAdministrationMutationResult<NanoKvmAdministrationDnsSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                readState = ::refreshDnsLocked,
                isDesired = {
                    it.selection == NanoKvmAdministrationDnsSelection.DHCP &&
                        it.configuredServers.isEmpty()
                },
                dispatch = { port.setDhcpDns() },
            )
        }

    /** Ownership of [password] transfers to this call and the exact array is always cleared. */
    suspend fun connectWifi(
        ssid: String,
        password: CharArray,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationWifiSnapshot> =
        operationMutex.withLock {
            try {
                setDesiredLocked(
                    impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                    appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                    uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                    readState = ::refreshWifiLocked,
                    preflightError = { state ->
                        when {
                            !state.supported || state.accessPointMode ->
                                NanoKvmAdministrationError(
                                    NanoKvmAdministrationError.Kind.UNSUPPORTED,
                                )
                            else -> null
                        }
                    },
                    isDesired = { it.connected && it.ssid == ssid },
                    dispatch = {
                        NanoKvmWifiCredentials(ssid, password).use { credentials ->
                            port.connectWifi(credentials)
                        }
                    },
                )
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun disconnectWifi():
        NanoKvmAdministrationMutationResult<NanoKvmAdministrationWifiSnapshot> =
        operationMutex.withLock {
            setDesiredLocked(
                impact = NanoKvmAdministrationImpact.NAME_OR_NETWORK_ACCESS_CHANGE,
                appliedGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                uncertainGuidance = NanoKvmAdministrationGuidance.REDISCOVER_AND_RECONNECT,
                readState = ::refreshWifiLocked,
                preflightError = { state ->
                    if (!state.supported || state.accessPointMode) {
                        NanoKvmAdministrationError(NanoKvmAdministrationError.Kind.UNSUPPORTED)
                    } else {
                        null
                    }
                },
                isDesired = { !it.connected },
                dispatch = port::disconnectWifi,
            )
        }

    suspend fun executeTailscale(
        command: NanoKvmTailscaleCommand,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationTailscaleSnapshot> =
        operationMutex.withLock {
            require(command != NanoKvmTailscaleCommand.LOGIN) {
                "Tailscale login requires the ephemeral login result API"
            }
            executeTailscaleLocked(command)
        }

    suspend fun loginTailscale(): NanoKvmAdministrationTailscaleLoginOutcome =
        operationMutex.withLock {
            val before = when (val read = refreshTailscaleLocked()) {
                is NanoKvmAdministrationReadResult.Success -> read.state
                is NanoKvmAdministrationReadResult.Failure ->
                    return@withLock NanoKvmAdministrationTailscaleLoginOutcome.Completed(
                        NanoKvmAdministrationMutationResult.Rejected(
                            read.error,
                            NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE,
                        ),
                    )
            }
            if (!before.protocolStatus.state.allowsAppCommand(NanoKvmTailscaleCommand.LOGIN)) {
                return@withLock NanoKvmAdministrationTailscaleLoginOutcome.Completed(
                    rejected(
                        NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE,
                        NanoKvmAdministrationError.Kind.INVALID_REQUEST,
                    ),
                )
            }
            requireCurrentBinding()?.let { error ->
                return@withLock NanoKvmAdministrationTailscaleLoginOutcome.Completed(
                    rejected(NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE, error),
                )
            }
            val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
                before.protocolStatus,
                NanoKvmTailscaleCommand.LOGIN,
            )
            try {
                when (val result = port.loginTailscale(approval)) {
                    NanoKvmTailscaleLoginResult.AlreadyAuthenticated ->
                        NanoKvmAdministrationTailscaleLoginOutcome.Completed(
                            tailscaleSuccessReadback(
                                command = NanoKvmTailscaleCommand.LOGIN,
                                impact = NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE,
                            ),
                        )
                    is NanoKvmTailscaleLoginResult.AuthorizationRequired ->
                        NanoKvmAdministrationTailscaleLoginOutcome.AuthorizationRequired(result.url)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NanoKvmAdministrationTailscaleLoginOutcome.Completed(
                    tailscaleFailureResult(
                        error = error,
                        command = NanoKvmTailscaleCommand.LOGIN,
                        impact = NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE,
                    ),
                )
            }
        }

    private suspend fun refreshAccountLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationAccountSnapshot> =
        guardedRead {
            val account = port.currentAccount()
            val passwordStatus = port.passwordStatus()
            NanoKvmAdministrationAccountSnapshot(
                username = boundedResponseText("account username", account.username, 256, false),
                passwordUpdated = passwordStatus.isUpdated,
                binding = binding,
            )
        }

    private suspend fun refreshUpdatesLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationUpdateSnapshot> =
        guardedRead {
            val versions = port.applicationVersions()
            val preview = port.previewUpdates()
            NanoKvmAdministrationUpdateSnapshot(
                currentVersion =
                    boundedResponseText("current version", versions.current, 64, false),
                latestVersion =
                    versions.latest?.let {
                        boundedResponseText("latest version", it, 64, false)
                    },
                previewUpdatesEnabled = preview.enabled,
                binding = binding,
            )
        }

    private suspend fun refreshOledLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationOledSnapshot> =
        guardedRead {
            val configuration = port.oledConfiguration()
            if (configuration.sleepSeconds !in 0..MAX_OLED_RESPONSE_SECONDS) {
                throw InvalidApiResponseException("OLED sleep is outside the UI response bound")
            }
            NanoKvmAdministrationOledSnapshot(
                exists = configuration.exists,
                sleepSeconds = configuration.sleepSeconds,
                sleepPreset = configuration.sleepPreset,
                binding = binding,
            )
        }

    private suspend fun refreshSshLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationSshSnapshot> =
        guardedRead {
            NanoKvmAdministrationSshSnapshot(port.sshState().enabled, binding)
        }

    private suspend fun refreshHostnameLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationHostnameSnapshot> =
        guardedRead {
            NanoKvmAdministrationHostnameSnapshot(
                hostname = validateResponseHostname(port.hostname().value),
                binding = binding,
            )
        }

    private suspend fun refreshMdnsLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationMdnsSnapshot> =
        guardedRead {
            NanoKvmAdministrationMdnsSnapshot(port.mdnsState().enabled, binding)
        }

    private suspend fun refreshWebTitleLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationTitleSnapshot> =
        guardedRead {
            val title = port.webTitle()
            val bounded = boundedResponseText("web title", title.value, 256, false)
            NanoKvmAdministrationTitleSnapshot(
                title = bounded,
                isDefault = title.isDefault,
                binding = binding,
            )
        }

    private suspend fun refreshDnsLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationDnsSnapshot> =
        guardedRead { port.dnsConfiguration().toUiSnapshot() }

    private suspend fun refreshWifiLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationWifiSnapshot> =
        guardedRead {
            val status = port.wifiStatus()
            NanoKvmAdministrationWifiSnapshot(
                supported = status.supported,
                accessPointMode = status.accessPointMode,
                connected = status.connected,
                ssid = status.ssid?.value,
                binding = binding,
            )
        }

    private suspend fun refreshTailscaleLocked():
        NanoKvmAdministrationReadResult<NanoKvmAdministrationTailscaleSnapshot> =
        guardedRead { port.tailscaleStatus().toUiSnapshot() }

    private suspend fun executeTailscaleLocked(
        command: NanoKvmTailscaleCommand,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationTailscaleSnapshot> {
        val impact = command.tailscaleImpact()
        val before = when (val read = refreshTailscaleLocked()) {
            is NanoKvmAdministrationReadResult.Success -> read.state
            is NanoKvmAdministrationReadResult.Failure ->
                return NanoKvmAdministrationMutationResult.Rejected(read.error, impact)
        }
        if (!before.protocolStatus.state.allowsAppCommand(command)) {
            return rejected(impact, NanoKvmAdministrationError.Kind.INVALID_REQUEST)
        }
        requireCurrentBinding()?.let { return rejected(impact, it) }
        val approval = NanoKvmTailscaleActionApproval.afterUserConfirmed(
            before.protocolStatus,
            command,
        )
        return try {
            when (command) {
                NanoKvmTailscaleCommand.INSTALL -> port.installTailscale(approval)
                NanoKvmTailscaleCommand.UNINSTALL -> port.uninstallTailscale(approval)
                NanoKvmTailscaleCommand.START -> port.startTailscale(approval)
                NanoKvmTailscaleCommand.STOP -> port.stopTailscale(approval)
                NanoKvmTailscaleCommand.RESTART -> port.restartTailscale(approval)
                NanoKvmTailscaleCommand.UP -> port.bringTailscaleUp(approval)
                NanoKvmTailscaleCommand.DOWN -> port.bringTailscaleDown(approval)
                NanoKvmTailscaleCommand.LOGOUT -> port.logoutTailscale(approval)
                NanoKvmTailscaleCommand.LOGIN -> error("Login uses loginTailscale")
            }
            tailscaleSuccessReadback(command, impact)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            tailscaleFailureResult(error, command, impact)
        }
    }

    private suspend fun tailscaleSuccessReadback(
        command: NanoKvmTailscaleCommand,
        impact: NanoKvmAdministrationImpact,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationTailscaleSnapshot> =
        when (val refreshed = refreshTailscaleLocked()) {
            is NanoKvmAdministrationReadResult.Success -> if (
                refreshed.state.matchesTailscaleCommand(command)
            ) {
                NanoKvmAdministrationMutationResult.Applied(refreshed.state, impact)
            } else {
                NanoKvmAdministrationMutationResult.Accepted(
                    state = refreshed.state,
                    refreshError = null,
                    impact = impact,
                    guidance = NanoKvmAdministrationGuidance.REVIEW_AUTHORITATIVE_STATE,
                )
            }
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Accepted(
                    state = null,
                    refreshError = refreshed.error,
                    impact = impact,
                    guidance = NanoKvmAdministrationGuidance.REFRESH_AUTHORITATIVE_STATE,
                )
        }

    private suspend fun tailscaleFailureResult(
        error: Throwable,
        command: NanoKvmTailscaleCommand,
        impact: NanoKvmAdministrationImpact,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationTailscaleSnapshot> {
        if (isDefiniteAdministrationRejection(error)) {
            return NanoKvmAdministrationMutationResult.Rejected(
                error.toAdministrationError(),
                impact,
            )
        }
        return when (val refreshed = refreshTailscaleLocked()) {
            is NanoKvmAdministrationReadResult.Success ->
                NanoKvmAdministrationMutationResult.Reconciled(
                    state = refreshed.state,
                    observation = if (refreshed.state.matchesTailscaleCommand(command)) {
                        NanoKvmAdministrationObservation.DESIRED_STATE
                    } else {
                        NanoKvmAdministrationObservation.OTHER_STATE
                    },
                    dispatchError = error.toAdministrationError(),
                    impact = impact,
                    guidance = NanoKvmAdministrationGuidance.REVIEW_AUTHORITATIVE_STATE,
                )
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.toAdministrationError(),
                    refreshError = refreshed.error,
                    impact = impact,
                    guidance = NanoKvmAdministrationGuidance.REFRESH_AUTHORITATIVE_STATE,
                )
        }
    }

    private fun NanoKvmTailscaleStatus.toUiSnapshot():
        NanoKvmAdministrationTailscaleSnapshot {
        val selection = when (state) {
            NanoKvmTailscaleState.NotInstalled ->
                NanoKvmAdministrationTailscaleSelection.NOT_INSTALLED
            NanoKvmTailscaleState.NotRunning ->
                NanoKvmAdministrationTailscaleSelection.NOT_RUNNING
            NanoKvmTailscaleState.NotLoggedIn ->
                NanoKvmAdministrationTailscaleSelection.NOT_LOGGED_IN
            NanoKvmTailscaleState.Stopped -> NanoKvmAdministrationTailscaleSelection.STOPPED
            NanoKvmTailscaleState.Running -> NanoKvmAdministrationTailscaleSelection.RUNNING
            is NanoKvmTailscaleState.Other -> NanoKvmAdministrationTailscaleSelection.OTHER
        }
        return NanoKvmAdministrationTailscaleSnapshot(
            selection = selection,
            reportedState = (state as? NanoKvmTailscaleState.Other)?.wireValue,
            deviceName = deviceName,
            ipv4 = ipv4?.value,
            account = account,
            protocolStatus = this,
            binding = binding,
        )
    }

    private suspend fun <State> guardedRead(
        block: suspend () -> State,
    ): NanoKvmAdministrationReadResult<State> {
        requireCurrentBinding()?.let { return NanoKvmAdministrationReadResult.Failure(it) }
        return try {
            val state = block()
            requireCurrentBinding()?.let { return NanoKvmAdministrationReadResult.Failure(it) }
            NanoKvmAdministrationReadResult.Success(state)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmAdministrationReadResult.Failure(error.toAdministrationError())
        }
    }

    private suspend fun <State> setDesiredLocked(
        impact: NanoKvmAdministrationImpact,
        appliedGuidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.NONE,
        uncertainGuidance: NanoKvmAdministrationGuidance =
            NanoKvmAdministrationGuidance.REFRESH_AUTHORITATIVE_STATE,
        readState: suspend () -> NanoKvmAdministrationReadResult<State>,
        preflightError: (State) -> NanoKvmAdministrationError? = { null },
        isDesired: (State) -> Boolean,
        dispatch: suspend () -> Unit,
    ): NanoKvmAdministrationMutationResult<State> {
        val before = when (val read = readState()) {
            is NanoKvmAdministrationReadResult.Success -> read.state
            is NanoKvmAdministrationReadResult.Failure ->
                return NanoKvmAdministrationMutationResult.Rejected(read.error, impact)
        }
        preflightError(before)?.let {
            return NanoKvmAdministrationMutationResult.Rejected(it, impact)
        }
        if (isDesired(before)) {
            return NanoKvmAdministrationMutationResult.AlreadySatisfied(before, impact)
        }
        requireCurrentBinding()?.let {
            return NanoKvmAdministrationMutationResult.Rejected(it, impact)
        }

        try {
            dispatch()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isDefiniteAdministrationRejection(error)) {
                return NanoKvmAdministrationMutationResult.Rejected(
                    error.toAdministrationError(),
                    impact,
                )
            }
            return reconcileSettingFailure(
                error = error,
                impact = impact,
                appliedGuidance = appliedGuidance,
                uncertainGuidance = uncertainGuidance,
                readState = readState,
                isDesired = isDesired,
            )
        }

        return when (val refreshed = readState()) {
            is NanoKvmAdministrationReadResult.Success ->
                if (isDesired(refreshed.state)) {
                    NanoKvmAdministrationMutationResult.Applied(
                        refreshed.state,
                        impact,
                        appliedGuidance,
                    )
                } else {
                    NanoKvmAdministrationMutationResult.Accepted(
                        state = refreshed.state,
                        refreshError = null,
                        impact = impact,
                        guidance = NanoKvmAdministrationGuidance.REVIEW_AUTHORITATIVE_STATE,
                    )
                }
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Accepted(
                    state = null,
                    refreshError = refreshed.error,
                    impact = impact,
                    guidance = uncertainGuidance,
                )
        }
    }

    private suspend fun <State> reconcileSettingFailure(
        error: Throwable,
        impact: NanoKvmAdministrationImpact,
        appliedGuidance: NanoKvmAdministrationGuidance,
        uncertainGuidance: NanoKvmAdministrationGuidance,
        readState: suspend () -> NanoKvmAdministrationReadResult<State>,
        isDesired: (State) -> Boolean,
    ): NanoKvmAdministrationMutationResult<State> =
        when (val refreshed = readState()) {
            is NanoKvmAdministrationReadResult.Success -> {
                val desired = isDesired(refreshed.state)
                NanoKvmAdministrationMutationResult.Reconciled(
                    state = refreshed.state,
                    observation =
                        if (desired) NanoKvmAdministrationObservation.DESIRED_STATE else {
                            NanoKvmAdministrationObservation.OTHER_STATE
                        },
                    dispatchError = error.toAdministrationError(),
                    impact = impact,
                    guidance =
                        if (desired) appliedGuidance else {
                            NanoKvmAdministrationGuidance.REVIEW_AUTHORITATIVE_STATE
                        },
                )
            }
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.toAdministrationError(),
                    refreshError = refreshed.error,
                    impact = impact,
                    guidance = uncertainGuidance,
                )
        }

    private suspend fun reconcileIndeterminatePassword(
        error: Throwable,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationAccountSnapshot> =
        when (val refreshed = refreshAccountLocked()) {
            is NanoKvmAdministrationReadResult.Success ->
                NanoKvmAdministrationMutationResult.Indeterminate(
                    state = refreshed.state,
                    dispatchError = error.toAdministrationError(),
                    refreshError = null,
                    impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                    guidance =
                        NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
                )
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.toAdministrationError(),
                    refreshError = refreshed.error,
                    impact = NanoKvmAdministrationImpact.CREDENTIALS_AND_SESSION,
                    guidance =
                        NanoKvmAdministrationGuidance.VERIFY_NEW_CREDENTIALS_AFTER_RECONNECT,
                )
        }

    private suspend fun reconcileOnlineUpdateFailure(
        error: Throwable,
        before: NanoKvmAdministrationUpdateSnapshot,
    ): NanoKvmAdministrationMutationResult<NanoKvmAdministrationUpdateSnapshot> =
        when (val refreshed = refreshUpdatesLocked()) {
            is NanoKvmAdministrationReadResult.Success -> {
                val expected = before.latestVersion
                val changed =
                    (expected != null && refreshed.state.currentVersion == expected) ||
                        refreshed.state.currentVersion != before.currentVersion
                NanoKvmAdministrationMutationResult.Reconciled(
                    state = refreshed.state,
                    observation =
                        if (changed) NanoKvmAdministrationObservation.DESIRED_STATE else {
                            NanoKvmAdministrationObservation.OTHER_STATE
                        },
                    dispatchError = error.toAdministrationError(),
                    impact = NanoKvmAdministrationImpact.SERVICE_RESTART,
                    guidance = NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH,
                )
            }
            is NanoKvmAdministrationReadResult.Failure ->
                NanoKvmAdministrationMutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.toAdministrationError(),
                    refreshError = refreshed.error,
                    impact = NanoKvmAdministrationImpact.SERVICE_RESTART,
                    guidance = NanoKvmAdministrationGuidance.RECONNECT_AND_REFRESH,
                )
        }

    private fun NanoKvmDnsConfiguration.toUiSnapshot(): NanoKvmAdministrationDnsSnapshot {
        val selection =
            when (mode) {
                NanoKvmDnsMode.Dhcp -> NanoKvmAdministrationDnsSelection.DHCP
                NanoKvmDnsMode.Manual -> NanoKvmAdministrationDnsSelection.MANUAL
                is NanoKvmDnsMode.Other -> NanoKvmAdministrationDnsSelection.OTHER
            }
        val otherMode =
            (mode as? NanoKvmDnsMode.Other)?.wireValue?.let {
                boundedResponseText("DNS mode", it, 32, false)
            }
        val configured = boundedIpList("configured DNS", servers, 6)
        val effective = boundedIpList("effective DNS", effectiveServers, 16)
        val dhcp = boundedIpList("DHCP DNS", dhcpServers, 16)
        if (info.searchDomains.size > 16) {
            throw InvalidApiResponseException("DNS search domain count exceeds the UI bound")
        }
        return NanoKvmAdministrationDnsSnapshot(
            selection = selection,
            otherMode = otherMode,
            configuredServers = configured,
            effectiveServers = effective,
            dhcpServers = dhcp,
            info = NanoKvmAdministrationDnsInfo(
                interfaceName = boundedResponseText("DNS interface", info.interfaceName, 256, true),
                networkType = boundedResponseText("DNS network type", info.type, 256, true),
                address = boundedResponseText("DNS address", info.address, 256, true),
                subnetMask = boundedResponseText("DNS subnet", info.subnetMask, 256, true),
                gateway = boundedResponseText("DNS gateway", info.gateway, 256, true),
                searchDomains =
                    info.searchDomains.map {
                        boundedResponseText("DNS search domain", it, 256, false)
                    },
            ),
            binding = binding,
        )
    }

    private fun boundedIpList(
        field: String,
        addresses: List<NanoKvmIpAddress>,
        maximum: Int,
    ): List<String> {
        if (addresses.size > maximum) {
            throw InvalidApiResponseException("$field count exceeds the UI bound")
        }
        return addresses.map { address ->
            val value = boundedResponseText(field, address.value, 64, false)
            val canonical = NanoKvmIpAddress.parse(value).value
            if (canonical != value) {
                throw InvalidApiResponseException("$field contains a non-canonical IP literal")
            }
            canonical
        }
    }

    private fun requireCurrentBinding(): NanoKvmAdministrationError? =
        if (currentBinding() == binding) null else {
            NanoKvmAdministrationError(NanoKvmAdministrationError.Kind.SESSION_CHANGED)
        }

    private fun rejected(
        impact: NanoKvmAdministrationImpact,
        kind: NanoKvmAdministrationError.Kind,
    ): NanoKvmAdministrationMutationResult.Rejected =
        rejected(impact, NanoKvmAdministrationError(kind))

    private fun rejected(
        impact: NanoKvmAdministrationImpact,
        error: NanoKvmAdministrationError,
    ): NanoKvmAdministrationMutationResult.Rejected =
        NanoKvmAdministrationMutationResult.Rejected(error, impact)
}

private fun Throwable.toAdministrationError(): NanoKvmAdministrationError =
    NanoKvmAdministrationError(
        when (this) {
            is IllegalArgumentException -> NanoKvmAdministrationError.Kind.INVALID_REQUEST
            is AuthenticationExpiredException ->
                NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED
            is ApiResponseException -> NanoKvmAdministrationError.Kind.SERVER_REJECTED
            is NanoKvmWifiOperationException -> when {
                httpStatus == 401 -> NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED
                httpStatus.isUnsupportedOptionalEndpoint() ->
                    NanoKvmAdministrationError.Kind.UNSUPPORTED
                apiCode != null || httpStatus in 400..499 ->
                    NanoKvmAdministrationError.Kind.SERVER_REJECTED
                else -> NanoKvmAdministrationError.Kind.CONNECTION
            }
            is NanoKvmTailscaleOperationException -> when {
                httpStatus == 401 -> NanoKvmAdministrationError.Kind.AUTHENTICATION_EXPIRED
                httpStatus.isUnsupportedOptionalEndpoint() ->
                    NanoKvmAdministrationError.Kind.UNSUPPORTED
                apiCode != null || httpStatus in 400..499 ->
                    NanoKvmAdministrationError.Kind.SERVER_REJECTED
                else -> NanoKvmAdministrationError.Kind.CONNECTION
            }
            is InvalidApiResponseException -> NanoKvmAdministrationError.Kind.INVALID_RESPONSE
            is HttpResponseException -> if (statusCode.isUnsupportedOptionalEndpoint()) {
                NanoKvmAdministrationError.Kind.UNSUPPORTED
            } else {
                NanoKvmAdministrationError.Kind.CONNECTION
            }
            is IOException -> NanoKvmAdministrationError.Kind.CONNECTION
            else -> NanoKvmAdministrationError.Kind.UNEXPECTED
        },
    )

private fun isDefiniteAdministrationRejection(error: Throwable): Boolean =
    error is IllegalArgumentException ||
        error is AuthenticationExpiredException ||
        error is ApiResponseException ||
        (error is HttpResponseException &&
            error.statusCode.isUnsupportedOptionalEndpoint()) ||
        (error is NanoKvmWifiOperationException &&
            (error.apiCode != null || error.httpStatus in 400..499)) ||
        (error is NanoKvmTailscaleOperationException &&
            (error.apiCode != null || error.httpStatus in 400..499))

private fun Int?.isUnsupportedOptionalEndpoint(): Boolean = this == 404 || this == 405 || this == 501

private fun NanoKvmTailscaleState.allowsAppCommand(command: NanoKvmTailscaleCommand): Boolean =
    when (this) {
        NanoKvmTailscaleState.NotInstalled -> command == NanoKvmTailscaleCommand.INSTALL
        NanoKvmTailscaleState.NotRunning ->
            command == NanoKvmTailscaleCommand.START ||
                command == NanoKvmTailscaleCommand.UNINSTALL
        NanoKvmTailscaleState.NotLoggedIn ->
            command == NanoKvmTailscaleCommand.LOGIN ||
                command == NanoKvmTailscaleCommand.STOP ||
                command == NanoKvmTailscaleCommand.RESTART ||
                command == NanoKvmTailscaleCommand.UNINSTALL
        NanoKvmTailscaleState.Stopped ->
            command == NanoKvmTailscaleCommand.UP ||
                command == NanoKvmTailscaleCommand.LOGOUT ||
                command == NanoKvmTailscaleCommand.STOP ||
                command == NanoKvmTailscaleCommand.RESTART ||
                command == NanoKvmTailscaleCommand.UNINSTALL
        NanoKvmTailscaleState.Running ->
            command == NanoKvmTailscaleCommand.DOWN ||
                command == NanoKvmTailscaleCommand.LOGOUT ||
                command == NanoKvmTailscaleCommand.STOP ||
                command == NanoKvmTailscaleCommand.RESTART ||
                command == NanoKvmTailscaleCommand.UNINSTALL
        is NanoKvmTailscaleState.Other -> false
    }

private fun NanoKvmTailscaleCommand.tailscaleImpact(): NanoKvmAdministrationImpact = when (this) {
    NanoKvmTailscaleCommand.INSTALL,
    NanoKvmTailscaleCommand.UNINSTALL,
    NanoKvmTailscaleCommand.START,
    NanoKvmTailscaleCommand.STOP,
    NanoKvmTailscaleCommand.RESTART -> NanoKvmAdministrationImpact.SERVICE_RESTART
    NanoKvmTailscaleCommand.UP,
    NanoKvmTailscaleCommand.DOWN,
    NanoKvmTailscaleCommand.LOGIN,
    NanoKvmTailscaleCommand.LOGOUT -> NanoKvmAdministrationImpact.SECURITY_ACCESS_CHANGE
}

private fun NanoKvmAdministrationTailscaleSnapshot.matchesTailscaleCommand(
    command: NanoKvmTailscaleCommand,
): Boolean = when (command) {
    NanoKvmTailscaleCommand.INSTALL ->
        selection != NanoKvmAdministrationTailscaleSelection.NOT_INSTALLED
    NanoKvmTailscaleCommand.UNINSTALL ->
        selection == NanoKvmAdministrationTailscaleSelection.NOT_INSTALLED
    NanoKvmTailscaleCommand.START ->
        selection != NanoKvmAdministrationTailscaleSelection.NOT_INSTALLED &&
            selection != NanoKvmAdministrationTailscaleSelection.NOT_RUNNING
    NanoKvmTailscaleCommand.STOP ->
        selection == NanoKvmAdministrationTailscaleSelection.NOT_RUNNING
    NanoKvmTailscaleCommand.RESTART -> false // A status snapshot cannot prove a restart occurred.
    NanoKvmTailscaleCommand.UP ->
        selection == NanoKvmAdministrationTailscaleSelection.RUNNING
    NanoKvmTailscaleCommand.DOWN ->
        selection == NanoKvmAdministrationTailscaleSelection.STOPPED
    NanoKvmTailscaleCommand.LOGIN ->
        selection != NanoKvmAdministrationTailscaleSelection.NOT_LOGGED_IN
    NanoKvmTailscaleCommand.LOGOUT ->
        selection == NanoKvmAdministrationTailscaleSelection.NOT_LOGGED_IN
}

private fun boundedResponseText(
    field: String,
    value: String,
    maximumUtf8Bytes: Int,
    allowEmpty: Boolean,
): String {
    if (!allowEmpty && value.isEmpty()) {
        throw InvalidApiResponseException("$field must not be empty")
    }
    if (value.encodeToByteArray().size > maximumUtf8Bytes) {
        throw InvalidApiResponseException("$field exceeds the UI response bound")
    }
    if (value.any(Char::isISOControl)) {
        throw InvalidApiResponseException("$field contains a control character")
    }
    return value
}

private fun validateChangedUsername(value: String) {
    if (value.isEmpty() || value != value.trim() || value.encodeToByteArray().size > 256) {
        throw IllegalArgumentException("invalid username")
    }
    if (value.any(Char::isISOControl) || value.any { it in INVALID_ACCOUNT_CHARACTERS }) {
        throw IllegalArgumentException("invalid username")
    }
}

private fun validateChangedPassword(value: CharArray) {
    if (value.isEmpty() || value.size > 256) throw IllegalArgumentException("invalid password")
    var utf8Bytes = 0
    var index = 0
    while (index < value.size) {
        val character = value[index]
        utf8Bytes +=
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= value.size || !value[index + 1].isLowSurrogate()) {
                        throw IllegalArgumentException("invalid password")
                    }
                    index += 1
                    4
                }
                character.isLowSurrogate() -> throw IllegalArgumentException("invalid password")
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
        if (utf8Bytes > 256) throw IllegalArgumentException("invalid password")
        index += 1
    }
    if (value.any { it in INVALID_ACCOUNT_CHARACTERS }) {
        throw IllegalArgumentException("invalid password")
    }
}

private fun validateHostname(value: String): String {
    if (value.isEmpty() || value.length > 63 || value.encodeToByteArray().size > 63) {
        throw IllegalArgumentException("invalid hostname")
    }
    if (!HOSTNAME_PATTERN.matches(value)) throw IllegalArgumentException("invalid hostname")
    return value
}

private fun validateResponseHostname(value: String): String {
    if (
        value.isEmpty() ||
        value.encodeToByteArray().size > MAX_REPORTED_HOSTNAME_BYTES ||
        value.any(Char::isISOControl)
    ) {
        throw InvalidApiResponseException("hostname is outside the UI response bound")
    }
    return value
}

private fun validateCustomTitle(value: String): String {
    if (
        value.isEmpty() ||
        value == NanoKvmWebTitle.DEFAULT ||
        value.encodeToByteArray().size > 256 ||
        value.any(Char::isISOControl)
    ) {
        throw IllegalArgumentException("invalid title")
    }
    return value
}

private fun validateManualDns(values: List<String>): List<String> {
    if (values.isEmpty() || values.size > 6) throw IllegalArgumentException("invalid DNS list")
    val canonical = values.map { NanoKvmIpAddress.parse(it).value }
    if (canonical.distinct().size != canonical.size) {
        throw IllegalArgumentException("duplicate DNS server")
    }
    return canonical
}

private val INVALID_ACCOUNT_CHARACTERS: Set<Char> = setOf('\'', '"', '\\', '/')
private val HOSTNAME_PATTERN: Regex =
    Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private const val MAX_OLED_RESPONSE_SECONDS: Int = 86_400
private const val MAX_REPORTED_HOSTNAME_BYTES: Int = 253
