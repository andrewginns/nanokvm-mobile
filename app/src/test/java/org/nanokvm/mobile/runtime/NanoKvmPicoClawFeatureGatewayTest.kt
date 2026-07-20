package org.nanokvm.mobile.runtime

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.NanoKvmPicoClawAgentProfile
import org.nanokvm.protocol.NanoKvmPicoClawApiBase
import org.nanokvm.protocol.NanoKvmPicoClawAssistantMessageKind
import org.nanokvm.protocol.NanoKvmPicoClawGatewayEvent
import org.nanokvm.protocol.NanoKvmPicoClawGatewayScope
import org.nanokvm.protocol.NanoKvmPicoClawGatewayState
import org.nanokvm.protocol.NanoKvmPicoClawHistoryRole
import org.nanokvm.protocol.NanoKvmPicoClawInboundMessage
import org.nanokvm.protocol.NanoKvmPicoClawManualHidLockState
import org.nanokvm.protocol.NanoKvmPicoClawMessageOptions
import org.nanokvm.protocol.NanoKvmPicoClawMessageReceipt
import org.nanokvm.protocol.NanoKvmPicoClawRuntimePhase
import org.nanokvm.protocol.NanoKvmPicoClawRuntimeSessionId
import org.nanokvm.protocol.NanoKvmPicoClawRuntimeStatus
import org.nanokvm.protocol.NanoKvmPicoClawSessionRelease

@OptIn(ExperimentalCoroutinesApi::class)
class NanoKvmPicoClawFeatureGatewayTest {
    @Test
    fun `construction and guarded APIs make zero calls before explicit feature entry`() = runTest {
        val port = FakePicoClawPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }

        assertEquals(0, port.totalCalls)
        assertNull(gateway.recordBroadControlConsentAfterDisclosure())
        assertTrue(gateway.refreshRuntime() is NanoKvmPicoClawReadResult.Failure)
        assertTrue(gateway.refreshHistories() is NanoKvmPicoClawReadResult.Failure)

        assertEquals(0, port.totalCalls)
        assertEquals(0, port.statusCalls)

