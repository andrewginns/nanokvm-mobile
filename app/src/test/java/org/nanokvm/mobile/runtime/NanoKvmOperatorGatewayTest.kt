package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.NanoKvmScriptRunMode
import org.nanokvm.protocol.NanoKvmSerialBaud
import org.nanokvm.protocol.NanoKvmSerialConfiguration
import org.nanokvm.protocol.NanoKvmSerialPort
import org.nanokvm.protocol.NanoKvmTerminalConnectionState
import org.nanokvm.protocol.NanoKvmTerminalEvent
import org.nanokvm.protocol.NanoKvmTerminalSize

@OptIn(ExperimentalCoroutinesApi::class)
class NanoKvmOperatorGatewayTest {
    @Test
    fun `stale binding prevents elevated entry before a socket is created`() = runTest {
        val port = FakeOperatorPort()
        val captured = binding(generation = 4)
        var current: NanoKvmSessionBinding? = binding(generation = 5)
        val gateway = gateway(port, captured, backgroundScope) { current }
        gateway.terminal.onForeground()

        val approval = gateway.terminal.recordExplicitElevatedEntryApproval()
        val result = gateway.terminal.enter(approval)

        assertNull(approval)
        assertRejected(result, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        assertEquals(0, port.newTerminalCalls)
        current = null
        gateway.close()
    }

    @Test
    fun `background closes terminal and invalidates unused elevated approval`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.terminal.onForeground()
        val approval = requireNotNull(gateway.terminal.recordExplicitElevatedEntryApproval())

        assertTrue(gateway.terminal.enter(approval) is NanoKvmOperatorActionResult.Dispatched)
        runCurrent()
        assertTrue(gateway.terminal.state.value is NanoKvmOperatorTerminalState.Connected)
        val firstTerminal = port.terminals.single()
        // An active owner cannot mint another approval.
        val unusedApproval = gateway.terminal.recordExplicitElevatedEntryApproval()
        assertNull(unusedApproval)

        gateway.terminal.onBackground()

        assertEquals(1, firstTerminal.closeCalls)
        assertEquals(NanoKvmOperatorTerminalState.Inactive, gateway.terminal.state.value)
        assertEquals(1, firstTerminal.connectCalls)

        gateway.terminal.onForeground()
        assertRejected(
            gateway.terminal.enter(approval),
            NanoKvmOperatorError.Kind.ELEVATED_APPROVAL_REQUIRED,
        )
        assertEquals(1, port.newTerminalCalls)

        val secondApproval = requireNotNull(gateway.terminal.recordExplicitElevatedEntryApproval())
        assertTrue(gateway.terminal.enter(secondApproval) is NanoKvmOperatorActionResult.Dispatched)
        assertEquals(2, port.newTerminalCalls)
        gateway.close()
        assertEquals(1, port.terminals.last().closeCalls)
    }

    @Test
    fun `approval minted before background cannot be used after returning`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.terminal.onForeground()
        val oldApproval = requireNotNull(gateway.terminal.recordExplicitElevatedEntryApproval())

        gateway.terminal.onBackground()
        gateway.terminal.onForeground()

