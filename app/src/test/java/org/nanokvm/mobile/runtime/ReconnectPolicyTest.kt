package org.nanokvm.mobile.runtime

import java.io.IOException
import java.security.cert.CertificateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.nanokvm.protocol.AuthenticationExpiredException

class ReconnectPolicyTest {
    @Test
    fun `bounded policy exhausts exact configured attempts and delays`() = runBlocking {
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        val policy = ReconnectPolicy(
            baseDelaysMillis = listOf(1L, 2L, 4L),
            jitterFraction = 0.0,
        )

        val result = policy.execute(
            immediateFirstAttempt = false,
            wait = { waits += it },
            attempt = { number ->
                attempts += number
                ReconnectAttemptResult.Failed(ReconnectFailure(IOException("offline")))
            },
        )

        assertEquals(listOf(1L, 2L, 4L), waits)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(3, (result as ReconnectRunResult.Exhausted).attempts)
    }

    @Test
    fun `successful retry stops the sequence`() = runBlocking {
        var attempts = 0
        val result = ReconnectPolicy(
            baseDelaysMillis = listOf(1L, 2L, 4L),
            jitterFraction = 0.0,
        ).execute(
            immediateFirstAttempt = true,
            wait = {},
            attempt = {
                attempts++
                if (attempts == 2) ReconnectAttemptResult.Connected else {
                    ReconnectAttemptResult.Failed(ReconnectFailure(IOException("transient")))
                }
            },
        )

        assertEquals(ReconnectRunResult.Connected, result)
        assertEquals(2, attempts)
    }

    @Test
    fun `authentication and certificate failures are terminal and never retried`() = runBlocking {
        val failures = listOf(
            ReconnectFailure(AuthenticationExpiredException()),
            ReconnectFailure(IOException("upgrade rejected"), httpStatus = 401),
            ReconnectFailure(CertificateException("pin mismatch")),
        )

        failures.forEach { failure ->
            var attempts = 0
            val result = ReconnectPolicy(
                baseDelaysMillis = listOf(0L, 0L, 0L),
                jitterFraction = 0.0,
            ).execute(
                immediateFirstAttempt = true,
                wait = {},
                attempt = {
                    attempts++
                    ReconnectAttemptResult.Failed(failure)
                },
            )

            assertEquals(1, attempts)
            assertEquals(failure, (result as ReconnectRunResult.Terminal).failure)
        }
    }

    @Test
    fun `server errors retry while other HTTP handshake failures are terminal`() {
        assertEquals(
            ReconnectFailureDisposition.RETRY,
            ReconnectFailure(IOException(), httpStatus = 503).disposition(),
        )
        assertEquals(
            ReconnectFailureDisposition.TERMINAL,
            ReconnectFailure(IOException(), httpStatus = 404).disposition(),
        )
    }

    @Test
    fun `cancellation during backoff propagates without an attempt`() {
        var attempts = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                ReconnectPolicy(
                    baseDelaysMillis = listOf(1L),
                    jitterFraction = 0.0,
                ).execute(
                    immediateFirstAttempt = false,
                    wait = { throw CancellationException("backgrounded") },
                    attempt = {
                        attempts++
                        ReconnectAttemptResult.Connected
                    },
                )
            }
        }
        assertEquals(0, attempts)
    }

    @Test
    fun `jitter is deterministic at injected midpoint`() = runBlocking {
        val waits = mutableListOf<Long>()
        ReconnectPolicy(
            baseDelaysMillis = listOf(1_000L),
            jitterFraction = 0.20,
            jitterSample = { 0.5 },
        ).execute(
            immediateFirstAttempt = false,
            wait = { waits += it },
            attempt = { ReconnectAttemptResult.Failed(ReconnectFailure(IOException())) },
        )

        assertEquals(listOf(1_000L), waits)
    }
}