        assertTrue(gateway.enterFeature() is NanoKvmPicoClawReadResult.Success)
        assertEquals(1, port.statusCalls)
    }

    @Test
    fun `stale exact binding blocks entry and every later dispatch`() = runTest {
        val port = FakePicoClawPort()
        val captured = binding(generation = 7)
        var current: NanoKvmSessionBinding? = binding(generation = 8)
        val gateway = gateway(port, captured, backgroundScope) { current }

        val entry = gateway.enterFeature()
        assertReadFailure(entry, NanoKvmPicoClawError.Kind.SESSION_CHANGED)
        assertEquals(0, port.totalCalls)

        current = captured
        assertTrue(gateway.enterFeature() is NanoKvmPicoClawReadResult.Success)
        current = captured.copy(authority = "192.0.2.9")

        val start = gateway.startRuntime()
        assertMutationRejected(start, NanoKvmPicoClawError.Kind.SESSION_CHANGED)
        assertEquals(0, port.startCalls)
    }

    @Test
    fun `owned provider key clears on success failure and local rejection without leaking`() =
        runTest {
            val port = FakePicoClawPort()
            val current = binding()
            val gateway = gateway(port, current, backgroundScope) { current }
            gateway.enterFeature()
            val key = "provider-secret-value".toCharArray()
            val update = NanoKvmPicoClawModelUpdate.takeOwnership(
                model = "small-model",
                apiBase = NanoKvmPicoClawApiBase.parse("https://models.example.test/v1"),
                apiKey = key,
            )

            val result = gateway.updateModel(update)

            assertTrue(result is NanoKvmPicoClawMutationResult.Applied)
            assertEquals("provider-secret-value", port.receivedProviderKey)
            assertTrue(key.all { it == '\u0000' })
            assertFalse(update.toString().contains("provider-secret-value"))
            assertFalse(result.toString().contains("provider-secret-value"))

            val rejectedKey = "must-also-clear".toCharArray()
            val rejected = NanoKvmPicoClawModelUpdate.takeOwnership(
                "other-model",
                NanoKvmPicoClawApiBase.parse("https://models.example.test/v2"),
                rejectedKey,
            )
            gateway.close()
            assertMutationRejected(
                gateway.updateModel(rejected),
                NanoKvmPicoClawError.Kind.SESSION_CHANGED,
            )
            assertTrue(rejectedKey.all { it == '\u0000' })

            val invalidKey = "invalid\nsecret".toCharArray()
            runCatching {
                NanoKvmPicoClawModelUpdate.takeOwnership(
                    "model",
                    NanoKvmPicoClawApiBase.parse("https://models.example.test"),
                    invalidKey,
                )
            }
            assertTrue(invalidKey.all { it == '\u0000' })
        }

    @Test
    fun `ambiguous runtime mutation reads authoritative status and never replays`() = runTest {
        val port = FakePicoClawPort().apply { startFailsAfterApplying = true }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()

        val result = gateway.startRuntime()

        assertTrue(result is NanoKvmPicoClawMutationResult.Reconciled)
        result as NanoKvmPicoClawMutationResult.Reconciled
        assertEquals(NanoKvmPicoClawRuntimeObservation.DESIRED_STATE, result.observation)
        assertEquals(NanoKvmPicoClawError.Kind.CONNECTION, result.dispatchError.kind)
        assertEquals(1, port.startCalls)
        assertEquals(3, port.statusCalls)
        assertFalse(result.toString().contains("response lost for private host"))
    }

    @Test
    fun `history handles stay opaque bounded exact and deletion is reconciled without replay`() =
        runTest {
            val port = FakePicoClawPort().apply {
                deleteFailsAfterApplying = true
                historyPresenceAfterFailure = NanoKvmPicoClawHistoryPresence.ABSENT
            }
            val current = binding()
            val gateway = gateway(port, current, backgroundScope) { current }
            gateway.enterFeature()
            val catalog = historySuccess(gateway.refreshHistories())
            val item = catalog.items.single()
            val detail = gateway.historyDetail(catalog, item) as NanoKvmPicoClawReadResult.Success
            assertEquals(32 * 1_024, detail.state.messages.single().content.encodeToByteArray().size)
            assertEquals(8 * 1_024, requireNotNull(detail.state.summary).encodeToByteArray().size)
            assertFalse(detail.state.toString().contains("private question"))

            assertEquals(256, item.title.encodeToByteArray().size)
            assertEquals(512, item.preview.encodeToByteArray().size)
            listOf(catalog.toString(), item.toString(), item.portSession.toString()).forEach {
                assertFalse(it.contains("sk_v1_private_identity"))
                assertFalse(it.contains("private history"))
            }

            val consent = requireNotNull(gateway.recordHistoryDeletionConsent(catalog, item))
            val result = gateway.deleteHistory(catalog, item, consent)

            assertTrue(result is NanoKvmPicoClawHistoryDeleteResult.Reconciled)
            result as NanoKvmPicoClawHistoryDeleteResult.Reconciled
            assertEquals(NanoKvmPicoClawHistoryDeleteObservation.ABSENT, result.observation)
            assertEquals(1, port.deleteCalls)
            assertEquals(1, port.presenceCalls)
            assertNull(gateway.recordHistoryDeletionConsent(catalog, item))

            val foreignPort = FakePicoClawPort()
            val foreignGateway = gateway(foreignPort, current, backgroundScope) { current }
            foreignGateway.enterFeature()
            val foreignCatalog = historySuccess(foreignGateway.refreshHistories())
            assertNull(gateway.recordHistoryDeletionConsent(foreignCatalog, foreignCatalog.items.single()))
        }

    @Test
    fun `history port catalog requires its exact typed member`() {
        val created = Instant.parse("2026-07-18T08:00:00Z")
        val updated = Instant.parse("2026-07-18T09:00:00Z")
        val member = NanoKvmPicoClawPortHistorySession(
            "title",
            "preview",
            1,
            created,
            updated,
        )
        val lookalike = NanoKvmPicoClawPortHistorySession(
            "title",
            "preview",
            1,
            created,
            updated,
        )
        val catalog = NanoKvmPicoClawPortHistoryCatalog(listOf(member))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            catalog.requireExactMember(lookalike)
        }

        assertEquals(
            "History session must be an exact member of the supplied catalog",
            failure.message,
        )
    }

    @Test
    fun `history deletion reconciliation preserves cancellation`() = runTest {
        val port = FakePicoClawPort().apply {
            deleteFailsAfterApplying = true
            historyPresenceFailure = CancellationException("history read cancelled")
        }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()
        val catalog = historySuccess(gateway.refreshHistories())
        val item = catalog.items.single()
        val consent = requireNotNull(gateway.recordHistoryDeletionConsent(catalog, item))

        val failure = runCatching {
            gateway.deleteHistory(catalog, item, consent)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, port.deleteCalls)
        assertEquals(1, port.presenceCalls)
    }

    @Test
    fun `cancel keeps manual HID locked and only close release restores it`() = runTest {
        val port = FakePicoClawPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()
        val consent = requireNotNull(gateway.recordBroadControlConsentAfterDisclosure())

        assertTrue(gateway.chat.open(consent) is NanoKvmPicoClawChatActionResult.Dispatched)
        runCurrent()
        assertEquals(NanoKvmPicoClawManualInputState.Held, gateway.chat.manualInput.value)
        assertTrue(gateway.chat.manualInput.value.manualInputBlockedOrUncertain)

        assertTrue(gateway.chat.cancelRun() is NanoKvmPicoClawChatActionResult.Dispatched)
        assertEquals(1, port.chat.cancelCalls)
        assertEquals(NanoKvmPicoClawManualInputState.Held, gateway.chat.manualInput.value)
        assertEquals(0, port.chat.releaseCalls)

        val release = gateway.chat.closeAndRelease()
        assertEquals(NanoKvmPicoClawChatReleaseResult.Released, release)
        assertEquals(NanoKvmPicoClawManualInputState.Released, gateway.chat.manualInput.value)
        assertEquals(1, port.chat.releaseCalls)
        assertEquals(1, port.newChatCalls)
        assertTrue(gateway.chat.events.replayCache.isEmpty())
    }

    @Test
    fun `chat messages are dispatched once never queued and gateway cannot reconnect`() = runTest {
        val port = FakePicoClawPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()

        val consent = requireNotNull(gateway.recordBroadControlConsentAfterDisclosure())
        gateway.chat.open(consent)
        runCurrent()

        assertTrue(gateway.chat.sendMessage("one private request") is
            NanoKvmPicoClawChatActionResult.MessageDispatched)
        port.chat.acceptMessages = false
        assertChatRejected(
            gateway.chat.sendMessage("must not queue"),
            NanoKvmPicoClawError.Kind.NOT_CONNECTED,
        )
        assertEquals(2, port.chat.sendCalls)

        val secondConsent = requireNotNull(gateway.recordBroadControlConsentAfterDisclosure())
        assertChatRejected(
            gateway.chat.open(secondConsent),
            NanoKvmPicoClawError.Kind.ALREADY_ACTIVE,
        )
        assertEquals(1, port.newChatCalls)
        assertEquals(1, port.chat.connectCalls)
    }

    @Test
    fun `dispatched chat message reuses exact normalized bounded display payload`() = runTest {
        val port = FakePicoClawPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()
        gateway.chat.open(requireNotNull(gateway.recordBroadControlConsentAfterDisclosure()))
        runCurrent()
        val remotePayload = "one private request"
        val multibyteWhitespace = "\u2003".repeat(12_000)

        val result = gateway.chat.sendMessage(
            multibyteWhitespace + remotePayload + multibyteWhitespace,
        )

        assertTrue(result is NanoKvmPicoClawChatActionResult.MessageDispatched)
        result as NanoKvmPicoClawChatActionResult.MessageDispatched
        assertEquals(remotePayload, port.chat.sentMessages.single())
        assertEquals(
            port.chat.sentMessages.single(),
            (result.message.content as PicoClawMessageContent.ApplianceText).value,
        )
    }

    @Test
    fun `chat display events are bounded replay free and redact retained content`() = runTest {
        val port = FakePicoClawPort()
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()
        gateway.chat.open(requireNotNull(gateway.recordBroadControlConsentAfterDisclosure()))
        runCurrent()
        val received = async(start = CoroutineStart.UNDISPATCHED) { gateway.chat.events.first() }

        port.chat.emit(
            NanoKvmPicoClawInboundMessage.AssistantMessage(
                kind = NanoKvmPicoClawAssistantMessageKind.CREATED,
                id = "private-message-id",
                text = "s".repeat(40 * 1_024) + "private chat tail",
            ),
        )
        runCurrent()

        val event = received.await() as NanoKvmPicoClawChatEvent.AssistantMessage
        assertEquals(32 * 1_024, event.text.encodeToByteArray().size)
        assertFalse(event.toString().contains("private chat tail"))
        assertFalse(event.toString().contains("private-message-id"))
        assertTrue(gateway.chat.events.replayCache.isEmpty())
    }

    @Test
    fun `ambiguous close never retries and exposes release uncertainty with redacted state`() =
        runTest {
            val port = FakePicoClawPort().apply {
                chat.releaseFailure = IOException("private release token")
                statusFailure = IOException("private status token")
            }
            val current = binding()
            val gateway = gateway(port, current, backgroundScope) { current }
            gateway.enterFeature()
            // The entry status succeeded; make only reconciliation status fail.
            port.statusFailureEnabled = true
            gateway.chat.open(requireNotNull(gateway.recordBroadControlConsentAfterDisclosure()))
            runCurrent()

            val result = gateway.chat.closeAndRelease()

            assertTrue(result is NanoKvmPicoClawChatReleaseResult.Indeterminate)
            assertEquals(
                NanoKvmPicoClawManualInputState.ReleaseUncertain,
                gateway.chat.manualInput.value,
            )
            assertEquals(1, port.chat.releaseCalls)
            assertFalse(result.toString().contains("private release token"))
            assertFalse(gateway.chat.state.value.toString().contains("private status token"))
        }

    @Test
    fun `release reconciliation preserves cancellation and lock uncertainty`() = runTest {
        val port = FakePicoClawPort().apply {
            chat.releaseFailure = IOException("release response lost")
            statusFailure = CancellationException("status read cancelled")
        }
        val current = binding()
        val gateway = gateway(port, current, backgroundScope) { current }
        gateway.enterFeature()
        port.statusFailureEnabled = true
        gateway.chat.open(requireNotNull(gateway.recordBroadControlConsentAfterDisclosure()))
        runCurrent()

        val failure = runCatching { gateway.chat.closeAndRelease() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(
            NanoKvmPicoClawManualInputState.ReleaseUncertain,
            gateway.chat.manualInput.value,
        )
        assertEquals(1, port.chat.releaseCalls)
        assertEquals(2, port.statusCalls)
    }

    @Test
    fun `destructive and broad control approvals are one use and disclosures are explicit`() =
        runTest {
            val port = FakePicoClawPort()
            val current = binding()
            val gateway = gateway(port, current, backgroundScope) { current }
            gateway.enterFeature()
            val uninstall = requireNotNull(gateway.recordUninstallConsentAfterWarning())

            val result = gateway.uninstallRuntime(uninstall)
            assertTrue(result is NanoKvmPicoClawMutationResult.Applied)
            assertEquals(1, port.uninstallCalls)
            assertMutationRejected(
                gateway.uninstallRuntime(uninstall),
                NanoKvmPicoClawError.Kind.APPROVAL_REQUIRED,
            )
            assertEquals(1, port.uninstallCalls)
            assertTrue(NanoKvmPicoClawPermissionDisclosure.EXPLANATION.contains("filesystem"))
            assertTrue(NanoKvmPicoClawPermissionDisclosure.EXPLANATION.contains("execute commands"))
            assertTrue(NanoKvmPicoClawPermissionDisclosure.UNINSTALL_EXPLANATION.contains("permanently"))
            assertFalse(uninstall.toString().contains("192.0.2.4"))
        }

    private fun gateway(
        port: NanoKvmPicoClawPort,
        binding: NanoKvmSessionBinding,
        scope: CoroutineScope,
        current: () -> NanoKvmSessionBinding?,
    ) = NanoKvmPicoClawFeatureGateway(port, binding, current, scope)

    private fun binding(generation: Long = 1L) = NanoKvmSessionBinding(
        profileId = "office-profile",
        authority = "192.0.2.4",
        sessionGeneration = generation,
    )

    private fun historySuccess(
        result: NanoKvmPicoClawReadResult<NanoKvmPicoClawHistoryCatalogSnapshot>,
    ) = (result as NanoKvmPicoClawReadResult.Success).state

    private fun assertReadFailure(
        result: NanoKvmPicoClawReadResult<*>,
        kind: NanoKvmPicoClawError.Kind,
    ) {
        assertTrue(result is NanoKvmPicoClawReadResult.Failure)
        assertEquals(kind, (result as NanoKvmPicoClawReadResult.Failure).error.kind)
    }

    private fun assertMutationRejected(
        result: NanoKvmPicoClawMutationResult<*>,
        kind: NanoKvmPicoClawError.Kind,
    ) {
        assertTrue(result is NanoKvmPicoClawMutationResult.Rejected)
        assertEquals(kind, (result as NanoKvmPicoClawMutationResult.Rejected).error.kind)
    }

    private fun assertChatRejected(
        result: NanoKvmPicoClawChatActionResult,
        kind: NanoKvmPicoClawError.Kind,
    ) {
        assertTrue(result is NanoKvmPicoClawChatActionResult.Rejected)
        assertEquals(kind, (result as NanoKvmPicoClawChatActionResult.Rejected).error.kind)
    }
}

