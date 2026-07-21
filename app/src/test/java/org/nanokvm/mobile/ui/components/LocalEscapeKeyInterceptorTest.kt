package org.nanokvm.mobile.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEscapeKeyInterceptorTest {
    @Test
    fun `enabled Escape invokes local action once and consumes its complete key pair`() {
        val interceptor = LocalEscapeKeyInterceptor()
        var exits = 0

        assertTrue(interceptor.onKeyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, true) { exits++ })
        assertTrue(interceptor.onKeyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, false) { exits++ })
        assertTrue(interceptor.onKeyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_UP, false) { exits++ })

        assertTrue(exits == 1)
    }

    @Test
    fun `disabled Escape and unrelated keys remain remote-owned`() {
        val interceptor = LocalEscapeKeyInterceptor()

        assertFalse(
            interceptor.onKeyEvent(
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.ACTION_DOWN,
                enabled = false,
            ) {},
        )
        assertFalse(
            interceptor.onKeyEvent(
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.ACTION_UP,
                enabled = false,
            ) {},
        )
        assertFalse(
            interceptor.onKeyEvent(
                KeyEvent.KEYCODE_A,
                KeyEvent.ACTION_DOWN,
                enabled = true,
            ) {},
        )
    }
}
