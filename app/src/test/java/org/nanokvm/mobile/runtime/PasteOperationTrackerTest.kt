package org.nanokvm.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteOperationTrackerTest {
    @Test
    fun `cancelling operation retains ownership and rejects overlap until finish`() {
        val tracker = PasteOperationTracker()
        val first = checkNotNull(tracker.start(totalKeystrokes = 8))

        tracker.progress(first.token, sentKeystrokes = 3, totalKeystrokes = 8)
        val cancelling = checkNotNull(tracker.cancel(first.token, userInitiated = true))
        val finalPair = checkNotNull(tracker.progress(first.token, sentKeystrokes = 4, totalKeystrokes = 8))

        assertEquals(RemotePastePhase.Cancelling, cancelling.phase)
        assertEquals(RemotePastePhase.Cancelling, finalPair.phase)
        assertEquals(4, finalPair.sentKeystrokes)
        assertTrue(cancelling.userInitiatedCancellation)
        assertNull(tracker.start(totalKeystrokes = 2))
        assertEquals(4, checkNotNull(tracker.finish(first.token)).sentKeystrokes)
        assertEquals(2, checkNotNull(tracker.start(totalKeystrokes = 2)).token)
    }

    @Test
    fun `stale progress and finish cannot mutate a replacement operation`() {
        val tracker = PasteOperationTracker()
        val old = checkNotNull(tracker.start(3))
        tracker.finish(old.token)
        val replacement = checkNotNull(tracker.start(4))

        assertNull(tracker.progress(old.token, 2, 3))
        assertNull(tracker.finish(old.token))
        val current = checkNotNull(tracker.snapshot(replacement.token))
        assertEquals(0, current.sentKeystrokes)
        assertEquals(4, current.totalKeystrokes)
        assertFalse(current.userInitiatedCancellation)
    }
}
