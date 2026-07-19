package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.NanoKvmEndpoint
import org.nanokvm.protocol.NanoKvmWifiAccessPointAuthorization
import org.nanokvm.protocol.NanoKvmWifiCredentials

class NanoKvmWifiAccessPointOnboardingGatewayTest {
    @Test
    fun `one foreground generation verifies then connects and clears exact arrays`() = runTest {
        var currentGeneration: Long? = 7L
        val session = FakeAccessPointSession()
        val factory = FakeAccessPointSessionFactory(session)
        val gateway = NanoKvmWifiAccessPointOnboardingGateway(
            generation = 7L,
            currentGeneration = { currentGeneration },
            sessionFactory = factory,
        )
        val apPassword = charArrayOf('a', 'p', '-', 's', 'e', 'c', 'r', 'e', 't')
        val targetPassword = charArrayOf('w', 'i', 'f', 'i', '-', 's', 'e', 'c', 'r', 'e', 't')

        val result = gateway.connect(
            endpointInput = "http://10.10.10.1",
            apPassword = apPassword,
            targetSsid = "manual-network",
            targetPassword = targetPassword,
        )

        assertSame(NanoKvmWifiAccessPointOnboardingResult.Applied, result)
        assertEquals(1, session.verifyCalls)
        assertEquals(1, session.connectCalls)
        assertEquals(1, session.closeCalls)
        assertEquals("10.10.10.1", factory.endpoint?.baseUrl?.host)
        assertTrue(apPassword.all { it == '\u0000' })
        assertTrue(targetPassword.all { it == '\u0000' })
        assertTrue(result.toString().contains("Applied"))
    }

    @Test
    fun `generation change after verification prevents the network write and clears secrets`() =
        runTest {
            var currentGeneration: Long? = 3L
            val session = FakeAccessPointSession(
                afterVerify = { currentGeneration = null },
            )
            val gateway = NanoKvmWifiAccessPointOnboardingGateway(
                generation = 3L,
                currentGeneration = { currentGeneration },
                sessionFactory = FakeAccessPointSessionFactory(session),
            )
            val apPassword = charArrayOf('a', 'p')
            val targetPassword = charArrayOf('w', 'i', 'f', 'i')

            val result = gateway.connect(
                "http://10.10.10.1",
                apPassword,
                "manual-network",
                targetPassword,
            )

            assertTrue(result is NanoKvmWifiAccessPointOnboardingResult.Rejected)
            assertEquals(1, session.verifyCalls)
            assertEquals(0, session.connectCalls)
            assertTrue(apPassword.all { it == '\u0000' })
            assertTrue(targetPassword.all { it == '\u0000' })
        }

    @Test
    fun `lost write response is indeterminate and never replayed`() = runTest {
        val session = FakeAccessPointSession(failConnect = true)
        val gateway = NanoKvmWifiAccessPointOnboardingGateway(
            generation = 4L,
            currentGeneration = { 4L },
            sessionFactory = FakeAccessPointSessionFactory(session),
        )
        val apPassword = charArrayOf('a', 'p')
        val targetPassword = charArrayOf('w', 'i', 'f', 'i')

        val result = gateway.connect(
            "http://10.10.10.1",
            apPassword,
            "manual-network",
            targetPassword,
        )

        assertTrue(result is NanoKvmWifiAccessPointOnboardingResult.Indeterminate)
        assertEquals(1, session.connectCalls)
        assertTrue(apPassword.all { it == '\u0000' })
        assertTrue(targetPassword.all { it == '\u0000' })
    }
}

private class FakeAccessPointSessionFactory(
    private val session: FakeAccessPointSession,
) : NanoKvmWifiAccessPointOnboardingSessionFactory {
    var endpoint: NanoKvmEndpoint? = null

    override fun create(endpoint: NanoKvmEndpoint): NanoKvmWifiAccessPointOnboardingSession {
        this.endpoint = endpoint
        return session
    }
}

private class FakeAccessPointSession(
    private val afterVerify: () -> Unit = {},
    private val failConnect: Boolean = false,
) : NanoKvmWifiAccessPointOnboardingSession {
    var verifyCalls = 0
    var connectCalls = 0
    var closeCalls = 0

    override suspend fun verifyAccessPointPassword(
        password: CharArray,
    ): NanoKvmWifiAccessPointAuthorization {
        verifyCalls++
        afterVerify()
        val constructor = NanoKvmWifiAccessPointAuthorization::class.java.declaredConstructors
            .single { it.parameterTypes.size == 2 }
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(this, password) as NanoKvmWifiAccessPointAuthorization
    }

    override suspend fun connect(
        credentials: NanoKvmWifiCredentials,
        authorization: NanoKvmWifiAccessPointAuthorization,
    ) {
        connectCalls++
        if (failConnect) throw IOException("response lost")
    }

    override fun close() {
        closeCalls++
    }
}
