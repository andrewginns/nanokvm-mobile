package org.nanokvm.mobile.runtime

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

/** Opaque member of one exact port catalog. Its display name is never rendered by [toString]. */
internal class NanoKvmOperatorPortScript internal constructor(
    val displayName: String,
    internal val opaqueToken: Any,
) {
    init {
        require(displayName.isNotBlank()) { "Script display name must not be blank" }
    }

    override fun toString(): String = "NanoKvmOperatorPortScript(displayName=<redacted>)"
}

/** Immutable port snapshot. Mutations require both this object and one of its exact members. */
internal class NanoKvmOperatorPortScriptCatalog internal constructor(
    scripts: List<NanoKvmOperatorPortScript>,
    internal val opaqueToken: Any,
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

    override fun newTerminal(): NanoKvmOperatorTerminalPort =
        NanoKvmProtocolOperatorTerminalPort(client.newTerminalSocket())

    override suspend fun listScripts(): NanoKvmOperatorPortScriptCatalog {
        val catalog = api.listScripts()
        return NanoKvmOperatorPortScriptCatalog(
            scripts = catalog.scripts.map { script ->
                NanoKvmOperatorPortScript(
                    displayName = script.name,
                    opaqueToken = ProtocolScriptToken(script),
                )
            },
            opaqueToken = ProtocolScriptCatalogToken(catalog),
        )
    }

    override suspend fun uploadScript(
        fileName: String,
        content: ByteArray,
    ): NanoKvmOperatorPortUploadReceipt {
        val receipt = api.uploadScript(fileName, content)
        return NanoKvmOperatorPortUploadReceipt(receipt.fileName, receipt.byteCount)
    }

    override suspend fun runScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmOperatorPortRunResult {
        catalog.requireExactMember(script)
        val result = api.runScript(catalog.protocolCatalog(), script.protocolScript(), mode)
        return NanoKvmOperatorPortRunResult(result.mode, result.output)
    }

    override suspend fun deleteScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
    ) {
        catalog.requireExactMember(script)
        api.deleteScript(catalog.protocolCatalog(), script.protocolScript())
    }

    private fun NanoKvmOperatorPortScriptCatalog.protocolCatalog(): NanoKvmScriptCatalog =
        (opaqueToken as? ProtocolScriptCatalogToken)?.catalog
            ?: throw IllegalArgumentException("Foreign script catalog")

    private fun NanoKvmOperatorPortScript.protocolScript(): NanoKvmScript =
        (opaqueToken as? ProtocolScriptToken)?.script
            ?: throw IllegalArgumentException("Foreign script handle")

    private class ProtocolScriptCatalogToken(val catalog: NanoKvmScriptCatalog) {
        override fun toString(): String = "ProtocolScriptCatalogToken(<redacted>)"
    }

    private class ProtocolScriptToken(val script: NanoKvmScript) {
        override fun toString(): String = "ProtocolScriptToken(<redacted>)"
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