        assertRejected(
            gateway.terminal.enter(oldApproval),
            NanoKvmOperatorError.Kind.ELEVATED_APPROVAL_REQUIRED,
        )
        assertEquals(0, port.newTerminalCalls)
    }

    @Test
    fun `terminal discards callbacks from a foreign protocol generation`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        connectTerminal(gateway)
        val terminal = port.terminals.single()

        terminal.emit(NanoKvmTerminalEvent.Closed(generation = 77, code = 1000, reason = "private"))
        runCurrent()

        assertTrue(gateway.terminal.state.value is NanoKvmOperatorTerminalState.Connected)
        assertEquals(0, terminal.closeCalls)
        gateway.close()
    }

    @Test
    fun `terminal input is dispatched directly once and never replayed after lifecycle change`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        connectTerminal(gateway)
        val terminal = port.terminals.single()

        assertTrue(
            gateway.terminal.sendInput("one private command\r") is
                NanoKvmOperatorActionResult.Dispatched,
        )
        terminal.acceptInput = false
        assertRejected(
            gateway.terminal.sendInput("second private command\r"),
            NanoKvmOperatorError.Kind.NOT_CONNECTED,
        )

        gateway.terminal.onBackground()
        gateway.terminal.onForeground()

        assertEquals(2, terminal.inputCalls)
        assertEquals(1, terminal.connectCalls)
        assertEquals(1, port.newTerminalCalls)
        assertTrue(gateway.terminal.output.replayCache.isEmpty())
    }

    @Test
    fun `serial configuration stays typed and exit is one shot`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        connectTerminal(gateway)
        val terminal = port.terminals.single()
        val configuration = NanoKvmSerialConfiguration(
            port = NanoKvmSerialPort.TTY_S2,
            baud = NanoKvmSerialBaud.B921600,
        )

        assertTrue(
            gateway.terminal.startSerial(configuration) is NanoKvmOperatorActionResult.Dispatched,
        )
        assertSame(configuration, terminal.serialConfigurations.single())
        assertTrue(gateway.terminal.exitSerial() is NanoKvmOperatorActionResult.Dispatched)
        assertRejected(
            gateway.terminal.exitSerial(),
            NanoKvmOperatorError.Kind.NOT_CONNECTED,
        )

        assertEquals(1, terminal.serialStartCalls)
        assertEquals(1, terminal.serialExitCalls)
        assertEquals(1, terminal.closeCalls)
    }

    @Test
    fun `script operation rejects a session that changed after listing`() = runTest {
        val port = FakeOperatorPort()
        val captured = binding(generation = 8)
        var current: NanoKvmSessionBinding? = captured
        val gateway = gateway(port, captured, backgroundScope) { current }
        val catalog = success(gateway.refreshScripts())
        current = binding(generation = 9)

        val result = gateway.runScript(
            catalog,
            catalog.scripts.single(),
            NanoKvmScriptRunMode.FOREGROUND,
        )

        assertScriptRejected(result, NanoKvmOperatorError.Kind.SESSION_CHANGED)
        assertEquals(0, port.runCalls)
    }

    @Test
    fun `script output completing after generation change is discarded`() = runTest {
        val captured = binding(generation = 12)
        var current: NanoKvmSessionBinding? = captured
        val port = FakeOperatorPort().apply {
            onRun = { current = binding(generation = 13) }
        }
        val gateway = gateway(port, captured, backgroundScope) { current }
        val catalog = success(gateway.refreshScripts())

        val result = gateway.runScript(
            catalog,
            catalog.scripts.single(),
            NanoKvmScriptRunMode.FOREGROUND,
        )

        assertTrue(result is NanoKvmOperatorScriptCommandResult.Indeterminate)
        result as NanoKvmOperatorScriptCommandResult.Indeterminate
        assertEquals(NanoKvmOperatorError.Kind.SESSION_CHANGED, result.dispatchError.kind)
        assertNull(result.refreshedCatalog)
        assertEquals(1, port.runCalls)
    }

    @Test
    fun `foreign and superseded script handles are rejected by identity`() = runTest {
        val port = FakeOperatorPort()
        val current = binding()
        val first = gateway(port, current, backgroundScope) { current }
        val second = gateway(port, current, backgroundScope) { current }
        val foreign = success(first.refreshScripts())
        second.refreshScripts()

        assertScriptRejected(
            second.runScript(
                foreign,
                foreign.scripts.single(),
                NanoKvmScriptRunMode.BACKGROUND,
            ),
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE,
        )

        val old = success(first.refreshScripts())
        first.refreshScripts()
        assertScriptRejected(
            first.deleteScript(old, old.scripts.single()),
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE,
        )
        assertEquals(0, port.runCalls)
        assertEquals(0, port.deleteCalls)
    }

    @Test
    fun `script port catalog requires its exact typed member`() {
        val member = NanoKvmOperatorPortScript("health-check.sh")
        val lookalike = NanoKvmOperatorPortScript("health-check.sh")
        val catalog = NanoKvmOperatorPortScriptCatalog(listOf(member))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            catalog.requireExactMember(lookalike)
        }

        assertEquals(
            "Script must be an exact member of the supplied port catalog",
            failure.message,
        )
    }

    @Test
    fun `ambiguous delete reconciles by read and never replays mutation`() = runTest {
        val port = FakeOperatorPort().apply { deleteFailsAfterApplying = true }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        val catalog = success(gateway.refreshScripts())
        val script = catalog.scripts.single()

        val result = gateway.deleteScript(catalog, script)

        assertTrue(result is NanoKvmOperatorScriptCommandResult.Reconciled)
        result as NanoKvmOperatorScriptCommandResult.Reconciled
        assertEquals(NanoKvmScriptDeleteObservation.ABSENT, result.observation)
        assertEquals(NanoKvmOperatorError.Kind.CONNECTION, result.dispatchError.kind)
        assertEquals(1, port.deleteCalls)
        assertEquals(2, port.listCalls)

        assertScriptRejected(
            gateway.runScript(catalog, script, NanoKvmScriptRunMode.FOREGROUND),
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE,
        )
        assertEquals(0, port.runCalls)
    }

    @Test
    fun `ambiguous upload invalidates prior snapshot without retry`() = runTest {
        val port = FakeOperatorPort().apply { uploadFailsAfterApplying = true }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        val old = success(gateway.refreshScripts())

        val result = gateway.uploadScript("new-safe.py", byteArrayOf(1, 2, 3))

        assertTrue(result is NanoKvmOperatorScriptCommandResult.Indeterminate)
        assertEquals(1, port.uploadCalls)
        assertScriptRejected(
            gateway.runScript(old, old.scripts.single(), NanoKvmScriptRunMode.FOREGROUND),
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE,
        )
        assertEquals(0, port.runCalls)
    }

    @Test
    fun `ambiguous foreground run is dispatched once consumes snapshot and exposes warning`() = runTest {
        val port = FakeOperatorPort().apply { runFailsAfterDispatch = true }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        val catalog = success(gateway.refreshScripts())
        val script = catalog.scripts.single()

        val result = gateway.runScript(catalog, script, NanoKvmScriptRunMode.FOREGROUND)

        assertTrue(result is NanoKvmOperatorScriptCommandResult.Indeterminate)
        assertEquals(
            setOf(NanoKvmScriptRunWarning.FOREGROUND_REQUEST_CANCELLATION_DOES_NOT_STOP_PROCESS),
            result.warnings,
        )
        assertEquals(1, port.runCalls)
        assertScriptRejected(
            gateway.runScript(catalog, script, NanoKvmScriptRunMode.FOREGROUND),
            NanoKvmOperatorError.Kind.FOREIGN_OR_STALE_STATE,
        )
        assertEquals(1, port.runCalls)
        assertEquals(
            setOf(NanoKvmScriptRunWarning.BACKGROUND_HAS_NO_STATUS_OR_CANCELLATION),
            NanoKvmScriptRunMode.BACKGROUND.operatorWarnings(),
        )
    }

    @Test
    fun `operator state output handles and results redact sensitive content`() = runTest {
        val port = FakeOperatorPort(output = "token=private-value")
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        val catalog = success(gateway.refreshScripts())
        val script = catalog.scripts.single()
        val result = gateway.runScript(catalog, script, NanoKvmScriptRunMode.FOREGROUND)
        result as NanoKvmOperatorScriptCommandResult.Completed<NanoKvmOperatorScriptExecution>
        val execution = result.value
        val output = execution.output
        val terminalOutput = NanoKvmOperatorTerminalOutput("terminal-private".encodeToByteArray())
        val approvalText = run {
            gateway.terminal.onForeground()
            requireNotNull(gateway.terminal.recordExplicitElevatedEntryApproval()).toString()
        }

        listOf(
            current.toString(),
            catalog.toString(),
            script.toString(),
            output.toString(),
            terminalOutput.toString(),
            execution.toString(),
            approvalText,
            NanoKvmOperatorError(
                NanoKvmOperatorOperation.SCRIPT_RUN,
                NanoKvmOperatorError.Kind.CONNECTION,
            ).toString(),
        ).forEach { rendered ->
            assertFalse(rendered.contains("192.0.2.250"))
            assertFalse(rendered.contains("private-value"))
            assertFalse(rendered.contains("terminal-private"))
            assertFalse(rendered.contains("health-check.sh"))
        }
        assertEquals("token=private-value", output.content)
        assertTrue(gateway.terminal.output.replayCache.isEmpty())
    }

    private fun TestScope.connectTerminal(gateway: NanoKvmOperatorGateway) {
        gateway.terminal.onForeground()
        val approval = requireNotNull(gateway.terminal.recordExplicitElevatedEntryApproval())
        assertTrue(gateway.terminal.enter(approval) is NanoKvmOperatorActionResult.Dispatched)
        runCurrent()
    }

    private fun binding(generation: Long = 1L) = NanoKvmSessionBinding(
        profileId = "office-profile",
        authority = "192.0.2.250",
        sessionGeneration = generation,
    )

    private fun gateway(
        port: NanoKvmOperatorPort,
        binding: NanoKvmSessionBinding,
        scope: CoroutineScope,
        current: () -> NanoKvmSessionBinding?,
    ) = NanoKvmOperatorGateway(port, binding, current, scope)

    private fun success(result: NanoKvmOperatorScriptReadResult): NanoKvmOperatorScriptCatalog =
        (result as NanoKvmOperatorScriptReadResult.Success).catalog

    private fun assertRejected(
        result: NanoKvmOperatorActionResult,
        kind: NanoKvmOperatorError.Kind,
    ) {
        assertTrue(result is NanoKvmOperatorActionResult.Rejected)
        assertEquals(kind, (result as NanoKvmOperatorActionResult.Rejected).error.kind)
    }

    private fun assertScriptRejected(
        result: NanoKvmOperatorScriptCommandResult<*>,
        kind: NanoKvmOperatorError.Kind,
    ) {
        assertTrue(result is NanoKvmOperatorScriptCommandResult.Rejected)
        assertEquals(kind, (result as NanoKvmOperatorScriptCommandResult.Rejected).error.kind)
    }

}

