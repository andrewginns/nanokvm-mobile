package org.nanokvm.video

import livekit.org.webrtc.Logging
import org.junit.Assert.assertSame
import org.junit.Test

class WebRtcLoggingPolicyTest {
    @Test
    fun `native WebRTC logging is disabled rather than forwarding sensitive failures`() {
        assertSame(Logging.Severity.LS_NONE, WebRtcLoggingPolicy.minimumSeverity)

        WebRtcLoggingPolicy.sink.onLogMessage(
            "discarded diagnostic",
            Logging.Severity.LS_ERROR,
            "WebRTC",
        )
    }
}
