package org.nanokvm.mobile.runtime

import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmScript
import org.nanokvm.protocol.NanoKvmScriptCatalog
import org.nanokvm.protocol.NanoKvmScriptRunMode
import org.nanokvm.protocol.NanoKvmSerialConfiguration
import org.nanokvm.protocol.NanoKvmTerminalConnectionState
import org.nanokvm.protocol.NanoKvmTerminalEvent
import org.nanokvm.protocol.NanoKvmTerminalSize
import org.nanokvm.protocol.NanoKvmTerminalSocket

/**
 * Narrow, testable boundary for NanoKVM's privileged root terminal and script runner.
 *
 * A method call is one protocol dispatch. Implementations must not reconnect, retry, queue, or
 * replay any terminal input or script mutation.
 */
internal interface NanoKvmOperatorPort {
    fun newTerminal(): NanoKvmOperatorTerminalPort

    suspend fun listScripts(): NanoKvmOperatorPortScriptCatalog

    suspend fun uploadScript(fileName: String, content: ByteArray): NanoKvmOperatorPortUploadReceipt

    suspend fun runScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmOperatorPortRunResult

    suspend fun deleteScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
    )
}

/** One non-reconnecting terminal socket. All send methods dispatch immediately or return false. */
internal interface NanoKvmOperatorTerminalPort : AutoCloseable {
    val state: StateFlow<NanoKvmTerminalConnectionState>
    val events: SharedFlow<NanoKvmTerminalEvent>

    fun connect(): Boolean
    fun sendInput(text: String): Boolean
    fun resize(size: NanoKvmTerminalSize): Boolean
    fun startSerial(configuration: NanoKvmSerialConfiguration): Boolean
    suspend fun exitSerialAndDisconnect(): Boolean
}

/** Member of one exact port catalog. Its display name is never rendered by [toString]. */
internal class NanoKvmOperatorPortScript internal constructor(
    val displayName: String,
) {
    init {
        require(displayName.isNotBlank()) { "Script display name must not be blank" }
    }

    override fun toString(): String = "NanoKvmOperatorPortScript(displayName=<redacted>)"
}

/** Immutable port snapshot. Mutations require both this object and one of its exact members. */
internal class NanoKvmOperatorPortScriptCatalog internal constructor(
    scripts: List<NanoKvmOperatorPortScript>,
) {
    val scripts: List<NanoKvmOperatorPortScript> = scripts.toList()

    init {
        require(scripts.distinctBy { it.displayName }.size == scripts.size) {
            "Script catalog must not contain duplicate display names"
        }
    }

    internal fun requireExactMember(script: NanoKvmOperatorPortScript) {
        require(scripts.any { it === script }) {
            "Script must be an exact member of the supplied port catalog"
        }
    }

    override fun toString(): String = "NanoKvmOperatorPortScriptCatalog(scripts=${scripts.size})"
}

internal class NanoKvmOperatorPortUploadReceipt internal constructor(
    val displayName: String,
    val byteCount: Int,
) {
    override fun toString(): String =
        "NanoKvmOperatorPortUploadReceipt(displayName=<redacted>, byteCount=$byteCount)"
}

internal class NanoKvmOperatorPortRunResult internal constructor(
    val mode: NanoKvmScriptRunMode,
    val output: String,
) {
    override fun toString(): String =
        "NanoKvmOperatorPortRunResult(mode=$mode, output=<redacted>)"
}

