package org.nanokvm.mobile.runtime

import android.view.KeyEvent
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException

class NanoKvmAutomationGatewayTest {
    @Test
    fun `missing optional leader endpoint is unsupported`() = runTest {
        val current = binding(1)
        val port = FakeAutomationPort().apply {
            leaderReadFailure = HttpResponseException(404)
        }
        val gateway = foregroundGateway(port, current) { current }

        val result = gateway.refreshLeaderKey() as NanoKvmAutomationReadResult.Failure

        assertEquals(NanoKvmAutomationError.Kind.UNSUPPORTED, result.error.kind)
    }

    @Test
    fun `foreground and exact destination generation gate every catalog`() = runTest {
        val port = FakeAutomationPort()
        val captured = binding(4)
        var current: NanoKvmSessionBinding? = captured
        val gateway = gateway(port, captured) { current }

        assertReadFailure(
            gateway.refreshHidShortcuts(),
            NanoKvmAutomationError.Kind.NOT_FOREGROUND,
        )
        gateway.onForeground()
        val first = success(gateway.refreshHidShortcuts())
        gateway.onBackground()
        gateway.onForeground()

        val review = gateway.reviewHidShortcutAction(
            first,
            first.shortcuts.single(),
            NanoKvmHidShortcutAction.DELETE,
        )
        assertReviewRejected(review, NanoKvmAutomationError.Kind.FOREIGN_OR_STALE_STATE)

        current = binding(5)
        assertReadFailure(
            gateway.refreshAutostartScripts(),
            NanoKvmAutomationError.Kind.SESSION_CHANGED,
        )
        assertEquals(1, port.hidListCalls)
        assertEquals(0, port.autostartListCalls)
    }