private class FakePicoClawPort : NanoKvmPicoClawPort {
    var status = stoppedStatus()
    var statusCalls = 0
    var installCalls = 0
    var uninstallCalls = 0
    var startCalls = 0
    var stopCalls = 0
    var profileCalls = 0
    var modelCalls = 0
    var historyListCalls = 0
    var historyDetailCalls = 0
    var deleteCalls = 0
    var presenceCalls = 0
    var newChatCalls = 0
    var startFailsAfterApplying = false
    var deleteFailsAfterApplying = false
    var historyPresenceAfterFailure = NanoKvmPicoClawHistoryPresence.UNKNOWN
    var historyPresenceFailure: Throwable? = null
    var receivedProviderKey: String? = null
    var statusFailure: Throwable? = null
    var statusFailureEnabled = false
    val chat = FakePicoClawChatPort()
    private var historyPresent = true

    val totalCalls: Int
        get() = statusCalls + installCalls + uninstallCalls + startCalls + stopCalls +
            profileCalls + modelCalls + historyListCalls + historyDetailCalls + deleteCalls +
            presenceCalls + newChatCalls

    override suspend fun runtimeStatus(): NanoKvmPicoClawRuntimeStatus {
        statusCalls++
        if (statusFailureEnabled) throw requireNotNull(statusFailure)
        return status
    }

