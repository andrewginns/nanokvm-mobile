package org.nanokvm.mobile.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.mobile.runtime.RemoteKey

class ModifierLatchTest {
    @Test
    fun `modifier cycles one shot locked and off`() {
        val oneShot = ModifierLatch().cycle(RemoteKey.Control)
        val locked = oneShot.cycle(RemoteKey.Control)
        val off = locked.cycle(RemoteKey.Control)

        assertEquals(ModifierMode.OneShot, oneShot.mode(RemoteKey.Control))
        assertEquals(ModifierMode.Locked, locked.mode(RemoteKey.Control))
        assertEquals(ModifierMode.Off, off.mode(RemoteKey.Control))
    }

    @Test
    fun `consuming one shot preserves locked modifiers`() {
        val state = ModifierLatch()
            .cycle(RemoteKey.Control)
            .cycle(RemoteKey.Alt)
            .cycle(RemoteKey.Alt)

        val consumed = state.consumeOneShot()

        assertFalse(RemoteKey.Control in consumed.activeKeys())
        assertTrue(RemoteKey.Alt in consumed.activeKeys())
        assertEquals(ModifierMode.Locked, consumed.mode(RemoteKey.Alt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non modifier cannot be latched`() {
        ModifierLatch().cycle(RemoteKey.Enter)
    }
}
