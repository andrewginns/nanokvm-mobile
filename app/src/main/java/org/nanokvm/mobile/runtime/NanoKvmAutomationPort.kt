package org.nanokvm.mobile.runtime

import java.io.Closeable
import java.util.IdentityHashMap
import org.nanokvm.protocol.MAX_AUTOSTART_CONTENT_BYTES
import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmAutostartCatalog
import org.nanokvm.protocol.NanoKvmAutostartContent
import org.nanokvm.protocol.NanoKvmAutostartScript
import org.nanokvm.protocol.NanoKvmAutostartWriteContent
import org.nanokvm.protocol.NanoKvmAutostartWriteKind
import org.nanokvm.protocol.NanoKvmHidKeyCode
import org.nanokvm.protocol.NanoKvmHidShortcutDraft
import org.nanokvm.protocol.NanoKvmHidShortcutRunResult
import org.nanokvm.protocol.NanoKvmInputSocket
import org.nanokvm.protocol.NanoKvmSavedHidShortcut
import org.nanokvm.protocol.NanoKvmSavedHidShortcutCatalog

/**
 * One-dispatch boundary for saved HID shortcuts, the leader key, and root autostart scripts.
 *
 * The gateway owns lifecycle, exact-handle, confirmation, and no-replay policy. Implementations
 * must never retry a mutation or silently recreate an input connection.
 */
internal interface NanoKvmAutomationPort {
    suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog
    suspend fun saveHidShortcut(wireCodes: List<String>)
    fun releaseAllInput(): Boolean
    fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult
    suspend fun deleteHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    )

    suspend fun leaderKey(): NanoKvmAutomationPortLeaderKey
    suspend fun setLeaderKey(wireCode: String)
    suspend fun disableLeaderKey()

    suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog
    suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent
    suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt
    suspend fun updateAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt
    suspend fun deleteAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    )
}

internal data class NanoKvmAutomationPortHidKey(
    val code: String,
    val label: String,
    val known: Boolean,
)

internal class NanoKvmAutomationPortHidShortcut internal constructor(
    internal val stableId: String,
    keys: List<NanoKvmAutomationPortHidKey>,
    val runnable: Boolean,
) {
    val keys: List<NanoKvmAutomationPortHidKey> = keys.toList()

    init {
        require(stableId.isNotBlank()) { "HID shortcut stable ID must not be blank" }
    }

    override fun toString(): String =
        "NanoKvmAutomationPortHidShortcut(keys=${keys.size}, stableId=<redacted>)"
}

