package org.nanokvm.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportsTest {
    @Test
    fun `ctrl alt delete has correct framed boot report`() {
        val report = HidKeyboardReport.create(
            modifiers = setOf(HidModifier.LEFT_CONTROL, HidModifier.LEFT_ALT),
            keys = listOf(HidUsage.DELETE_FORWARD),
        )

        assertArrayEquals(
            byteArrayOf(1, 0x05, 0, 0x4C, 0, 0, 0, 0, 0),
            report.toWireFrame(),
        )
    }

    @Test
    fun `keyboard state preserves insertion order and release all is unconditional`() {
        val state = KeyboardReportState()
        state.press(HidUsage.Z)
        state.press(HidUsage.A)
        state.press(HidModifier.LEFT_SHIFT)

        assertEquals(setOf(HidModifier.LEFT_SHIFT), state.modifiersSnapshot())

        assertArrayEquals(
            byteArrayOf(0x02, 0, 0x1D, 0x04, 0, 0, 0, 0),
            state.snapshot().toByteArray(),
        )
        assertArrayEquals(ByteArray(8), state.releaseAll().toByteArray())
        assertTrue(state.modifiersSnapshot().isEmpty())
    }

    @Test
    fun `seventh simultaneous boot key is rejected`() {
        val state = KeyboardReportState()
        listOf(HidUsage.A, HidUsage.B, HidUsage.C, HidUsage.D, HidUsage.E, HidUsage.F)
            .forEach(state::press)

        assertThrows(IllegalArgumentException::class.java) { state.press(HidUsage.G) }
    }

    @Test
    fun `US and UK printable mappings target physical layouts`() {
        val usAt = HidCharacterMapper.map('@', KeyboardLayout.US)
        val ukAt = HidCharacterMapper.map('@', KeyboardLayout.UK)
        val ukHash = HidCharacterMapper.map('#', KeyboardLayout.UK)

        assertEquals(HidUsage.DIGIT_2, usAt?.usage)
        assertEquals(HidUsage.APOSTROPHE, ukAt?.usage)
        assertEquals(HidUsage.NON_US_HASH, ukHash?.usage)
        assertTrue(HidModifier.LEFT_SHIFT in requireNotNull(ukAt).modifiers)
        assertFalse(HidModifier.LEFT_SHIFT in requireNotNull(ukHash).modifiers)
    }

    @Test
    fun `unsupported supplementary code point is reported once at UTF-16 index`() {
        val mapping = HidCharacterMapper.mapText("a\uD83D\uDE03b")

        assertEquals(2, mapping.keystrokes.size)
        assertEquals(listOf(UnsupportedCodePoint(1, 0x1F603)), mapping.unsupported)
        assertNull(HidCharacterMapper.map('\u00E9'))
    }

    @Test
    fun `relative mouse clamps signed axes and frames message type`() {
        val report = RelativeMouseReport.create(
            buttons = setOf(MouseButton.LEFT, MouseButton.RIGHT),
            deltaX = 999,
            deltaY = -999,
            wheel = 127,
        )

        assertArrayEquals(
            byteArrayOf(2, 0x03, 0x7F, 0x81.toByte(), 0x7F),
            report.toWireFrame(),
        )
    }

    @Test
    fun `absolute mouse encodes little endian coordinates`() {
        val report = AbsoluteMouseReport.create(
            buttons = setOf(MouseButton.MIDDLE),
            x = 32767,
            y = 0x1234,
            wheel = -1,
        )

        assertArrayEquals(
            byteArrayOf(2, 0x04, 0xFF.toByte(), 0x7F, 0x34, 0x12, 0xFF.toByte()),
            report.toWireFrame(),
        )
    }

    @Test
    fun `relative mouse encodes Back and Forward as NanoKVM navigation button bits`() {
        assertArrayEquals(
            byteArrayOf(2, 0x08, 0, 0, 0),
            RelativeMouseReport.create(buttons = setOf(MouseButton.BACK)).toWireFrame(),
        )
        assertArrayEquals(
            byteArrayOf(2, 0x10, 0, 0, 0),
            RelativeMouseReport.create(buttons = setOf(MouseButton.FORWARD)).toWireFrame(),
        )
        assertArrayEquals(
            byteArrayOf(2, 0x18, 0x81.toByte(), 0x7F, 0x81.toByte()),
            RelativeMouseReport.create(
                buttons = setOf(MouseButton.BACK, MouseButton.FORWARD),
                deltaX = -128,
                deltaY = 128,
                wheel = -999,
            ).toWireFrame(),
        )
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0, 0),
            RelativeMouseReport.create().toWireFrame(),
        )
    }

    @Test
    fun `absolute mouse preserves navigation bits while clamping coordinates and wheel`() {
        val report = AbsoluteMouseReport.create(
            buttons = setOf(MouseButton.BACK, MouseButton.FORWARD),
            x = -1,
            y = AbsoluteMouseReport.MAX_COORDINATE + 1,
            wheel = 999,
        )

        assertArrayEquals(
            byteArrayOf(2, 0x18, 0, 0, 0xFF.toByte(), 0x7F, 0x7F),
            report.toWireFrame(),
        )
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0, 0, 0, 0),
            AbsoluteMouseReport.create(x = 0, y = 0).toWireFrame(),
        )
    }

    @Test
    fun `temporary keyboard modifiers do not alter held state`() {
        val state = KeyboardReportState()
        state.press(HidUsage.A)

        val temporaryShift = state.snapshotWithModifiers(setOf(HidModifier.LEFT_SHIFT))
        val restored = state.snapshot()

        assertArrayEquals(
            byteArrayOf(1, 2, 0, HidUsage.A.code.toByte(), 0, 0, 0, 0, 0),
            temporaryShift.toWireFrame(),
        )
        assertArrayEquals(
            byteArrayOf(1, 0, 0, HidUsage.A.code.toByte(), 0, 0, 0, 0, 0),
            restored.toWireFrame(),
        )
    }
}
