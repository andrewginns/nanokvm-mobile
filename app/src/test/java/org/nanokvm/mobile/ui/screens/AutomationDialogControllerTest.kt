package org.nanokvm.mobile.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.runtime.NanoKvmAutomationCommandResult
import org.nanokvm.mobile.runtime.NanoKvmAutomationError
import org.nanokvm.mobile.runtime.NanoKvmAutomationGateway
import org.nanokvm.mobile.runtime.NanoKvmAutomationPort
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartContent
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartReceipt
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartScript
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartWrite
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortAutostartWriteKind
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidCatalog
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidKey
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidRunResult
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortHidShortcut
import org.nanokvm.mobile.runtime.NanoKvmAutomationPortLeaderKey
import org.nanokvm.mobile.runtime.NanoKvmAutomationReadResult
import org.nanokvm.mobile.runtime.NanoKvmSessionBinding
import org.nanokvm.mobile.runtime.MAX_OPERATOR_OUTPUT_BYTES
import org.nanokvm.mobile.runtime.OperatorEphemeralOutput
import org.nanokvm.mobile.runtime.OperatorOutputKind

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDialogControllerTest {
    @Test
    fun `initial refresh publishes immutable catalogs and releases loading`() = runTest {
        val port = ControllerAutomationPort()
        val gateway = gateway(port)
        val controller = AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = true,
            coroutineContext = coroutineContext,
        )

        advanceUntilIdle()

        assertEquals(1, port.hidListCalls)
        assertEquals(1, port.leaderReadCalls)
        assertEquals(1, port.autostartListCalls)
        assertNotNull(controller.state.value.hidCatalog)
        assertNotNull(controller.state.value.leaderKey)
        assertNotNull(controller.state.value.autostartCatalog)
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.controlsEnabled)

        controller.close()
    }

    @Test
    fun `duplicate mutation is rejected while first mutation owns the slot`() = runTest {
        val port = ControllerAutomationPort()
        val gateway = gateway(port)
        val controller = AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = true,
            coroutineContext = coroutineContext,
        )
        advanceUntilIdle()
        port.leaderMutationGate = CompletableDeferred()

        assertTrue(controller.setLeaderKey("KeyA"))
        runCurrent()
        assertTrue(controller.state.value.operationInProgress)
        assertFalse(controller.setLeaderKey("KeyB"))
        assertEquals(1, port.leaderMutationCalls)

        port.leaderMutationGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.leaderMutationCalls)
        assertFalse(controller.state.value.operationInProgress)

        controller.close()
    }

    @Test
    fun `failed post-mutation refresh is not masked by action success`() = runTest {
        val port = ControllerAutomationPort()
        val gateway = gateway(port)
        val controller = AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = true,
            coroutineContext = coroutineContext,
        )
        advanceUntilIdle()
        port.hidListFailure = IllegalStateException("fixture read failure")

        assertTrue(controller.setLeaderKey("KeyB"))
        advanceUntilIdle()

        assertEquals(AutomationNotice.ACTION_COMPLETE, controller.state.value.notice)
        assertEquals(AutomationNotice.READ_FAILED, controller.state.value.hidReadNotice)
        assertEquals(AutomationNotice.READ_FAILED, controller.state.value.visibleNotice)

        controller.close()
    }

    @Test
    fun `close cancels active work clears drafts and backgrounds gateway`() = runTest {
        val port = ControllerAutomationPort()
        val gateway = gateway(port)
        val controller = AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = true,
            coroutineContext = coroutineContext,
        )
        advanceUntilIdle()
        port.leaderMutationGate = CompletableDeferred()
        controller.updateAutostartName("private.py")
        controller.updateAutostartText("print('private')")

        assertTrue(controller.setLeaderKey("KeyA"))
        runCurrent()
        controller.close()
        advanceUntilIdle()

        assertTrue(port.leaderMutationCancelled)
        assertEquals("", controller.state.value.autostartName)
        assertEquals("", controller.state.value.autostartText)
        assertNull(controller.state.value.pendingAction)
        assertFalse(controller.state.value.operationInProgress)
        val refresh = gateway.refreshHidShortcuts()
        assertTrue(refresh is NanoKvmAutomationReadResult.Failure)
        assertEquals(
            NanoKvmAutomationError.Kind.NOT_FOREGROUND,
            (refresh as NanoKvmAutomationReadResult.Failure).error.kind,
        )
    }

    @Test
    fun `close discards pending autostart approval without dispatch`() = runTest {
        val port = ControllerAutomationPort()
        val gateway = gateway(port)
        val controller = AutomationDialogController(
            gateway = gateway,
            leaderKeyAvailable = true,
            coroutineContext = coroutineContext,
        )
        advanceUntilIdle()
        controller.updateAutostartName("new.py")
        controller.updateAutostartText("print('safe')\n")
        controller.reviewAutostartWrite()
        val pending = controller.state.value.pendingAction as PendingAutomationAction.AutostartWrite

        controller.close()
        val result = gateway.executeAutostartWrite(pending.approval)

        assertTrue(result is NanoKvmAutomationCommandResult.Rejected)
        assertEquals(0, port.autostartCreateCalls)
        assertEquals("", controller.state.value.autostartName)
        assertEquals("", controller.state.value.autostartText)
        assertNull(controller.state.value.pendingAction)
    }

    @Test
    fun `session owner retains automation controller across recreation and clears on dismiss`() =
        runTest {
            val owner = ConsoleSessionDraftOwner(coroutineContext)
            val gateway = gateway(ControllerAutomationPort())
            val session = ConsoleDraftSession("test-profile", "192.0.2.250", 7)
            val first = owner.automationController(session, gateway, leaderKeyAvailable = true)
            advanceUntilIdle()
            first.updateAutostartText("print('retained only in memory')")

            val restored = owner.automationController(session, gateway, leaderKeyAvailable = true)

            assertSame(first, restored)
            assertEquals("print('retained only in memory')", restored.state.value.autostartText)

            owner.clearAutomation()

            assertEquals("", first.state.value.autostartText)
        }

    @Test
    fun `session replacement clears bounded operator transcript and script body`() = runTest {
        val owner = ConsoleSessionDraftOwner(coroutineContext)
        val firstSession = ConsoleDraftSession("test-profile", "192.0.2.250", 7)
        val first = owner.operatorMemory(firstSession)
        first.updateScriptContent("echo retained")
        val largeOutput = "x".repeat(MAX_OPERATOR_OUTPUT_BYTES)
        first.appendOutput(OperatorEphemeralOutput(OperatorOutputKind.Terminal, largeOutput))
        first.appendOutput(OperatorEphemeralOutput(OperatorOutputKind.Terminal, largeOutput))

        assertEquals("", first.outputText)
        first.publishOutputSnapshot()
        assertTrue(first.outputText.encodeToByteArray().size <= MAX_OPERATOR_OUTPUT_BYTES)
        first.updateScriptContent("x".repeat(MAX_RETAINED_OPERATOR_SCRIPT_BYTES + 1))
        assertEquals("echo retained", first.scriptContent)
        assertEquals("echo retained".encodeToByteArray().size, first.scriptUtf8ByteCount)

        val replacement = owner.operatorMemory(
            ConsoleDraftSession("other-profile", "192.0.2.251", 8),
        )

        assertEquals("", first.outputText)
        assertEquals("", first.scriptContent)
        assertEquals(0, first.scriptUtf8ByteCount)
        assertEquals("", replacement.outputText)
        assertEquals("", replacement.scriptContent)
    }

    @Test(timeout = 5_000L)
    fun `operator memory coalesces many events until the UI publishes one snapshot`() {
        val memory = OperatorDialogMemory()

        repeat(10_000) {
            memory.appendOutput(OperatorEphemeralOutput(OperatorOutputKind.Terminal, "x"))
        }

        assertEquals("", memory.outputText)
        val published = memory.publishOutputSnapshot()
        assertEquals(10_000, published.length)
        assertSame(published, memory.publishOutputSnapshot())
    }

    private fun gateway(port: NanoKvmAutomationPort): NanoKvmAutomationGateway {
        val binding = NanoKvmSessionBinding(
            profileId = "test-profile",
            authority = "192.0.2.250",
            sessionGeneration = 7,
        )
        return NanoKvmAutomationGateway(
            port = port,
            binding = binding,
            currentBinding = { binding },
        )
    }
}

