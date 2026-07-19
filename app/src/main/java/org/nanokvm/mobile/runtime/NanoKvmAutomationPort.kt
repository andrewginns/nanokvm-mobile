package org.nanokvm.mobile.runtime

import java.io.Closeable
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
    keys: List<NanoKvmAutomationPortHidKey>,
    val runnable: Boolean,
    internal val opaqueToken: Any,
) {
    val keys: List<NanoKvmAutomationPortHidKey> = keys.toList()

    override fun toString(): String =
        "NanoKvmAutomationPortHidShortcut(keys=${keys.size}, token=<redacted>)"
}

internal class NanoKvmAutomationPortHidCatalog internal constructor(
    shortcuts: List<NanoKvmAutomationPortHidShortcut>,
    internal val opaqueToken: Any,
) {
    val shortcuts: List<NanoKvmAutomationPortHidShortcut> = shortcuts.toList()

    internal fun requireExactMember(shortcut: NanoKvmAutomationPortHidShortcut) {
        require(shortcuts.any { it === shortcut }) {
            "HID shortcut must be an exact member of the supplied port catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmAutomationPortHidCatalog(shortcuts=${shortcuts.size}, token=<redacted>)"
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
    internal val opaqueToken: Any,
) {
    override fun toString(): String =
        "NanoKvmAutomationPortAutostartScript(displayName=<redacted>, token=<redacted>)"
}

internal class NanoKvmAutomationPortAutostartCatalog internal constructor(
    scripts: List<NanoKvmAutomationPortAutostartScript>,
    internal val opaqueToken: Any,
) {
    val scripts: List<NanoKvmAutomationPortAutostartScript> = scripts.toList()

    internal fun requireExactMember(script: NanoKvmAutomationPortAutostartScript) {
        require(scripts.any { it === script }) {
            "Autostart script must be an exact member of the supplied port catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmAutomationPortAutostartCatalog(scripts=${scripts.size}, token=<redacted>)"
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
    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        val catalog = api.savedHidShortcuts()
        return NanoKvmAutomationPortHidCatalog(
            shortcuts = catalog.shortcuts.map { shortcut ->
                NanoKvmAutomationPortHidShortcut(
                    keys = shortcut.keys.map { key ->
                        NanoKvmAutomationPortHidKey(key.code, key.label, key.knownCode != null)
                    },
                    runnable = shortcut.isRunnable,
                    opaqueToken = ProtocolHidShortcutToken(shortcut),
                )
            },
            opaqueToken = ProtocolHidCatalogToken(catalog),
        )
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) {
        api.addSavedHidShortcut(
            NanoKvmHidShortcutDraft.record(wireCodes.map(NanoKvmHidKeyCode::known)),
        )
    }

    override fun releaseAllInput(): Boolean = releaseInput()

    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult {
        catalog.requireExactMember(shortcut)
        return when (
            val result = currentInput()?.sendSavedHidShortcut(shortcut.protocolShortcut())
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
        catalog.requireExactMember(shortcut)
        api.deleteSavedHidShortcut(catalog.protocolCatalog(), shortcut.protocolShortcut())
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
        val catalog = api.autostartScripts()
        return NanoKvmAutomationPortAutostartCatalog(
            scripts = catalog.scripts.map { script ->
                NanoKvmAutomationPortAutostartScript(
                    displayName = script.name,
                    opaqueToken = ProtocolAutostartScriptToken(script),
                )
            },
            opaqueToken = ProtocolAutostartCatalogToken(catalog),
        )
    }

    override suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent {
        catalog.requireExactMember(script)
        val content = api.autostartContent(catalog.protocolCatalog(), script.protocolScript())
        return content.useToPortContent()
    }

    override suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        val protocolContent = content.consumeProtocolContent()
        try {
            val receipt = api.createAutostartScript(catalog.protocolCatalog(), fileName, protocolContent)
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
        catalog.requireExactMember(script)
        val protocolContent = content.consumeProtocolContent()
        try {
            val receipt = api.updateAutostartScript(
                catalog.protocolCatalog(),
                script.protocolScript(),
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
        catalog.requireExactMember(script)
        api.deleteAutostartScript(catalog.protocolCatalog(), script.protocolScript())
    }

    private fun NanoKvmAutomationPortHidCatalog.protocolCatalog(): NanoKvmSavedHidShortcutCatalog =
        (opaqueToken as? ProtocolHidCatalogToken)?.catalog
            ?: throw IllegalArgumentException("Foreign HID shortcut catalog")

    private fun NanoKvmAutomationPortHidShortcut.protocolShortcut(): NanoKvmSavedHidShortcut =
        (opaqueToken as? ProtocolHidShortcutToken)?.shortcut
            ?: throw IllegalArgumentException("Foreign HID shortcut handle")

    private fun NanoKvmAutomationPortAutostartCatalog.protocolCatalog(): NanoKvmAutostartCatalog =
        (opaqueToken as? ProtocolAutostartCatalogToken)?.catalog
            ?: throw IllegalArgumentException("Foreign autostart catalog")

    private fun NanoKvmAutomationPortAutostartScript.protocolScript(): NanoKvmAutostartScript =
        (opaqueToken as? ProtocolAutostartScriptToken)?.script
            ?: throw IllegalArgumentException("Foreign autostart handle")

    private class ProtocolHidCatalogToken(val catalog: NanoKvmSavedHidShortcutCatalog)
    private class ProtocolHidShortcutToken(val shortcut: NanoKvmSavedHidShortcut)
    private class ProtocolAutostartCatalogToken(val catalog: NanoKvmAutostartCatalog)
    private class ProtocolAutostartScriptToken(val script: NanoKvmAutostartScript)
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
