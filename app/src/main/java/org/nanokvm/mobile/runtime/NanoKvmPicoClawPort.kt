package org.nanokvm.mobile.runtime

import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmPicoClaw
import org.nanokvm.protocol.NanoKvmPicoClawAgentProfile
import org.nanokvm.protocol.NanoKvmPicoClawApiBase
import org.nanokvm.protocol.NanoKvmPicoClawControlApproval
import org.nanokvm.protocol.NanoKvmPicoClawGatewayEvent
import org.nanokvm.protocol.NanoKvmPicoClawGatewayState
import org.nanokvm.protocol.NanoKvmPicoClawHistoryCatalog
import org.nanokvm.protocol.NanoKvmPicoClawHistoryDeletionApproval
import org.nanokvm.protocol.NanoKvmPicoClawHistoryDetail
import org.nanokvm.protocol.NanoKvmPicoClawHistoryMessage
import org.nanokvm.protocol.NanoKvmPicoClawHistoryRole
import org.nanokvm.protocol.NanoKvmPicoClawHistorySession
import org.nanokvm.protocol.NanoKvmPicoClawManualHidLockState
import org.nanokvm.protocol.NanoKvmPicoClawMessageOptions
import org.nanokvm.protocol.NanoKvmPicoClawMessageReceipt
import org.nanokvm.protocol.NanoKvmPicoClawModelConfiguration
import org.nanokvm.protocol.NanoKvmPicoClawRuntimeStatus
import org.nanokvm.protocol.NanoKvmPicoClawSessionRelease
import org.nanokvm.protocol.NanoKvmPicoClawUninstallApproval

/**
 * Testable boundary around the official PicoClaw protocol.
 *
 * Every mutating call represents exactly one protocol dispatch. Implementations must never retry,
 * reconnect, queue, or replay a mutation or chat frame. Runtime status is intentionally absent
 * from construction: the app gateway calls it only after an explicit feature-entry action.
 */
internal interface NanoKvmPicoClawPort {
    suspend fun runtimeStatus(): NanoKvmPicoClawRuntimeStatus

    suspend fun installRuntime()

    suspend fun uninstallRuntime()

    suspend fun startRuntime()

    suspend fun stopRuntime()

    suspend fun setAgentProfile(profile: NanoKvmPicoClawAgentProfile)

    suspend fun updateModel(
        model: String,
        apiBase: NanoKvmPicoClawApiBase,
        apiKey: CharArray,
    )

    suspend fun histories(limit: Int): NanoKvmPicoClawPortHistoryCatalog