    override suspend fun installRuntime() {
        installCalls++
        status = status.copy(installed = true, phase = NanoKvmPicoClawRuntimePhase.Installed)
    }

    override suspend fun uninstallRuntime() {
        uninstallCalls++
        status = stoppedStatus().copy(installed = false, phase = NanoKvmPicoClawRuntimePhase.NotInstalled)
    }

    override suspend fun startRuntime() {
        startCalls++
        status = readyStatus()
        if (startFailsAfterApplying) throw IOException("response lost for private host")
    }

    override suspend fun stopRuntime() {
        stopCalls++
        status = stoppedStatus()
    }

    override suspend fun setAgentProfile(profile: NanoKvmPicoClawAgentProfile) {
        profileCalls++
        status = status.copy(agentProfile = profile)
    }

    override suspend fun updateModel(
        model: String,
        apiBase: NanoKvmPicoClawApiBase,
        apiKey: CharArray,
    ) {
        modelCalls++
        receivedProviderKey = apiKey.concatToString()
        status = status.copy(modelConfigured = true, modelName = model)
    }

    override suspend fun histories(limit: Int): NanoKvmPicoClawPortHistoryCatalog {
        historyListCalls++
        val sessions = if (historyPresent) {
            listOf(
                NanoKvmPicoClawPortHistorySession(
                    title = "t".repeat(300) + " private history",
                    preview = "p".repeat(700) + " private preview",
                    messageCount = 3,
                    createdAt = Instant.parse("2026-07-18T08:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T09:00:00Z"),
                ),
            )
        } else {
            emptyList()
        }
        return NanoKvmPicoClawPortHistoryCatalog(sessions)
    }

