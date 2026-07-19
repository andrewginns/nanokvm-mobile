package org.nanokvm.mobile.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.AuthenticationExpiredException

@OptIn(ExperimentalCoroutinesApi::class)
class NanoKvmOfflineUpdateGatewayTest {
    @Test
    fun `source payload and protocol opener are each consumed exactly once and redacted`() {
        val opens = AtomicInteger()
        val source = source(
            fileName = "nanokvm_9.8.7.tar.gz",
            content = "private archive bytes".encodeToByteArray(),
            opens = opens,
        )

        val payload = source.consume()
        assertThrows(IllegalStateException::class.java) { source.consume() }
        val protocolSource = payload.consumeForProtocol()
        assertThrows(IllegalStateException::class.java) { payload.consumeForProtocol() }
        val bytes = protocolSource.openOnce().use { it.readBytes() }
        assertThrows(IOException::class.java) { protocolSource.openOnce() }

        assertEquals("private archive bytes", bytes.decodeToString())
        assertEquals(1, opens.get())
        listOf(source, payload, protocolSource).forEach { value ->
            assertFalse(value.toString().contains("nanokvm_9.8.7.tar.gz"))
            assertFalse(value.toString().contains("private archive"))
        }
    }

    @Test
    fun `invalid names and sizes fail before any source is opened`() {
        val opens = AtomicInteger()
        val invalidNames = listOf(
            "nanokvm_1.2.tar.gz",
            "../nanokvm_1.2.3.tar.gz",
            "NanoKVM_1.2.3.tar.gz",
            "nanokvm_1234567890.2.3.tar.gz",
            "nanokvm_1.2.3.zip",
        )
        invalidNames.forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                source(name, byteArrayOf(1), opens)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NanoKvmOfflineUpdateSource.create(
                "nanokvm_1.2.3.tar.gz",
                0,
                NanoKvmOfflineUpdateStreamOpener {
                    opens.incrementAndGet()
                    ByteArrayInputStream(byteArrayOf(1))
                },
            )
        }
        assertEquals(0, opens.get())
    }

    @Test
    fun `review binds exact destination version size interruption and object identity`() = runTest {
        val port = FakeOfflineUpdatePort()
        val binding = binding()
        val gateway = gateway(port, binding, backgroundScope) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        val first = source("nanokvm_2.5.0.tar.gz", byteArrayOf(1, 2, 3))

        assertTrue(gateway.select(first))
        val firstReview = requireNotNull(gateway.state.value.review)
        assertEquals("192.0.2.44", firstReview.destinationAuthority)
        assertEquals("2.4.3", firstReview.installedVersion)
        assertEquals("2.5.0", firstReview.packageVersion)
        assertEquals(3L, firstReview.packageSizeBytes)
        assertEquals("nanokvm_2.5.0.tar.gz", firstReview.expectedFileName)
        assertTrue(firstReview.interruptsRemoteControl)
        assertTrue(firstReview.restartsNanoKvmServices)
        assertFalse(firstReview.automaticRetryAllowed)
        assertFalse(firstReview.toString().contains("192.0.2.44"))
        assertFalse(gateway.state.value.toString().contains("nanokvm_2.5.0.tar.gz"))

        assertTrue(gateway.select(source("nanokvm_2.5.1.tar.gz", byteArrayOf(4))))
        assertFalse(gateway.confirmAndStart(firstReview))
        runCurrent()

        assertEquals(0, port.calls)
        assertEquals(NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE, gateway.state.value.phase)
        assertEquals(NanoKvmOfflineUpdateError.STALE_REVIEW, gateway.state.value.error)
        assertNull(gateway.state.value.review)
    }

    @Test
    fun `acknowledged upload reports bounded progress and cannot replay selection`() = runTest {
        val port = FakeOfflineUpdatePort()
        val binding = binding()
        val opens = AtomicInteger()
        val gateway = gateway(port, binding, backgroundScope) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(
            source(
                "nanokvm_2.5.2.tar.gz",
                "archive".encodeToByteArray(),
                opens,
            ),
        )
        val review = requireNotNull(gateway.state.value.review)

        assertTrue(gateway.confirmAndStart(review))
        runCurrent()

        val state = gateway.state.value
        assertEquals(NanoKvmOfflineUpdatePhase.ACKNOWLEDGED_RESTARTING, state.phase)
        assertEquals(7L, state.bytesTransferred)
        assertEquals(7L, state.totalBytes)
        assertEquals(NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION, state.guidance)
        assertEquals(1, port.calls)
        assertEquals(1, opens.get())
        assertFalse(gateway.confirmAndStart(review))
        runCurrent()
        assertEquals(1, port.calls)
    }

    @Test
    fun `ambiguous failure consumes selection and exposes no retry action or sensitive cause`() =
        runTest {
            val port = FakeOfflineUpdatePort().apply {
                failure = IOException("response lost for /private/document/path")
            }
            val binding = binding()
            val gateway = gateway(port, binding, backgroundScope) { binding }
            gateway.setForeground(true)
            gateway.setSurfaceVisible(true)
            gateway.select(source("nanokvm_2.5.3.tar.gz", byteArrayOf(1)))
            val review = requireNotNull(gateway.state.value.review)

            assertTrue(gateway.confirmAndStart(review))
            runCurrent()

            val state = gateway.state.value
            assertEquals(NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN, state.phase)
            assertEquals(NanoKvmOfflineUpdateError.CONNECTION, state.error)
            assertEquals(NanoKvmOfflineUpdateGuidance.RECONNECT_AND_VERIFY_VERSION, state.guidance)
            assertFalse(state.toString().contains("/private/document/path"))
            assertFalse(gateway.confirmAndStart(review))
            runCurrent()
            assertEquals(1, port.calls)
        }

    @Test
    fun `hide and background clear progress but retain dispatched outcome uncertainty`() = runTest {
        val port = FakeOfflineUpdatePort().apply { block = true }
        val binding = binding()
        val gateway = gateway(port, binding, backgroundScope) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(source("nanokvm_2.5.4.tar.gz", byteArrayOf(1, 2)))
        val review = requireNotNull(gateway.state.value.review)
        gateway.confirmAndStart(review)
        runCurrent()
        assertTrue(port.started.isCompleted)

        gateway.setSurfaceVisible(false)
        runCurrent()

        assertEquals(NanoKvmOfflineUpdatePhase.HIDDEN, gateway.state.value.phase)
        assertEquals(0, gateway.state.value.bytesTransferred)
        assertEquals(0, gateway.state.value.totalBytes)
        assertNull(gateway.state.value.review)
        assertTrue(port.cancelled.isCompleted)

        gateway.setSurfaceVisible(true)
        assertEquals(NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN, gateway.state.value.phase)
        assertEquals(
            NanoKvmOfflineUpdateError.CANCELLED_AFTER_DISPATCH,
            gateway.state.value.error,
        )
        assertFalse(gateway.select(source("nanokvm_2.5.5.tar.gz", byteArrayOf(9))))
        gateway.setForeground(false)
        assertEquals(NanoKvmOfflineUpdatePhase.INACTIVE, gateway.state.value.phase)
        gateway.setForeground(true)
        assertEquals(NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN, gateway.state.value.phase)
        assertEquals(1, port.calls)
    }

    @Test
    fun `generation change cancels dispatched upload and rejects stale completion`() = runTest {
        val port = FakeOfflineUpdatePort().apply { block = true }
        val captured = binding(generation = 4)
        var current: NanoKvmSessionBinding? = captured
        val gateway = gateway(port, captured, backgroundScope) { current }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(source("nanokvm_2.5.5.tar.gz", byteArrayOf(1)))
        gateway.confirmAndStart(requireNotNull(gateway.state.value.review))
        runCurrent()
        assertTrue(port.started.isCompleted)

        current = binding(generation = 5)
        advanceTimeBy(101)
        runCurrent()

        assertEquals(NanoKvmOfflineUpdatePhase.SESSION_CHANGED, gateway.state.value.phase)
        assertEquals(NanoKvmOfflineUpdateError.SESSION_CHANGED, gateway.state.value.error)
        assertNull(gateway.state.value.review)
        assertTrue(port.cancelled.isCompleted)
        assertEquals(1, port.calls)
    }

    @Test
    fun `unsupported gateway rejects and clears package without port calls`() = runTest {
        val port = FakeOfflineUpdatePort()
        val binding = binding()
        val source = source("nanokvm_2.5.6.tar.gz", byteArrayOf(1))
        val gateway = gateway(port, binding, backgroundScope, supported = false) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)

        assertFalse(gateway.select(source))

        assertEquals(NanoKvmOfflineUpdatePhase.UNSUPPORTED, gateway.state.value.phase)
        assertEquals(0, port.calls)
        assertThrows(IllegalStateException::class.java) { source.consume() }
    }

    @Test
    fun `document rejection clears prior source without exposing provider details`() = runTest {
        val port = FakeOfflineUpdatePort()
        val binding = binding()
        val selected = source("nanokvm_2.5.6.tar.gz", byteArrayOf(1))
        val gateway = gateway(port, binding, backgroundScope) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        assertTrue(gateway.select(selected))

        gateway.rejectDocumentSelection()

        assertEquals(NanoKvmOfflineUpdatePhase.DEFINITE_FAILURE, gateway.state.value.phase)
        assertEquals(NanoKvmOfflineUpdateError.INVALID_SELECTION, gateway.state.value.error)
        assertNull(gateway.state.value.review)
        assertThrows(IllegalStateException::class.java) { selected.consume() }
        assertEquals(0, port.calls)
    }

    @Test
    fun `authenticated upload 401 notifies central expiry boundary exactly once`() = runTest {
        val port = FakeOfflineUpdatePort().apply { failure = AuthenticationExpiredException() }
        val binding = binding()
        var expirations = 0
        val gateway = gateway(
            port = port,
            binding = binding,
            scope = backgroundScope,
            onAuthenticationExpired = { expirations++ },
            currentBinding = { binding },
        )
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(source("nanokvm_2.5.6.tar.gz", byteArrayOf(1)))
        val review = requireNotNull(gateway.state.value.review)

        assertTrue(gateway.confirmAndStart(review))
        runCurrent()

        assertEquals(NanoKvmOfflineUpdatePhase.AUTHENTICATION_EXPIRED, gateway.state.value.phase)
        assertEquals(1, expirations)
        assertFalse(gateway.confirmAndStart(review))
        assertEquals(1, expirations)
        assertEquals(1, port.calls)
    }

    @Test
    fun `late upload 401 from replaced generation cannot notify expiry boundary`() = runTest {
        val captured = binding(generation = 12)
        var current: NanoKvmSessionBinding? = captured
        var expirations = 0
        val port = FakeOfflineUpdatePort().apply {
            failure = AuthenticationExpiredException()
            beforeFailure = { current = binding(generation = 13) }
        }
        val gateway = gateway(
            port = port,
            binding = captured,
            scope = backgroundScope,
            onAuthenticationExpired = { expirations++ },
            currentBinding = { current },
        )
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(source("nanokvm_2.5.6.tar.gz", byteArrayOf(1)))
        gateway.confirmAndStart(requireNotNull(gateway.state.value.review))
        runCurrent()

        assertEquals(0, expirations)
        assertEquals(1, port.calls)
    }

    @Test
    fun `invalid progress cannot turn an acknowledged mutation into trusted success`() = runTest {
        val port = FakeOfflineUpdatePort().apply { invalidProgress = true }
        val binding = binding()
        val gateway = gateway(port, binding, backgroundScope) { binding }
        gateway.setForeground(true)
        gateway.setSurfaceVisible(true)
        gateway.select(source("nanokvm_2.5.7.tar.gz", byteArrayOf(1, 2, 3)))
        gateway.confirmAndStart(requireNotNull(gateway.state.value.review))
        runCurrent()

        assertEquals(NanoKvmOfflineUpdatePhase.OUTCOME_UNKNOWN, gateway.state.value.phase)
        assertEquals(NanoKvmOfflineUpdateError.INVALID_RESPONSE, gateway.state.value.error)
        assertEquals(1, port.calls)
    }

    private fun gateway(
        port: NanoKvmOfflineUpdatePort,
        binding: NanoKvmSessionBinding,
        scope: kotlinx.coroutines.CoroutineScope,
        supported: Boolean = true,
        onAuthenticationExpired: () -> Unit = {},
        currentBinding: () -> NanoKvmSessionBinding?,
    ) = NanoKvmOfflineUpdateGateway(
        port = port,
        binding = binding,
        currentBinding = currentBinding,
        scope = scope,
        destinationAuthority = "192.0.2.44",
        installedVersion = "2.4.3",
        supported = supported,
        onAuthenticationExpired = onAuthenticationExpired,
    )

    private fun source(
        fileName: String,
        content: ByteArray,
        opens: AtomicInteger = AtomicInteger(),
    ): NanoKvmOfflineUpdateSource = NanoKvmOfflineUpdateSource.create(
        fileName = fileName,
        contentLength = content.size.toLong(),
        opener = NanoKvmOfflineUpdateStreamOpener {
            opens.incrementAndGet()
            ByteArrayInputStream(content)
        },
    )

    private fun binding(generation: Long = 3) = NanoKvmSessionBinding(
        profileId = "profile",
        authority = "192.0.2.44",
        sessionGeneration = generation,
    )

    private class FakeOfflineUpdatePort : NanoKvmOfflineUpdatePort {
        var calls = 0
        var failure: Throwable? = null
        var beforeFailure: (() -> Unit)? = null
        var block = false
        var invalidProgress = false
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun upload(
            payload: NanoKvmOfflineUpdatePayload,
            onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
        ) {
            calls++
            val source = payload.consumeForProtocol()
            try {
                source.openOnce().use { input ->
                    onProgress(0, payload.contentLength)
                    started.complete(Unit)
                    if (block) {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    failure?.let {
                        beforeFailure?.invoke()
                        throw it
                    }
                    input.readBytes()
                    if (invalidProgress) {
                        onProgress(payload.contentLength + 1, payload.contentLength)
                    } else {
                        onProgress(payload.contentLength, payload.contentLength)
                    }
                }
            } finally {
                source.close()
                payload.close()
            }
        }
    }
}
