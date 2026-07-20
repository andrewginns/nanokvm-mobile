package org.nanokvm.mobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import org.nanokvm.mobile.runtime.BoundedOperatorOutputBuffer
import org.nanokvm.mobile.runtime.NanoKvmAutomationGateway
import org.nanokvm.mobile.runtime.OperatorEphemeralOutput
import org.nanokvm.mobile.runtime.OperatorOutputKind
import org.nanokvm.mobile.runtime.utf8SizeAtMost

internal data class ConsoleDraftSession(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    override fun toString(): String =
        "ConsoleDraftSession(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

/**
 * ViewModel-retained, memory-only owner for drafts that must survive Activity recreation without
 * entering SavedState. Binding every value to one authenticated generation prevents a draft or
 * one-use automation approval from crossing sessions.
 */
internal class ConsoleSessionDraftOwner internal constructor(
    private val automationCoroutineContext: CoroutineContext = Dispatchers.Main.immediate,
) : AutoCloseable {
    private var session: ConsoleDraftSession? = null
    private var automationGateway: NanoKvmAutomationGateway? = null
    private var automationLeaderKeyAvailable: Boolean? = null
    private var automationController: AutomationDialogController? = null
    private var operatorMemory: OperatorDialogMemory? = null

    internal fun automationController(
        session: ConsoleDraftSession,
        gateway: NanoKvmAutomationGateway,
        leaderKeyAvailable: Boolean,
    ): AutomationDialogController {
        bind(session)
        automationController?.takeIf {
            automationGateway === gateway &&
                automationLeaderKeyAvailable == leaderKeyAvailable
        }?.let { return it }
        automationController?.close()
        return AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = leaderKeyAvailable,
            coroutineContext = automationCoroutineContext,
        ).also {
            automationGateway = gateway
            automationLeaderKeyAvailable = leaderKeyAvailable
            automationController = it
        }
    }

    internal fun operatorMemory(session: ConsoleDraftSession): OperatorDialogMemory {
        bind(session)
        return operatorMemory ?: OperatorDialogMemory().also { operatorMemory = it }
    }

    internal fun clearAutomation() {
        automationController?.close()
        automationController = null
        automationGateway = null
        automationLeaderKeyAvailable = null
        releaseSessionIfEmpty()
    }

    internal fun clearOperator() {
        operatorMemory?.clear()
        operatorMemory = null
        releaseSessionIfEmpty()
    }

    internal fun clear() {
        automationController?.close()
        automationController = null
        automationGateway = null
        automationLeaderKeyAvailable = null
        operatorMemory?.clear()
        operatorMemory = null
        session = null
    }

    override fun close() = clear()

    private fun bind(next: ConsoleDraftSession) {
        if (session == null) {
            session = next
        } else if (session != next) {
            clear()
            session = next
        }
    }

    private fun releaseSessionIfEmpty() {
        if (automationController == null && operatorMemory == null) session = null
    }

    override fun toString(): String =
        "ConsoleSessionDraftOwner(bound=${session != null}, automation=${automationController != null}, " +
            "operator=${operatorMemory != null}, content=<redacted>)"
}

/** Bounded transcript and script body; neither is placed in Android SavedState. */
internal class OperatorDialogMemory {
    private val outputBuffer = BoundedOperatorOutputBuffer()
    private var closed = false
    private var outputDirty = false

    var outputText: String by mutableStateOf("")
        private set
    var scriptContent: String by mutableStateOf("")
        private set
    var scriptUtf8ByteCount: Int by mutableIntStateOf(0)
        private set

    fun appendOutput(event: OperatorEphemeralOutput) {
        if (closed) return
        val marker = when (event.kind) {
            OperatorOutputKind.Terminal -> ""
            OperatorOutputKind.Script -> "\n--- script output ---\n"
        }
        outputBuffer.append(marker + event.copyText())
        outputDirty = true
    }

    /** Materializes at most one immutable transcript snapshot for any number of pending events. */
    fun publishOutputSnapshot(): String {
        if (!closed && outputDirty) {
            outputText = outputBuffer.snapshot()
            outputDirty = false
        }
        return outputText
    }

    fun updateScriptContent(value: String) {
        if (closed) return
        val byteCount = value.utf8SizeAtMost(MAX_RETAINED_OPERATOR_SCRIPT_BYTES)
        if (byteCount != null) {
            scriptContent = value
            scriptUtf8ByteCount = byteCount
        }
    }

    fun takeScriptContent(): ByteArray {
        if (closed) return ByteArray(0)
        return scriptContent.encodeToByteArray().also {
            scriptContent = ""
            scriptUtf8ByteCount = 0
        }
    }

    fun clearOutput() {
        if (closed) return
        outputBuffer.clear()
        outputDirty = false
        outputText = ""
    }

    fun clear() {
        if (closed) return
        closed = true
        outputBuffer.clear()
        outputDirty = false
        outputText = ""
        scriptContent = ""
        scriptUtf8ByteCount = 0
    }

    override fun toString(): String =
        "OperatorDialogMemory(outputLength=${outputText.length}, script=<redacted>)"
}

internal const val MAX_RETAINED_OPERATOR_SCRIPT_BYTES: Int = 512 * 1_024