    @Test
    fun `physical recorder maps common keys ignores repeats and caps reviewed chord at six`() {
        val recorder = NanoKvmPhysicalShortcutRecorder()

        assertEquals(
            NanoKvmPhysicalKeyRecordResult.ADDED,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT),
        )
        assertEquals(
            NanoKvmPhysicalKeyRecordResult.ADDED,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_A),
        )
        assertEquals(
            NanoKvmPhysicalKeyRecordResult.DUPLICATE,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_A),
        )
        assertEquals(
            NanoKvmPhysicalKeyRecordResult.REPEAT_IGNORED,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_B, repeatCount = 1),
        )
        listOf("KeyB", "KeyC", "KeyD", "KeyE").forEach {
            assertEquals(NanoKvmPhysicalKeyRecordResult.ADDED, recorder.recordWireCode(it))
        }
        assertEquals(
            NanoKvmPhysicalKeyRecordResult.LIMIT_REACHED,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_F),
        )
        assertEquals(
            NanoKvmPhysicalKeyRecordResult.UNSUPPORTED,
            recorder.recordAndroidKeyCode(KeyEvent.KEYCODE_VOLUME_UP),
        )
        assertEquals(
            listOf("ControlLeft", "KeyA", "KeyB", "KeyC", "KeyD", "KeyE"),
            recorder.keys.map { it.wireCode },
        )
        assertFalse(recorder.toString().contains("ControlLeft"))
    }

    @Test
    fun `HID run releases all input before and finally after one dispatch`() = runTest {
        val port = FakeAutomationPort()
        val current = binding(8)
        val gateway = foregroundGateway(port, current)
        val catalog = success(gateway.refreshHidShortcuts())
        val shortcut = catalog.shortcuts.single()
        val approval = ready(
            gateway.reviewHidShortcutAction(
                catalog,
                shortcut,
                NanoKvmHidShortcutAction.RUN,
            ),
        )

        val result = gateway.executeHidShortcutAction(approval)

        assertTrue(result is NanoKvmAutomationCommandResult.Completed<*>)
        result as NanoKvmAutomationCommandResult.Completed<*>
        assertEquals(NanoKvmAutomationHidRunReceipt(3), result.value)
        assertEquals(listOf("release", "run", "release"), port.inputEvents)
        assertEquals(1, port.hidRunCalls)
        assertCommandRejected(
            gateway.executeHidShortcutAction(approval),
            NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
        )
        assertEquals(1, port.hidRunCalls)
    }

    @Test
    fun `unknown shortcut and leader stay visible but read only`() = runTest {
        val port = FakeAutomationPort().apply {
            shortcutKnown = false
            leader = NanoKvmAutomationPortLeaderKey(
                code = "FutureLeader",
                displayLabel = "FutureLeader",
                known = false,
                enabled = true,
            )
        }
        val current = binding(11)
        val gateway = foregroundGateway(port, current)
        val catalog = success(gateway.refreshHidShortcuts())
        val shortcut = catalog.shortcuts.single()

        assertFalse(shortcut.runnable)
        assertFalse(shortcut.keys.single().known)
        assertReviewRejected(
            gateway.reviewHidShortcutAction(catalog, shortcut, NanoKvmHidShortcutAction.RUN),
            NanoKvmAutomationError.Kind.UNKNOWN_READ_ONLY,
        )
        val leader = success(gateway.refreshLeaderKey())
        assertFalse(leader.writable)
        assertEquals("FutureLeader", leader.code)
        assertCommandRejected(
            gateway.setLeaderKey(null),
            NanoKvmAutomationError.Kind.UNKNOWN_READ_ONLY,
        )
        assertEquals(0, port.leaderMutationCalls)
    }

    @Test
    fun `reviewed shortcut save is catalog bound consumed and never replayed`() = runTest {
        val port = FakeAutomationPort()
        val current = binding(13)
        val gateway = foregroundGateway(port, current)
        val catalog = success(gateway.refreshHidShortcuts())
        val keys = listOf(
            NanoKvmRecordedHidKey("ControlLeft", "Ctrl"),
            NanoKvmRecordedHidKey("KeyR", "R"),
        )
        val approval = ready(gateway.reviewHidShortcutSave(catalog, keys))

        assertTrue(
            gateway.executeHidShortcutSave(approval) is
                NanoKvmAutomationCommandResult.Completed<*>,
        )
        assertEquals(listOf("ControlLeft", "KeyR"), port.savedWireCodes.single())
        assertCommandRejected(
            gateway.executeHidShortcutSave(approval),
            NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
        )
        assertEquals(1, port.savedWireCodes.size)
        assertFalse(approval.toString().contains("ControlLeft"))
    }

    @Test
    fun `autostart create consumes mutable editor and exact confirmation before dispatch`() = runTest {
        val port = FakeAutomationPort(autostartNames = mutableListOf())
        val current = binding(17)
        val gateway = foregroundGateway(port, current)
        val catalog = success(gateway.refreshAutostartScripts())
        val editor = NanoKvmAutostartEditorBuffer.fromText("#!/bin/sh\necho private\n")
        val approval = ready(gateway.reviewAutostartCreate(catalog, "01-start.sh", editor))

        assertFalse(editor.toString().contains("private"))
        assertTrue(editor.toString().contains("closed=true"))
        val result = gateway.executeAutostartWrite(approval)

        assertTrue(result is NanoKvmAutomationCommandResult.Completed<*>)
        result as NanoKvmAutomationCommandResult.Completed<NanoKvmAutomationAutostartReceipt>
        assertEquals("01-start.sh", result.value.displayName)
        assertEquals(NanoKvmAutostartReviewKind.CREATE, result.value.kind)
        assertEquals(1, port.autostartCreateCalls)
        assertEquals(listOf("01-start.sh"), port.autostartNames)
        assertFalse(approval.toString().contains("private"))
        assertFalse(approval.toString().contains("01-start.sh"))
        assertCommandRejected(
            gateway.executeAutostartWrite(approval),
            NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
        )
        assertEquals(1, port.autostartCreateCalls)
    }

    @Test
    fun `autostart update and delete require fresh exact approvals and dispatch once`() = runTest {
        val port = FakeAutomationPort()
        val current = binding(19)
        val gateway = foregroundGateway(port, current)
        val updateCatalog = success(gateway.refreshAutostartScripts())
        val updateScript = updateCatalog.scripts.single()
        val update = ready(
            gateway.reviewAutostartUpdate(
                updateCatalog,
                updateScript,
                NanoKvmAutostartEditorBuffer.fromText("print('updated')\n"),
            ),
        )

        assertTrue(
            gateway.executeAutostartWrite(update) is NanoKvmAutomationCommandResult.Completed<*>,
        )
        assertEquals(1, port.autostartUpdateCalls)

        val deleteCatalog = success(gateway.refreshAutostartScripts())
        val delete = ready(
            gateway.reviewAutostartDelete(deleteCatalog, deleteCatalog.scripts.single()),
        )
        assertTrue(
            gateway.executeAutostartDelete(delete) is NanoKvmAutomationCommandResult.Completed<*>,
        )
        assertEquals(1, port.autostartDeleteCalls)
        assertCommandRejected(
            gateway.executeAutostartDelete(delete),
            NanoKvmAutomationError.Kind.CONFIRMATION_REQUIRED,
        )
        assertEquals(1, port.autostartDeleteCalls)
    }

    @Test
    fun `mutable import clears caller bytes and rejects unsafe text without retaining it`() {
        val source = "#!/bin/sh\necho import-secret\n".encodeToByteArray()
        val editor = NanoKvmAutostartEditorBuffer.importOwned(source)
        assertTrue(source.all { it == 0.toByte() })
        assertEquals("#!/bin/sh\necho import-secret\n", editor.copyText())
        assertFalse(editor.toString().contains("import-secret"))
        editor.close()
        assertTrue(editor.toString().contains("byteCount=0"))

        listOf(
            byteArrayOf(0),
            byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            ByteArray(org.nanokvm.protocol.MAX_AUTOSTART_CONTENT_BYTES + 1) { 1 },
        ).forEach { invalid ->
            runCatching { NanoKvmAutostartEditorBuffer.importOwned(invalid) }
                .onSuccess { throw AssertionError("Invalid import was accepted") }
            assertTrue(invalid.all { it == 0.toByte() })
        }
    }

    @Test
    fun `late response after generation change is discarded and diagnostics redact peer text`() =
        runTest {
            val captured = binding(23)
            var current: NanoKvmSessionBinding? = captured
            val port = FakeAutomationPort().apply {
                afterHidList = { current = binding(24) }
            }
            val gateway = foregroundGateway(port, captured) { current }

            assertReadFailure(
                gateway.refreshHidShortcuts(),
                NanoKvmAutomationError.Kind.SESSION_CHANGED,
            )

            current = captured
            gateway.onBackground()
            gateway.onForeground()
            port.afterHidList = null
            val catalog = success(gateway.refreshHidShortcuts())
            port.failRun = true
            val approval = ready(
                gateway.reviewHidShortcutAction(
                    catalog,
                    catalog.shortcuts.single(),
                    NanoKvmHidShortcutAction.RUN,
                ),
            )
            val result = gateway.executeHidShortcutAction(approval)
            assertTrue(result is NanoKvmAutomationCommandResult.Indeterminate)
            assertFalse(result.toString().contains("peer-secret"))
            assertEquals(1, port.hidRunCalls)
        }

    @Test
    fun `classified authentication expiry tears down the owning session once`() = runTest {
        val port = FakeAutomationPort().apply { failListAuthentication = true }
        val current = binding(29)
        var expiryCount = 0
        val gateway = NanoKvmAutomationGateway(
            port = port,
            binding = current,
            onAuthenticationExpired = { expiryCount++ },
            currentBinding = { current },
        ).also { it.onForeground() }

        assertReadFailure(
            gateway.refreshHidShortcuts(),
            NanoKvmAutomationError.Kind.AUTHENTICATION_EXPIRED,
        )
        assertEquals(1, expiryCount)
        assertEquals(1, port.hidListCalls)
    }

    @Test
    fun `late authentication expiry from replaced generation does not invoke teardown`() = runTest {
        val captured = binding(30)
        var current: NanoKvmSessionBinding? = captured
        var expiryCount = 0
        val port = FakeAutomationPort().apply {
            failListAuthentication = true
            beforeHidListFailure = { current = binding(31) }
        }
        val gateway = NanoKvmAutomationGateway(
            port = port,
            binding = captured,
            onAuthenticationExpired = { expiryCount++ },
            currentBinding = { current },
        ).also { it.onForeground() }

        assertReadFailure(
            gateway.refreshHidShortcuts(),
            NanoKvmAutomationError.Kind.AUTHENTICATION_EXPIRED,
        )
        assertEquals(0, expiryCount)
    }

    private fun binding(generation: Long) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.250",
        sessionGeneration = generation,
    )

    private fun gateway(
        port: NanoKvmAutomationPort,
        binding: NanoKvmSessionBinding,
        current: () -> NanoKvmSessionBinding?,
    ) = NanoKvmAutomationGateway(port, binding, currentBinding = current)

    private fun foregroundGateway(
        port: NanoKvmAutomationPort,
        binding: NanoKvmSessionBinding,
        current: () -> NanoKvmSessionBinding? = { binding },
    ): NanoKvmAutomationGateway = gateway(port, binding, current).also { it.onForeground() }

    private fun <Value> success(result: NanoKvmAutomationReadResult<Value>): Value =
        (result as NanoKvmAutomationReadResult.Success).value

    private fun <Approval> ready(result: NanoKvmAutomationReviewResult<Approval>): Approval =
        (result as NanoKvmAutomationReviewResult.Ready).approval

    private fun assertReadFailure(
        result: NanoKvmAutomationReadResult<*>,
        kind: NanoKvmAutomationError.Kind,
    ) {
        assertTrue(result is NanoKvmAutomationReadResult.Failure)
        assertEquals(kind, (result as NanoKvmAutomationReadResult.Failure).error.kind)
    }

    private fun assertReviewRejected(
        result: NanoKvmAutomationReviewResult<*>,
        kind: NanoKvmAutomationError.Kind,
    ) {
        assertTrue(result is NanoKvmAutomationReviewResult.Rejected)
        assertEquals(kind, (result as NanoKvmAutomationReviewResult.Rejected).error.kind)
    }

    private fun assertCommandRejected(
        result: NanoKvmAutomationCommandResult<*>,
        kind: NanoKvmAutomationError.Kind,
    ) {
        assertTrue(result is NanoKvmAutomationCommandResult.Rejected)
        assertEquals(kind, (result as NanoKvmAutomationCommandResult.Rejected).error.kind)
    }
}