    override suspend fun history(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawPortHistoryDetail {
        catalog.requireExactMember(session)
        historyDetailCalls++
        return NanoKvmPicoClawPortHistoryDetail(
            session = session,
            messages = listOf(
                NanoKvmPicoClawPortHistoryMessage(
                    NanoKvmPicoClawHistoryRole.USER,
                    "q".repeat(40 * 1_024) + "private question",
                ),
            ),
            summary = "u".repeat(10 * 1_024) + "private summary",
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
        )
    }

    override suspend fun deleteHistory(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ) {
        catalog.requireExactMember(session)
        deleteCalls++
        historyPresent = false
        if (deleteFailsAfterApplying) throw IOException("lost delete for sk_v1_private_identity")
    }

    override suspend fun historyPresence(
        catalog: NanoKvmPicoClawPortHistoryCatalog,
        session: NanoKvmPicoClawPortHistorySession,
    ): NanoKvmPicoClawHistoryPresence {
        catalog.requireExactMember(session)
        presenceCalls++
        historyPresenceFailure?.let { throw it }
        return historyPresenceAfterFailure
    }

    override fun newChat(sessionGeneration: Long): NanoKvmPicoClawChatPort {
        newChatCalls++
        return chat
    }
}

private class FakePicoClawChatPort : NanoKvmPicoClawChatPort {
    private val session = NanoKvmPicoClawRuntimeSessionId.parse(
        "123e4567-e89b-42d3-a456-426614174000",
    )
    private val scope = NanoKvmPicoClawGatewayScope("192.0.2.4", 1, session)
    private val mutableState = MutableStateFlow<NanoKvmPicoClawGatewayState>(
        NanoKvmPicoClawGatewayState.New(scope),
    )
    private val mutableLock = MutableStateFlow<NanoKvmPicoClawManualHidLockState>(
        NanoKvmPicoClawManualHidLockState.Released(scope),
    )
    private val mutableEvents = MutableSharedFlow<NanoKvmPicoClawGatewayEvent>(
        extraBufferCapacity = 8,
    )

