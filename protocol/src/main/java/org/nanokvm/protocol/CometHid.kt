package org.nanokvm.protocol

import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Binary messages used by the stock RM1 console and KVMD's HID WebSocket handlers. */
object CometHid {
    fun key(code: String, down: Boolean) = named(1, code, down)
    fun button(name: String, down: Boolean) = named(2, name, down)
    private fun named(type: Byte, name: String, down: Boolean): ByteString =
        (byteArrayOf(type, if (down) 1 else 0) + name.toByteArray(Charsets.US_ASCII)).toByteString()

    fun absolute(x: Int, y: Int): ByteString {
        fun signed(value: Int) = (value.coerceIn(0, 32767) * 65535L / 32767 - 32768).toInt()
        val sx = signed(x)
        val sy = signed(y)
        return byteArrayOf(3, (sx shr 8).toByte(), sx.toByte(), (sy shr 8).toByte(), sy.toByte()).toByteString()
    }

    fun delta(wheel: Boolean, x: Int, y: Int): ByteString = byteArrayOf(
        if (wheel) 5 else 4, 0, x.coerceIn(-127, 127).toByte(), y.coerceIn(-127, 127).toByte(),
    ).toByteString()
}