private class FakeAutomationPort(
    val autostartNames: MutableList<String> = mutableListOf("boot.py"),
) : NanoKvmAutomationPort {
    var hidListCalls = 0
    var hidRunCalls = 0
    var hidDeleteCalls = 0
    var leaderMutationCalls = 0
    var autostartListCalls = 0
    var autostartCreateCalls = 0
    var autostartUpdateCalls = 0
    var autostartDeleteCalls = 0
    var shortcutKnown = true
    var failRun = false
    var failListAuthentication = false
    var beforeHidListFailure: (() -> Unit)? = null
    var afterHidList: (() -> Unit)? = null
    var leaderReadFailure: Throwable? = null
    var leader = NanoKvmAutomationPortLeaderKey(
        code = "ControlRight",
        displayLabel = "Ctrl",
        known = true,
        enabled = true,
    )
    val inputEvents = mutableListOf<String>()
    val savedWireCodes = mutableListOf<List<String>>()

    override suspend fun listHidShortcuts(): NanoKvmAutomationPortHidCatalog {
        hidListCalls++
        if (failListAuthentication) {
            beforeHidListFailure?.invoke()
            throw AuthenticationExpiredException()
        }
        val shortcut = NanoKvmAutomationPortHidShortcut(
            keys = listOf(
                NanoKvmAutomationPortHidKey(
                    code = if (shortcutKnown) "KeyA" else "FutureKey",
                    label = if (shortcutKnown) "A" else "Future",
                    known = shortcutKnown,
                ),
            ),
            runnable = shortcutKnown,
            opaqueToken = Any(),
        )
        return NanoKvmAutomationPortHidCatalog(listOf(shortcut), Any()).also {
            afterHidList?.invoke()
        }
    }

    override suspend fun saveHidShortcut(wireCodes: List<String>) {
        savedWireCodes += wireCodes.toList()
    }

    override fun releaseAllInput(): Boolean {
        inputEvents += "release"
        return true
    }

    override fun runHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ): NanoKvmAutomationPortHidRunResult {
        catalog.requireExactMember(shortcut)
        hidRunCalls++
        inputEvents += "run"
        if (failRun) throw IOException("peer-secret should be redacted")
        return NanoKvmAutomationPortHidRunResult.Completed(reportsSent = 3)
    }

    override suspend fun deleteHidShortcut(
        catalog: NanoKvmAutomationPortHidCatalog,
        shortcut: NanoKvmAutomationPortHidShortcut,
    ) {
        catalog.requireExactMember(shortcut)
        hidDeleteCalls++
    }

    override suspend fun leaderKey(): NanoKvmAutomationPortLeaderKey {
        leaderReadFailure?.let { throw it }
        return leader
    }

    override suspend fun setLeaderKey(wireCode: String) {
        leaderMutationCalls++
        leader = NanoKvmAutomationPortLeaderKey(wireCode, wireCode, known = true, enabled = true)
    }

    override suspend fun disableLeaderKey() {
        leaderMutationCalls++
        leader = NanoKvmAutomationPortLeaderKey("", "", known = true, enabled = false)
    }

    override suspend fun listAutostartScripts(): NanoKvmAutomationPortAutostartCatalog {
        autostartListCalls++
        return NanoKvmAutomationPortAutostartCatalog(
            scripts = autostartNames.map {
                NanoKvmAutomationPortAutostartScript(it, AutostartToken(it))
            },
            opaqueToken = Any(),
        )
    }

    override suspend fun readAutostartContent(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ): NanoKvmAutomationPortAutostartContent {
        catalog.requireExactMember(script)
        return NanoKvmAutomationPortAutostartContent.takeOwnership("print('safe')\n".encodeToByteArray())
    }

    override suspend fun createAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        fileName: String,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        autostartCreateCalls++
        autostartNames += fileName
        val bytes = content.byteCount
        content.close()
        return NanoKvmAutomationPortAutostartReceipt(
            fileName,
            bytes,
            NanoKvmAutomationPortAutostartWriteKind.CREATE,
        )
    }

    override suspend fun updateAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
        content: NanoKvmAutomationPortAutostartWrite,
    ): NanoKvmAutomationPortAutostartReceipt {
        catalog.requireExactMember(script)
        autostartUpdateCalls++
        val bytes = content.byteCount
        content.close()
        return NanoKvmAutomationPortAutostartReceipt(
            script.displayName,
            bytes,
            NanoKvmAutomationPortAutostartWriteKind.UPDATE,
        )
    }

    override suspend fun deleteAutostartScript(
        catalog: NanoKvmAutomationPortAutostartCatalog,
        script: NanoKvmAutomationPortAutostartScript,
    ) {
        catalog.requireExactMember(script)
        autostartDeleteCalls++
        autostartNames.remove(script.displayName)
    }

    private data class AutostartToken(val name: String)
}