    override val state: StateFlow<NanoKvmPicoClawGatewayState> = mutableState
    override val manualHidLock: StateFlow<NanoKvmPicoClawManualHidLockState> = mutableLock
    override val events: SharedFlow<NanoKvmPicoClawGatewayEvent> = mutableEvents

    var connectCalls = 0
    var sendCalls = 0
    var cancelCalls = 0
    var releaseCalls = 0
    var closeCalls = 0
    var acceptMessages = true
    var releaseFailure: Throwable? = null
    val sentMessages = mutableListOf<String>()

    override fun connect(): Boolean {
        connectCalls++
        mutableState.value = NanoKvmPicoClawGatewayState.Open(scope)
        mutableLock.value = NanoKvmPicoClawManualHidLockState.Held(scope)
        return true
    }

    override fun sendMessage(
        content: String,
        options: NanoKvmPicoClawMessageOptions,
    ): NanoKvmPicoClawMessageReceipt? {
        sendCalls++
        sentMessages += content
        return if (acceptMessages) NanoKvmPicoClawMessageReceipt("opaque-receipt", session) else null
    }

    override fun cancelRun(): Boolean {
        cancelCalls++
        return true
    }

    override suspend fun closeAndRelease(): NanoKvmPicoClawSessionRelease {
        releaseCalls++
        releaseFailure?.let { throw it }
        mutableState.value = NanoKvmPicoClawGatewayState.Closing(scope)
        mutableLock.value = NanoKvmPicoClawManualHidLockState.Released(scope)
        return NanoKvmPicoClawSessionRelease(released = true, currentSession = null)
    }

    fun emit(message: NanoKvmPicoClawInboundMessage) {
        mutableEvents.tryEmit(NanoKvmPicoClawGatewayEvent.Message(scope, message))
    }

    override fun close() {
        closeCalls++
    }
}

private fun stoppedStatus() = NanoKvmPicoClawRuntimeStatus(
    ready = false,
    installed = true,
    installing = false,
    installProgress = null,
    installStage = null,
    installPath = "/private/runtime/path",
    agentProfile = NanoKvmPicoClawAgentProfile.DEFAULT,
    modelConfigured = false,
    modelName = null,
    phase = NanoKvmPicoClawRuntimePhase.Stopped,
    configError = null,
    lastError = null,
    checkedAt = Instant.parse("2026-07-18T09:00:00Z"),
    currentSession = null,
)

private fun readyStatus() = stoppedStatus().copy(
    ready = true,
    installed = true,
    modelConfigured = true,
    modelName = "small-model",
    phase = NanoKvmPicoClawRuntimePhase.Ready,
)
