package org.nanokvm.mobile.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nanokvm.mobile.runtime.RemoteKey

class AndroidRemoteKeyMappingTest {
    @Test
    fun printableAndNavigationKeysPreservePhysicalUsage() {
        assertEquals(RemoteKey.A, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_A))
        assertEquals(RemoteKey.Digit0, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(RemoteKey.Backslash, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_BACKSLASH))
        assertEquals(RemoteKey.Space, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_SPACE))
        assertEquals(RemoteKey.Insert, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_INSERT))
        assertEquals(RemoteKey.Home, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(RemoteKey.End, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(RemoteKey.PageUp, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(RemoteKey.PageDown, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test
    fun rightModifiersRemainDistinctForAltGrAndRightHandShortcuts() {
        assertEquals(RemoteKey.Control, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(RemoteKey.RightControl, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(RemoteKey.Alt, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(RemoteKey.RightAlt, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(RemoteKey.Shift, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(RemoteKey.RightShift, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(RemoteKey.Super, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(RemoteKey.RightSuper, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_META_RIGHT))
    }

    @Test
    fun keypadAndExtendedPcKeysUseTheirOwnHidUsages() {
        assertEquals(RemoteKey.NumpadEnter, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(RemoteKey.NumpadDecimal, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_NUMPAD_DOT))
        assertEquals(RemoteKey.NumpadComma, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_NUMPAD_COMMA))
        assertEquals(
            RemoteKey.NumpadLeftParen,
            remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN),
        )
        assertEquals(RemoteKey.NumLock, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_NUM_LOCK))
        assertEquals(RemoteKey.PrintScreen, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_SYSRQ))
        assertEquals(RemoteKey.Pause, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_BREAK))
        assertEquals(RemoteKey.ContextMenu, remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_MENU))
        assertNull(remoteKeyForAndroidKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }
}