internal class NanoKvmAutomationPortHidCatalog internal constructor(
    shortcuts: List<NanoKvmAutomationPortHidShortcut>,
) {
    val shortcuts: List<NanoKvmAutomationPortHidShortcut> = shortcuts.toList()

    init {
        require(shortcuts.distinctBy { it.stableId }.size == shortcuts.size) {
            "HID shortcut catalog must not contain duplicate stable IDs"
        }
    }

    internal fun requireExactMember(shortcut: NanoKvmAutomationPortHidShortcut) {
        require(shortcuts.any { it === shortcut }) {
            "HID shortcut must be an exact member of the supplied port catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmAutomationPortHidCatalog(shortcuts=${shortcuts.size})"
}

internal sealed interface NanoKvmAutomationPortHidRunResult {
    data class Completed(val reportsSent: Int) : NanoKvmAutomationPortHidRunResult
    data class ConnectionLost(val reportsSent: Int) : NanoKvmAutomationPortHidRunResult
    data object Rejected : NanoKvmAutomationPortHidRunResult
}

internal data class NanoKvmAutomationPortLeaderKey(
    val code: String,
    val displayLabel: String,
    val known: Boolean,
    val enabled: Boolean,
)

internal class NanoKvmAutomationPortAutostartScript internal constructor(
    val displayName: String,
) {
    override fun toString(): String =
        "NanoKvmAutomationPortAutostartScript(displayName=<redacted>)"
}

internal class NanoKvmAutomationPortAutostartCatalog internal constructor(
    scripts: List<NanoKvmAutomationPortAutostartScript>,
) {
    val scripts: List<NanoKvmAutomationPortAutostartScript> = scripts.toList()

    internal fun requireExactMember(script: NanoKvmAutomationPortAutostartScript) {
        require(scripts.any { it === script }) {
            "Autostart script must be an exact member of the supplied port catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmAutomationPortAutostartCatalog(scripts=${scripts.size})"
}

/** Mutable, closeable result buffer. Incidental diagnostics never include its content. */
internal class NanoKvmAutomationPortAutostartContent private constructor(
    private val ownedBytes: ByteArray,
) : Closeable {
    private var closed = false

    val byteCount: Int = ownedBytes.size

    @Synchronized
    fun copyBytes(): ByteArray {
        check(!closed) { "Autostart content is closed" }
        return ownedBytes.copyOf()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        ownedBytes.fill(0)
    }

    override fun toString(): String =
        "NanoKvmAutomationPortAutostartContent(byteCount=$byteCount, content=<redacted>)"

    companion object {
        internal fun takeOwnership(bytes: ByteArray): NanoKvmAutomationPortAutostartContent =
            NanoKvmAutomationPortAutostartContent(bytes)
    }
}

/** Single-use root-equivalent bytes passed from the editor to the protocol adapter. */
internal class NanoKvmAutomationPortAutostartWrite private constructor(
    private val ownedBytes: ByteArray,
) : Closeable {
    private var consumed = false

    val byteCount: Int = ownedBytes.size

    @Synchronized
    internal fun consumeProtocolContent(): NanoKvmAutostartWriteContent {
        check(!consumed) { "Autostart write content has already been consumed" }
        consumed = true
        return NanoKvmAutostartWriteContent.takeOwnership(ownedBytes)
    }

    @Synchronized
    override fun close() {
        consumed = true
        ownedBytes.fill(0)
    }

    override fun toString(): String =
        "NanoKvmAutomationPortAutostartWrite(byteCount=$byteCount, content=<redacted>)"

    companion object {
        internal fun takeOwnership(bytes: ByteArray): NanoKvmAutomationPortAutostartWrite {
            require(bytes.isNotEmpty() && bytes.size <= MAX_AUTOSTART_CONTENT_BYTES) {
                "Autostart content is outside the supported size"
            }
            val retained = try {
                bytes.copyOf()
            } finally {
                bytes.fill(0)
            }
            return NanoKvmAutomationPortAutostartWrite(retained)
        }
    }
}

internal enum class NanoKvmAutomationPortAutostartWriteKind { CREATE, UPDATE }

internal data class NanoKvmAutomationPortAutostartReceipt(
    val displayName: String,
    val byteCount: Int,
    val kind: NanoKvmAutomationPortAutostartWriteKind,
)

/** Direct adapter for the pinned NanoKVM protocol surface and the active console input socket. */
internal class NanoKvmProtocolAutomationPort(
    private val api: NanoKvmApi,
    private val releaseInput: () -> Boolean,
    private val currentInput: () -> NanoKvmInputSocket?,
) : NanoKvmAutomationPort {
    private var latestHidCatalog: ProtocolHidCatalogBinding? = null
    private var latestAutostartCatalog: ProtocolAutostartCatalogBinding? = null

    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        latestHidCatalog = null
        val protocolCatalog = api.savedHidShortcuts()
        val members = protocolCatalog.shortcuts.map { shortcut ->
            NanoKvmAutomationPortHidShortcut(
                stableId = shortcut.id,
                keys = shortcut.keys.map { key ->
                    NanoKvmAutomationPortHidKey(key.code, key.label, key.knownCode != null)
                },
                runnable = shortcut.isRunnable,
            ) to shortcut
        }
        val portCatalog = NanoKvmAutomationPortHidCatalog(
            shortcuts = members.map { it.first },
        )
        val protocolMembers =
            IdentityHashMap<NanoKvmAutomationPortHidShortcut, NanoKvmSavedHidShortcut>()
        members.forEach { (portShortcut, protocolShortcut) ->
            protocolMembers[portShortcut] = protocolShortcut
        }
        latestHidCatalog = ProtocolHidCatalogBinding(
            portCatalog = portCatalog,
            protocolCatalog = protocolCatalog,
            members = protocolMembers,
        )
        return portCatalog
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) {
        val draft = NanoKvmHidShortcutDraft.record(wireCodes.map(NanoKvmHidKeyCode::known))
        // The server returns no created ID. Consume the old snapshot before the sole dispatch.
        latestHidCatalog = null
        api.addSavedHidShortcut(draft)
    }

    override fun releaseAllInput(): Boolean = releaseInput()

    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult {
        val binding = requireLatestHidCatalog(catalog)
        val protocolShortcut = binding.requireProtocolShortcut(shortcut)
        return when (
            val result = currentInput()?.sendSavedHidShortcut(protocolShortcut)
                ?: NanoKvmHidShortcutRunResult.ConnectionLost(reportsSent = 0)
        ) {
            is NanoKvmHidShortcutRunResult.Completed ->
                NanoKvmAutomationPortHidRunResult.Completed(result.reportsSent)
            is NanoKvmHidShortcutRunResult.ConnectionLost ->
                NanoKvmAutomationPortHidRunResult.ConnectionLost(result.reportsSent)
            is NanoKvmHidShortcutRunResult.Rejected -> NanoKvmAutomationPortHidRunResult.Rejected
        }
    }

    override suspend fun deleteHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ) {
        val binding = requireLatestHidCatalog(catalog)
        val protocolShortcut = binding.requireProtocolShortcut(shortcut)
        latestHidCatalog = null
        api.deleteSavedHidShortcut(binding.protocolCatalog, protocolShortcut)
    }

    override suspend fun leaderKey(): NanoKvmAutomationPortLeaderKey {
        val state = api.leaderKey()
        return NanoKvmAutomationPortLeaderKey(
            code = state.code,
            displayLabel = state.knownCode?.defaultLabel ?: state.code,
            known = !state.enabled || state.knownCode != null,
            enabled = state.enabled,
        )
    }

    override suspend fun setLeaderKey(wireCode: String) =
        api.setLeaderKey(NanoKvmHidKeyCode.known(wireCode))

    override suspend fun disableLeaderKey() = api.disableLeaderKey()

    override suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog {
        latestAutostartCatalog = null
        val protocolCatalog = api.autostartScripts()
        val members = protocolCatalog.scripts.map { script ->
            NanoKvmAutomationPortAutostartScript(displayName = script.name) to script
        }
        val portCatalog = NanoKvmAutomationPortAutostartCatalog(
            scripts = members.map { it.first },
        )
        val protocolMembers =
            IdentityHashMap<NanoKvmAutomationPortAutostartScript, NanoKvmAutostartScript>()
        members.forEach { (portScript, protocolScript) ->
            protocolMembers[portScript] = protocolScript
        }
        latestAutostartCatalog = ProtocolAutostartCatalogBinding(
            portCatalog = portCatalog,
            protocolCatalog = protocolCatalog,
            members = protocolMembers,
        )
        return portCatalog
    }

    override suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent {
        val binding = requireLatestAutostartCatalog(catalog)
        val content = api.autostartContent(
            binding.protocolCatalog,
            binding.requireProtocolScript(script),
        )
        return content.useToPortContent()
    }

    override suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        val binding = requireLatestAutostartCatalog(catalog)
        latestAutostartCatalog = null
        val protocolContent = content.consumeProtocolContent()
        try {
            val receipt = api.createAutostartScript(
                binding.protocolCatalog,
                fileName,
                protocolContent,
            )
            return receipt.toPortReceipt()
        } finally {
            protocolContent.close()
            content.close()
        }
    }

    override suspend fun updateAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        val binding = requireLatestAutostartCatalog(catalog)
        val protocolScript = binding.requireProtocolScript(script)
        latestAutostartCatalog = null
        val protocolContent = content.consumeProtocolContent()
        try {
            val receipt = api.updateAutostartScript(
                binding.protocolCatalog,
                protocolScript,
                protocolContent,
            )
            return receipt.toPortReceipt()
        } finally {
            protocolContent.close()
            content.close()
        }
    }

    override suspend fun deleteAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ) {
        val binding = requireLatestAutostartCatalog(catalog)
        val protocolScript = binding.requireProtocolScript(script)
        latestAutostartCatalog = null
        api.deleteAutostartScript(binding.protocolCatalog, protocolScript)
    }

    private fun requireLatestHidCatalog(
        catalog: NanoKvmAutomationPortHidCatalog,
    ): ProtocolHidCatalogBinding = latestHidCatalog
        ?.takeIf { it.portCatalog === catalog }
        ?: throw IllegalArgumentException("Foreign or stale HID shortcut catalog")

    private fun requireLatestAutostartCatalog(
        catalog: NanoKvmAutomationPortAutostartCatalog,
    ): ProtocolAutostartCatalogBinding = latestAutostartCatalog
        ?.takeIf { it.portCatalog === catalog }
        ?: throw IllegalArgumentException("Foreign or stale autostart catalog")

    private class ProtocolHidCatalogBinding(
        val portCatalog: NanoKvmAutomationPortHidCatalog,
        val protocolCatalog: NanoKvmSavedHidShortcutCatalog,
        private val members: Map<NanoKvmAutomationPortHidShortcut, NanoKvmSavedHidShortcut>,
    ) {
        fun requireProtocolShortcut(
            shortcut: NanoKvmAutomationPortHidShortcut,
        ): NanoKvmSavedHidShortcut {
            portCatalog.requireExactMember(shortcut)
            return members[shortcut]
                ?: throw IllegalArgumentException("Foreign HID shortcut handle")
        }

        override fun toString(): String = "ProtocolHidCatalogBinding(<redacted>)"
    }

    private class ProtocolAutostartCatalogBinding(
        val portCatalog: NanoKvmAutomationPortAutostartCatalog,
        val protocolCatalog: NanoKvmAutostartCatalog,
        private val members: Map<NanoKvmAutomationPortAutostartScript, NanoKvmAutostartScript>,
    ) {
        fun requireProtocolScript(
            script: NanoKvmAutomationPortAutostartScript,
        ): NanoKvmAutostartScript {
            portCatalog.requireExactMember(script)
            return members[script]
                ?: throw IllegalArgumentException("Foreign autostart handle")
        }

        override fun toString(): String = "ProtocolAutostartCatalogBinding(<redacted>)"
    }
}