private class FakeOperatorPort(
    private val output: String = "ok",
) : NanoKvmOperatorPort {
    var newTerminalCalls = 0
    var listCalls = 0
    var uploadCalls = 0
    var runCalls = 0
    var deleteCalls = 0
    var uploadFailsAfterApplying = false
    var runFailsAfterDispatch = false
    var deleteFailsAfterApplying = false
    var onRun: (() -> Unit)? = null
    val terminals = mutableListOf<FakeOperatorTerminal>()
    private val scriptNames = mutableListOf("health-check.sh")

    override fun newTerminal(): NanoKvmOperatorTerminalPort {
        newTerminalCalls++
        return FakeOperatorTerminal().also(terminals::add)
    }

    override suspend fun listScripts(): NanoKvmOperatorPortScriptCatalog {
        listCalls++
        return NanoKvmOperatorPortScriptCatalog(
            scripts = scriptNames.map(::NanoKvmOperatorPortScript),
        )
    }

    override suspend fun uploadScript(
        fileName: String,
        content: ByteArray,
    ): NanoKvmOperatorPortUploadReceipt {
        uploadCalls++
        if (fileName !in scriptNames) scriptNames += fileName
        if (uploadFailsAfterApplying) throw IOException("response lost: $fileName")
        return NanoKvmOperatorPortUploadReceipt(fileName, content.size)
    }

    override suspend fun runScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmOperatorPortRunResult {
        catalog.requireExactMember(script)
        runCalls++
        onRun?.invoke()
        if (runFailsAfterDispatch) throw IOException("response lost: ${script.displayName}")
        return NanoKvmOperatorPortRunResult(mode, output)
    }

    override suspend fun deleteScript(
        catalog: NanoKvmOperatorPortScriptCatalog,
        script: NanoKvmOperatorPortScript,
    ) {
        catalog.requireExactMember(script)
        deleteCalls++
        scriptNames.remove(script.displayName)
        if (deleteFailsAfterApplying) throw IOException("response lost: ${script.displayName}")
    }
}