/** Direct adapter for the official NanoKVM 2.4.3 operator APIs. */
internal class NanoKvmProtocolOperatorPort(
    private val client: NanoKvmClient,
) : NanoKvmOperatorPort {
    private val api: NanoKvmApi = client.api
    private var latestScriptCatalog: ProtocolScriptCatalogBinding? = null

    override fun newTerminal(): NanoKvmOperatorTerminalPort =
        NanoKvmProtocolOperatorTerminalPort(client.newTerminalSocket())

    override suspend fun listScripts(): NanoKvmOperatorPortScriptCatalog {
        latestScriptCatalog = null
        val protocolCatalog = api.listScripts()
        val members = protocolCatalog.scripts.map { script ->
            NanoKvmOperatorPortScript(displayName = script.name) to script
        }
        val portCatalog = NanoKvmOperatorPortScriptCatalog(
            scripts = members.map { it.first },
        )
        val protocolMembers = IdentityHashMap<NanoKvmOperatorPortScript, NanoKvmScript>()
        members.forEach { (portScript, protocolScript) ->
            protocolMembers[portScript] = protocolScript
        }
        latestScriptCatalog = ProtocolScriptCatalogBinding(
            portCatalog = portCatalog,
            protocolCatalog = protocolCatalog,
            members = protocolMembers,
        )
        return portCatalog
    }

    override suspend fun uploadScript(
        fileName: String,
        content: ByteArray,
    ): NanoKvmOperatorPortUploadReceipt {
        // Upload can overwrite a member. Consume the prior authority before dispatch so a lost
        // response never leaves an apparently reusable catalog.
        latestScriptCatalog = null
        val receipt = api.uploadScript(fileName, content)
        return NanoKvmOperatorPortUploadReceipt(receipt.fileName, receipt.byteCount)
    }

    override suspend fun runScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmOperatorPortRunResult {
        val binding = requireLatestScriptCatalog(catalog)
        val result = api.runScript(
            binding.protocolCatalog,
            binding.requireProtocolScript(script),
            mode,
        )
        return NanoKvmOperatorPortRunResult(result.mode, result.output)
    }

    override suspend fun deleteScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
    ) {
        val binding = requireLatestScriptCatalog(catalog)
        val protocolScript = binding.requireProtocolScript(script)
        // Delete consumes the exact snapshot before dispatch, matching the protocol boundary.
        latestScriptCatalog = null
        api.deleteScript(binding.protocolCatalog, protocolScript)
    }

    private fun requireLatestScriptCatalog(
        catalog: NanoKvmOperatorPortScriptCatalog,
    ): ProtocolScriptCatalogBinding = latestScriptCatalog
        ?.takeIf { it.portCatalog === catalog }
        ?: throw IllegalArgumentException("Foreign or stale script catalog")

    private class ProtocolScriptCatalogBinding(
        val portCatalog: NanoKvmOperatorPortScriptCatalog,
        val protocolCatalog: NanoKvmScriptCatalog,
        private val members: Map<NanoKvmOperatorPortScript, NanoKvmScript>,
    ) {
        fun requireProtocolScript(script: NanoKvmOperatorPortScript): NanoKvmScript {
            portCatalog.requireExactMember(script)
            return members[script] ?: throw IllegalArgumentException("Foreign script handle")
        }

        override fun toString(): String = "ProtocolScriptCatalogBinding(<redacted>)"
    }
}

private class NanoKvmProtocolOperatorTerminalPort(
    private val socket: NanoKvmTerminalSocket,
) : NanoKvmOperatorTerminalPort {
    override val state: StateFlow<NanoKvmTerminalConnectionState> = socket.state
    override val events: SharedFlow<NanoKvmTerminalEvent> = socket.events

    override fun connect(): Boolean = socket.connect()

    override fun sendInput(text: String): Boolean = socket.sendInput(text)

    override fun resize(size: NanoKvmTerminalSize): Boolean = socket.resize(size)

    override fun startSerial(configuration: NanoKvmSerialConfiguration): Boolean =
        socket.startSerial(configuration)

    override suspend fun exitSerialAndDisconnect(): Boolean = socket.exitSerialAndDisconnect()

    override fun close() = socket.close()
}

/**
 * Creates an isolated operator gateway for this exact authenticated destination generation.
 * [currentBinding] must turn null/stale as soon as backend command acceptance closes.
 */
internal fun AuthenticatedNanoKvmSession.createOperatorGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
    scope: CoroutineScope,
): NanoKvmOperatorGateway {
    val captured = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmOperatorGateway(
        port = NanoKvmProtocolOperatorPort(client),
        binding = captured,
        currentBinding = currentBinding,
        scope = scope,
    )
}