private fun NanoKvmAutostartContent.useToPortContent(): NanoKvmAutomationPortAutostartContent =
    try {
        NanoKvmAutomationPortAutostartContent.takeOwnership(copyBytes())
    } finally {
        close()
    }

private fun org.nanokvm.protocol.NanoKvmAutostartWriteReceipt.toPortReceipt() =
    NanoKvmAutomationPortAutostartReceipt(
        displayName = fileName,
        byteCount = byteCount,
        kind = when (kind) {
            NanoKvmAutostartWriteKind.CREATE -> NanoKvmAutomationPortAutostartWriteKind.CREATE
            NanoKvmAutostartWriteKind.UPDATE -> NanoKvmAutomationPortAutostartWriteKind.UPDATE
        },
    )

/**
 * Creates one isolated automation gateway for an exact authenticated destination generation.
 * The input callbacks must reference the existing console socket/state; the gateway never creates
 * a competing WebSocket or reconnects input on its own.
 */
internal fun AuthenticatedNanoKvmSession.createAutomationGateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
    releaseAllInput: () -> Boolean,
    currentInput: () -> NanoKvmInputSocket?,
    onAuthenticationExpired: () -> Unit = {},
): NanoKvmAutomationGateway {
    val captured = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmAutomationGateway(
        port = NanoKvmProtocolAutomationPort(client.api, releaseAllInput, currentInput),
        binding = captured,
        onAuthenticationExpired = onAuthenticationExpired,
        currentBinding = currentBinding,
    )
}