private class FakeOperatorTerminal : NanoKvmOperatorTerminalPort {
    private val mutableState = MutableStateFlow<NanoKvmTerminalConnectionState>(
        NanoKvmTerminalConnectionState.Disconnected,
    )
    private val mutableEvents = MutableSharedFlow<NanoKvmTerminalEvent>(extraBufferCapacity = 8)

    override val state: StateFlow<NanoKvmTerminalConnectionState> = mutableState
    override val events: SharedFlow<NanoKvmTerminalEvent> = mutableEvents

    var connectCalls = 0
    var inputCalls = 0
    var resizeCalls = 0
    var serialStartCalls = 0
    var serialExitCalls = 0
    var closeCalls = 0
    var acceptInput = true
    val serialConfigurations = mutableListOf<NanoKvmSerialConfiguration>()

    override fun connect(): Boolean {
        connectCalls++
        mutableState.value = NanoKvmTerminalConnectionState.Connecting(GENERATION)
        mutableState.value = NanoKvmTerminalConnectionState.Connected(GENERATION)
        return true
    }

    override fun sendInput(text: String): Boolean {
        inputCalls++
        return acceptInput
    }

    override fun resize(size: NanoKvmTerminalSize): Boolean {
        resizeCalls++
        return true
    }

    override fun startSerial(configuration: NanoKvmSerialConfiguration): Boolean {
        serialStartCalls++
        serialConfigurations += configuration
        return true
    }

    override suspend fun exitSerialAndDisconnect(): Boolean {
        serialExitCalls++
        return true
    }

    fun emit(event: NanoKvmTerminalEvent) {
        mutableEvents.tryEmit(event)
    }

    override fun close() {
        closeCalls++
        mutableState.value = NanoKvmTerminalConnectionState.Disconnected
    }

    private companion object {
        const val GENERATION = 7L
    }
}
