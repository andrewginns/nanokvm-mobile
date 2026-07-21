package org.nanokvm.mobile.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerCaptureControllerTest {
    @Test
    fun `every owner teardown reason releases an active host exactly once`() {
        val ownerReleaseReasons = listOf(
            PointerCaptureReleaseReason.User,
            PointerCaptureReleaseReason.Escape,
            PointerCaptureReleaseReason.Back,
            PointerCaptureReleaseReason.FocusLost,
            PointerCaptureReleaseReason.AppBackgrounded,
            PointerCaptureReleaseReason.KeyboardOpened,
            PointerCaptureReleaseReason.PointerModeChanged,
            PointerCaptureReleaseReason.SessionChanged,
            PointerCaptureReleaseReason.Disposed,
        )

        ownerReleaseReasons.forEach { reason ->
            val controller = PointerCaptureController()
            val host = RecordingHost()
            controller.attachHost(host)
            controller.request()
            controller.captureChanged(captured = true)

            controller.release(reason)
            controller.release(reason)
            controller.captureChanged(captured = false)
            controller.detachHost(host)

            assertEquals("$reason must release the host once", 1, host.releases)
            assertEquals("$reason must remain the recorded cause", reason, controller.lastReleaseReason)
            assertEquals("$reason must leave capture idle", PointerCaptureState.Idle, controller.state)
        }
    }

    @Test
    fun `request without an attached host is recoverably unavailable`() {
        val controller = PointerCaptureController()

        controller.request()

        assertEquals(
            PointerCaptureState.Unavailable(PointerCaptureUnavailableReason.HostUnavailable),
            controller.state,
        )
        assertFalse(controller.handleEscape())
    }

    @Test
    fun `accepted request becomes active only after Android confirms capture`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)

        controller.request()

        assertEquals(PointerCaptureState.Requesting, controller.state)
        assertEquals(1, host.requests)
        controller.captureChanged(captured = true)
        assertEquals(PointerCaptureState.Active, controller.state)
    }

    @Test
    fun `escape releases an active capture once and records local ownership`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)
        controller.request()
        controller.captureChanged(captured = true)

        assertTrue(controller.handleEscape())
        assertFalse(controller.handleEscape())

        assertEquals(PointerCaptureState.Idle, controller.state)
        assertEquals(PointerCaptureReleaseReason.Escape, controller.lastReleaseReason)
        assertEquals(1, host.releases)
    }

    @Test
    fun `system release does not call host release again`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)
        controller.request()
        controller.captureChanged(captured = true)

        controller.captureChanged(captured = false)

        assertEquals(PointerCaptureState.Idle, controller.state)
        assertEquals(PointerCaptureReleaseReason.System, controller.lastReleaseReason)
        assertEquals(0, host.releases)
    }

    @Test
    fun `late Android confirmation after timeout becomes active and releasable`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)
        controller.request()
        controller.requestRejected()

        assertTrue(controller.captureChanged(captured = true))
        assertEquals(PointerCaptureState.Active, controller.state)
        assertTrue(controller.handleEscape())
        assertEquals(1, host.releases)
    }

    @Test
    fun `late Android confirmation after owner cancellation is rejected`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)
        controller.request()
        controller.release(PointerCaptureReleaseReason.User)

        assertFalse(controller.captureChanged(captured = true))
        assertEquals(PointerCaptureState.Idle, controller.state)
        assertEquals(PointerCaptureReleaseReason.User, controller.lastReleaseReason)
        assertEquals(1, host.releases)
    }

    @Test
    fun `rejected request exposes a typed failure and can be retried`() {
        val controller = PointerCaptureController()
        val host = RecordingHost(failure = PointerCaptureUnavailableReason.FocusDenied)
        controller.attachHost(host)

        controller.request()
        assertEquals(
            PointerCaptureState.Unavailable(PointerCaptureUnavailableReason.FocusDenied),
            controller.state,
        )

        host.failure = null
        controller.request()
        assertEquals(PointerCaptureState.Requesting, controller.state)
        assertEquals(2, host.requests)
    }

    @Test
    fun `disposing the owning host releases capture and leaves no reusable binding`() {
        val controller = PointerCaptureController()
        val host = RecordingHost()
        controller.attachHost(host)
        controller.request()
        controller.captureChanged(captured = true)

        controller.detachHost(host)

        assertEquals(PointerCaptureState.Idle, controller.state)
        assertEquals(PointerCaptureReleaseReason.Disposed, controller.lastReleaseReason)
        assertEquals(1, host.releases)
        controller.request()
        assertEquals(
            PointerCaptureState.Unavailable(PointerCaptureUnavailableReason.HostUnavailable),
            controller.state,
        )
    }

    private class RecordingHost(
        var failure: PointerCaptureUnavailableReason? = null,
    ) : PointerCaptureHostBinding {
        var requests = 0
        var releases = 0

        override fun requestCapture(): PointerCaptureUnavailableReason? {
            requests += 1
            return failure
        }

        override fun releaseCapture() {
            releases += 1
        }
    }
}