private class ControllerAutomationPort : NanoKvmAutomationPort {
    var hidListCalls = 0
    var leaderReadCalls = 0
    var leaderMutationCalls = 0
    var autostartListCalls = 0
    var autostartCreateCalls = 0
    var hidListFailure: Throwable? = null
    var leaderMutationGate: CompletableDeferred<Unit>? = null
    var leaderMutationCancelled = false

    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        hidListCalls++
        hidListFailure?.let { throw it }
        return NanoKvmAutomationPortHidCatalog(
            shortcuts = listOf(
                NanoKvmAutomationPortHidShortcut(
                    stableId = "controller-shortcut-a",
                    keys = listOf(NanoKvmAutomationPortHidKey("KeyA", "A", known = true)),
                    runnable = true,
                ),
            ),
        )
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) = Unit

    override fun releaseAllInput(): Boolean = true

    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult =
        NanoKvmAutomationPortHidRunResult.Completed(reportsSent = 1)

    override suspend fun deleteHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ) = Unit

    override suspend fun leaderKey(): NanoKvmAutomationPortLeaderKey {
        leaderReadCalls++
        return NanoKvmAutomationPortLeaderKey(
            code = "ControlRight",
            displayLabel = "Ctrl",
            known = true,
            enabled = true,
        )
    }

    override suspend fun setLeaderKey(wireCode: String) {
        leaderMutationCalls++
        try {
            leaderMutationGate?.await()
        } catch (cancelled: CancellationException) {
            leaderMutationCancelled = true
            throw cancelled
        }
    }

    override suspend fun disableLeaderKey() {
        setLeaderKey("")
    }

    override suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog {
        autostartListCalls++
        return NanoKvmAutomationPortAutostartCatalog(emptyList())
    }

    override suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent =
        NanoKvmAutomationPortAutostartContent.takeOwnership(ByteArray(0))

    override suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        autostartCreateCalls++
        val byteCount = content.byteCount
        content.close()
        return NanoKvmAutomationPortAutostartReceipt(
            displayName = fileName,
            byteCount = byteCount,
            kind = NanoKvmAutomationPortAutostartWriteKind.CREATE,
        )
    }

    override suspend fun updateAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        val byteCount = content.byteCount
        content.close()
        return NanoKvmAutomationPortAutostartReceipt(
            displayName = script.displayName,
            byteCount = byteCount,
            kind = NanoKvmAutomationPortAutostartWriteKind.UPDATE,
        )
    }

    override suspend fun deleteAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ) = Unit
}