    suspend fun history(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawPortHistoryDetail

    suspend fun deleteHistory(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    )

    /**
     * Authoritative read of the selected history after an ambiguous deletion response.
     * Implementations return [UNKNOWN] unless the appliance proves presence or absence.
     */
    suspend fun historyPresence(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawHistoryPresence

    fun newChat(sessionGeneration: Long): NanoKvmPicoClawChatPort
}

internal enum class NanoKvmPicoClawHistoryPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

/** Opaque port identity for one exact server-issued history handle. */
internal class NanoKvmPicoClawPortHistorySession internal constructor(
    val title: String,
    val preview: String,
    val messageCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    internal val opaqueToken: Any,
) {
    override fun toString(): String = "NanoKvmPicoClawPortHistorySession(<opaque>)"
}

/** Immutable port page. Detail and deletion require an exact member of this exact object. */
internal class NanoKvmPicoClawPortHistoryCatalog internal constructor(
    sessions: List<NanoKvmPicoClawPortHistorySession>,
    internal val opaqueToken: Any,
) {
    val sessions = sessions.toList()

    internal fun requireExactMember(session: NanoKvmPicoClawPortHistorySession) {
        require(sessions.any { it === session }) {
            "History session must be an exact member of the supplied catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmPicoClawPortHistoryCatalog(sessions=${sessions.size})"
}

internal class NanoKvmPicoClawPortHistoryMessage(
    val role: NanoKvmPicoClawHistoryRole,
    val content: String,
) {
    override fun toString(): String =
        "NanoKvmPicoClawPortHistoryMessage(role=$role, content=<redacted>)"
}

internal class NanoKvmPicoClawPortHistoryDetail internal constructor(
    val session: NanoKvmPicoClawPortHistorySession,
    messages: List<NanoKvmPicoClawPortHistoryMessage>,
    val summary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val messages = messages.toList()

    override fun toString(): String =
        "NanoKvmPicoClawPortHistoryDetail(messages=${messages.size}, content=<redacted>)"
}

/** One non-reconnecting, non-queueing protocol chat session. */
internal interface NanoKvmPicoClawChatPort : AutoCloseable {
    val state: StateFlow<NanoKvmPicoClawGatewayState>
    val manualHidLock: StateFlow<NanoKvmPicoClawManualHidLockState>
    val events: SharedFlow<NanoKvmPicoClawGatewayEvent>

    fun connect(): Boolean

    fun sendMessage(
        content: String,
        options: NanoKvmPicoClawMessageOptions,
    ): NanoKvmPicoClawMessageReceipt?

    fun cancelRun(): Boolean

    suspend fun closeAndRelease(): NanoKvmPicoClawSessionRelease
}

/** Direct adapter for the official NanoKVM 2.4.3 PicoClaw surface. */
internal class NanoKvmProtocolPicoClawPort(
    private val picoClaw: NanoKvmPicoClaw,
) : NanoKvmPicoClawPort {
    override suspend fun runtimeStatus(): NanoKvmPicoClawRuntimeStatus =
        picoClaw.runtimeStatus()

    override suspend fun installRuntime() {
        picoClaw.installRuntime()
    }

    override suspend fun uninstallRuntime() {
        picoClaw.uninstallRuntime(
            NanoKvmPicoClawUninstallApproval
                .afterUserConfirmedRuntimeAndConfigurationErasure(),
        )
    }

    override suspend fun startRuntime() {
        picoClaw.startRuntime()
    }

    override suspend fun stopRuntime() {
        picoClaw.stopRuntime()
    }

    override suspend fun setAgentProfile(profile: NanoKvmPicoClawAgentProfile) {
        picoClaw.setAgentProfile(profile)
    }

    override suspend fun updateModel(
        model: String,
        apiBase: NanoKvmPicoClawApiBase,
        apiKey: CharArray,
    ) {
        picoClaw.updateModel(NanoKvmPicoClawModelConfiguration(model, apiBase, apiKey))
    }

    override suspend fun histories(limit: Int): NanoKvmPicoClawPortHistoryCatalog {
        val catalog = picoClaw.histories(limit = limit)
        return NanoKvmPicoClawPortHistoryCatalog(
            sessions = catalog.entries.map { summary ->
                NanoKvmPicoClawPortHistorySession(
                    title = summary.title,
                    preview = summary.preview,
                    messageCount = summary.messageCount,
                    createdAt = summary.createdAt,
                    updatedAt = summary.updatedAt,
                    opaqueToken = ProtocolHistorySessionToken(summary.session),
                )
            },
            opaqueToken = ProtocolHistoryCatalogToken(catalog),
        )
    }

    override suspend fun history(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawPortHistoryDetail {
        catalog.requireExactMember(session)
        return picoClaw.history(catalog.protocolCatalog(), session.protocolSession())
            .toPortDetail(session)
    }

    override suspend fun deleteHistory(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ) {
        catalog.requireExactMember(session)
        val protocolCatalog = catalog.protocolCatalog()
        val protocolSession = session.protocolSession()
        picoClaw.deleteHistory(
            protocolCatalog,
            protocolSession,
            NanoKvmPicoClawHistoryDeletionApproval.afterUserConfirmedPermanentDeletion(
                protocolCatalog,
                protocolSession,
            ),
        )
    }

    override suspend fun historyPresence(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawHistoryPresence {
        catalog.requireExactMember(session)
        return try {
            picoClaw.history(catalog.protocolCatalog(), session.protocolSession())
            NanoKvmPicoClawHistoryPresence.PRESENT
        } catch (_: IllegalStateException) {
            // The protocol invalidates its latest catalog after an acknowledged deletion. That is
            // not, by itself, proof about an earlier ambiguous response.
            NanoKvmPicoClawHistoryPresence.UNKNOWN
        } catch (_: IllegalArgumentException) {
            NanoKvmPicoClawHistoryPresence.UNKNOWN
        }
    }

    override fun newChat(sessionGeneration: Long): NanoKvmPicoClawChatPort =
        ProtocolPicoClawChatPort(
            picoClaw.newGateway(
                generation = sessionGeneration,
                approval = NanoKvmPicoClawControlApproval
                    .afterUserApprovedBroadDeviceAndHostControl(),
            ),
        )

    private fun NanoKvmPicoClawPortHistoryCatalog.protocolCatalog():
        NanoKvmPicoClawHistoryCatalog =
        (opaqueToken as? ProtocolHistoryCatalogToken)?.catalog
            ?: throw IllegalArgumentException("Foreign PicoClaw history catalog")

    private fun NanoKvmPicoClawPortHistorySession.protocolSession():
        NanoKvmPicoClawHistorySession =
        (opaqueToken as? ProtocolHistorySessionToken)?.session
            ?: throw IllegalArgumentException("Foreign PicoClaw history session")

    private fun NanoKvmPicoClawHistoryDetail.toPortDetail(
        appSession: NanoKvmPicoClawPortHistorySession,
    ) = NanoKvmPicoClawPortHistoryDetail(
        session = appSession,
        messages = messages.map { it.toPortMessage() },
        summary = summary,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun NanoKvmPicoClawHistoryMessage.toPortMessage() =
        NanoKvmPicoClawPortHistoryMessage(role, content)

    private class ProtocolHistoryCatalogToken(val catalog: NanoKvmPicoClawHistoryCatalog) {
        override fun toString(): String = "ProtocolHistoryCatalogToken(<redacted>)"
    }

    private class ProtocolHistorySessionToken(val session: NanoKvmPicoClawHistorySession) {
        override fun toString(): String = "ProtocolHistorySessionToken(<redacted>)"
    }
}

private class ProtocolPicoClawChatPort(
    private val gateway: org.nanokvm.protocol.NanoKvmPicoClawGateway,
) : NanoKvmPicoClawChatPort {
    override val state = gateway.state
    override val manualHidLock = gateway.manualHidLock
    override val events = gateway.events

    override fun connect(): Boolean = gateway.connect()

    override fun sendMessage(
        content: String,
        options: NanoKvmPicoClawMessageOptions,
    ): NanoKvmPicoClawMessageReceipt? = gateway.sendMessage(content, options)

    override fun cancelRun(): Boolean = gateway.cancelRun()

    override suspend fun closeAndRelease(): NanoKvmPicoClawSessionRelease =
        gateway.closeAndRelease()

    override fun close() = gateway.close()
}

/**
 * Creates a local PicoClaw feature entry for one authenticated destination. No network request is
 * made until [NanoKvmPicoClawFeatureGateway.enterFeature] is called by an explicit user action.
 */
internal fun AuthenticatedNanoKvmSession.createPicoClawFeatureGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
    scope: kotlinx.coroutines.CoroutineScope,
): NanoKvmPicoClawFeatureGateway {
    val applicationVersion: NanoKvmApplicationVersion = capabilities.applicationVersion
        ?: throw IllegalStateException("PicoClaw requires a known NanoKVM application version")
    val captured = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmPicoClawFeatureGateway(
        port = NanoKvmProtocolPicoClawPort(
            NanoKvmPicoClaw.enter(client, applicationVersion),
        ),
        binding = captured,
        currentBinding = currentBinding,
        scope = scope,
    )
}
