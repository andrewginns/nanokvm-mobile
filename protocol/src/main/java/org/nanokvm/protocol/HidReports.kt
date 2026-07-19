package org.nanokvm.protocol

import java.util.LinkedHashSet
import kotlin.math.roundToInt

enum class HidModifier(val bit: Int) {
    LEFT_CONTROL(0x01),
    LEFT_SHIFT(0x02),
    LEFT_ALT(0x04),
    LEFT_SUPER(0x08),
    RIGHT_CONTROL(0x10),
    RIGHT_SHIFT(0x20),
    RIGHT_ALT(0x40),
    RIGHT_SUPER(0x80),
}

/** USB HID Usage Tables keyboard/keypad page usages needed by a mobile KVM console. */
enum class HidUsage(val code: Int) {
    A(0x04), B(0x05), C(0x06), D(0x07), E(0x08), F(0x09), G(0x0A), H(0x0B),
    I(0x0C), J(0x0D), K(0x0E), L(0x0F), M(0x10), N(0x11), O(0x12), P(0x13),
    Q(0x14), R(0x15), S(0x16), T(0x17), U(0x18), V(0x19), W(0x1A), X(0x1B),
    Y(0x1C), Z(0x1D),
    DIGIT_1(0x1E), DIGIT_2(0x1F), DIGIT_3(0x20), DIGIT_4(0x21), DIGIT_5(0x22),
    DIGIT_6(0x23), DIGIT_7(0x24), DIGIT_8(0x25), DIGIT_9(0x26), DIGIT_0(0x27),
    ENTER(0x28), ESCAPE(0x29), BACKSPACE(0x2A), TAB(0x2B), SPACE(0x2C),
    MINUS(0x2D), EQUAL(0x2E), LEFT_BRACKET(0x2F), RIGHT_BRACKET(0x30),
    BACKSLASH(0x31), NON_US_HASH(0x32), SEMICOLON(0x33), APOSTROPHE(0x34),
    GRAVE(0x35), COMMA(0x36), PERIOD(0x37), SLASH(0x38), CAPS_LOCK(0x39),
    F1(0x3A), F2(0x3B), F3(0x3C), F4(0x3D), F5(0x3E), F6(0x3F),
    F7(0x40), F8(0x41), F9(0x42), F10(0x43), F11(0x44), F12(0x45),
    PRINT_SCREEN(0x46), SCROLL_LOCK(0x47), PAUSE(0x48), INSERT(0x49), HOME(0x4A),
    PAGE_UP(0x4B), DELETE_FORWARD(0x4C), END(0x4D), PAGE_DOWN(0x4E),
    ARROW_RIGHT(0x4F), ARROW_LEFT(0x50), ARROW_DOWN(0x51), ARROW_UP(0x52),
    NUM_LOCK(0x53), NUMPAD_DIVIDE(0x54), NUMPAD_MULTIPLY(0x55),
    NUMPAD_SUBTRACT(0x56), NUMPAD_ADD(0x57), NUMPAD_ENTER(0x58), NUMPAD_1(0x59),
    NUMPAD_2(0x5A), NUMPAD_3(0x5B), NUMPAD_4(0x5C), NUMPAD_5(0x5D), NUMPAD_6(0x5E),
    NUMPAD_7(0x5F), NUMPAD_8(0x60), NUMPAD_9(0x61), NUMPAD_0(0x62),
    NUMPAD_DECIMAL(0x63), NON_US_BACKSLASH(0x64), CONTEXT_MENU(0x65), POWER(0x66),
    NUMPAD_EQUAL(0x67),
    F13(0x68), F14(0x69), F15(0x6A), F16(0x6B), F17(0x6C), F18(0x6D),
    F19(0x6E), F20(0x6F), F21(0x70), F22(0x71), F23(0x72), F24(0x73),
    HELP(0x75), CUT(0x7B), COPY(0x7C), PASTE(0x7D),
    VOLUME_MUTE(0x7F), VOLUME_UP(0x80), VOLUME_DOWN(0x81), NUMPAD_COMMA(0x85),
    NUMPAD_LEFT_PAREN(0xB6), NUMPAD_RIGHT_PAREN(0xB7),
    ;

    companion object {
        private val byCode = entries.associateBy(HidUsage::code)
        fun fromCode(code: Int): HidUsage? = byCode[code]
    }
}

/** Immutable 8-byte USB HID boot keyboard report. */
class HidKeyboardReport private constructor(private val report: ByteArray) {
    fun toByteArray(): ByteArray = report.copyOf()

    fun toWireFrame(): ByteArray = byteArrayOf(MESSAGE_KEYBOARD) + report

    companion object {
        const val MAX_KEYS = 6

        @JvmStatic
        fun create(
            modifiers: Set<HidModifier> = emptySet(),
            keys: Collection<HidUsage> = emptyList(),
        ): HidKeyboardReport {
            val uniqueKeys = keys.distinct()
            require(uniqueKeys.size <= MAX_KEYS) { "A boot keyboard report can contain at most six keys" }
            val bytes = ByteArray(8)
            bytes[0] = modifiers.fold(0) { mask, modifier -> mask or modifier.bit }.toByte()
            uniqueKeys.forEachIndexed { index, usage -> bytes[index + 2] = usage.code.toByte() }
            return HidKeyboardReport(bytes)
        }

        @JvmStatic
        fun released(): HidKeyboardReport = HidKeyboardReport(ByteArray(8))

        internal fun fromBytes(bytes: ByteArray): HidKeyboardReport {
            require(bytes.size == 8) { "Keyboard report must contain eight bytes" }
            return HidKeyboardReport(bytes.copyOf())
        }
    }
}

/** Mutable pressed-key state which always emits a complete HID boot report. */
class KeyboardReportState {
    private val modifiers = LinkedHashSet<HidModifier>()
    private val keys = LinkedHashSet<HidUsage>()

    @Synchronized
    fun press(modifier: HidModifier): HidKeyboardReport {
        modifiers += modifier
        return snapshot()
    }

    @Synchronized
    fun release(modifier: HidModifier): HidKeyboardReport {
        modifiers -= modifier
        return snapshot()
    }

    @Synchronized
    fun press(key: HidUsage): HidKeyboardReport {
        require(key in keys || keys.size < HidKeyboardReport.MAX_KEYS) {
            "A boot keyboard report cannot hold more than six simultaneous keys"
        }
        keys += key
        return snapshot()
    }

    @Synchronized
    fun release(key: HidUsage): HidKeyboardReport {
        keys -= key
        return snapshot()
    }

    @Synchronized
    fun releaseAll(): HidKeyboardReport {
        modifiers.clear()
        keys.clear()
        return HidKeyboardReport.released()
    }

    @Synchronized
    fun snapshot(): HidKeyboardReport = HidKeyboardReport.create(modifiers, keys)

    /** Builds a report with temporary modifiers without changing the intentionally held state. */
    @Synchronized
    fun snapshotWithModifiers(additionalModifiers: Set<HidModifier>): HidKeyboardReport =
        HidKeyboardReport.create(modifiers + additionalModifiers, keys)

    /** A defensive copy of modifiers that should remain held across stateless IME keystrokes. */
    @Synchronized
    fun modifiersSnapshot(): Set<HidModifier> = modifiers.toSet()
}

enum class KeyboardLayout { US, UK }

data class HidKeystroke(
    val usage: HidUsage,
    val modifiers: Set<HidModifier> = emptySet(),
) {
    fun pressReport(): HidKeyboardReport = HidKeyboardReport.create(modifiers, listOf(usage))

    /** A complete stateless press/release pair suitable for committed IME text. */
    fun wireFrames(): List<ByteArray> = listOf(
        pressReport().toWireFrame(),
        HidKeyboardReport.released().toWireFrame(),
    )
}

data class UnsupportedCodePoint(val utf16Index: Int, val codePoint: Int)

data class CharacterMapping(
    val keystrokes: List<HidKeystroke>,
    val unsupported: List<UnsupportedCodePoint>,
)

/** Character-to-physical-key mapping for US ANSI and UK ISO target keyboard layouts. */
object HidCharacterMapper {
    @JvmStatic
    fun map(character: Char, layout: KeyboardLayout = KeyboardLayout.US): HidKeystroke? =
        layoutMap(layout)[character]

    @JvmStatic
    fun mapText(text: String, layout: KeyboardLayout = KeyboardLayout.US): CharacterMapping {
        val mapped = mutableListOf<HidKeystroke>()
        val unsupported = mutableListOf<UnsupportedCodePoint>()
        var index = 0
        val keyMap = layoutMap(layout)
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val stroke = if (codePoint <= Char.MAX_VALUE.code) keyMap[codePoint.toChar()] else null
            if (stroke == null) unsupported += UnsupportedCodePoint(index, codePoint) else mapped += stroke
            index += Character.charCount(codePoint)
        }
        return CharacterMapping(mapped, unsupported)
    }

    private fun layoutMap(layout: KeyboardLayout): Map<Char, HidKeystroke> = when (layout) {
        KeyboardLayout.US -> us
        KeyboardLayout.UK -> uk
    }

    private val us: Map<Char, HidKeystroke> = buildMap {
        ('a'..'z').forEachIndexed { index, character ->
            val usage = requireNotNull(HidUsage.fromCode(HidUsage.A.code + index))
            put(character, HidKeystroke(usage))
            put(character.uppercaseChar(), HidKeystroke(usage, setOf(HidModifier.LEFT_SHIFT)))
        }

        val digits = listOf(
            '1' to HidUsage.DIGIT_1, '2' to HidUsage.DIGIT_2, '3' to HidUsage.DIGIT_3,
            '4' to HidUsage.DIGIT_4, '5' to HidUsage.DIGIT_5, '6' to HidUsage.DIGIT_6,
            '7' to HidUsage.DIGIT_7, '8' to HidUsage.DIGIT_8, '9' to HidUsage.DIGIT_9,
            '0' to HidUsage.DIGIT_0,
        )
        digits.forEach { (character, usage) -> put(character, HidKeystroke(usage)) }
        "!@#$%^&*()".forEachIndexed { index, character ->
            put(character, HidKeystroke(digits[index].second, setOf(HidModifier.LEFT_SHIFT)))
        }

        put('\n', HidKeystroke(HidUsage.ENTER))
        put('\r', HidKeystroke(HidUsage.ENTER))
        put('\t', HidKeystroke(HidUsage.TAB))
        put('\b', HidKeystroke(HidUsage.BACKSPACE))
        put(' ', HidKeystroke(HidUsage.SPACE))

        putPair('-', '_', HidUsage.MINUS)
        putPair('=', '+', HidUsage.EQUAL)
        putPair('[', '{', HidUsage.LEFT_BRACKET)
        putPair(']', '}', HidUsage.RIGHT_BRACKET)
        putPair('\\', '|', HidUsage.BACKSLASH)
        putPair(';', ':', HidUsage.SEMICOLON)
        putPair('\'', '"', HidUsage.APOSTROPHE)
        putPair('`', '~', HidUsage.GRAVE)
        putPair(',', '<', HidUsage.COMMA)
        putPair('.', '>', HidUsage.PERIOD)
        putPair('/', '?', HidUsage.SLASH)
    }

    private val uk: Map<Char, HidKeystroke> = us.toMutableMap().apply {
        this['"'] = HidKeystroke(HidUsage.DIGIT_2, setOf(HidModifier.LEFT_SHIFT))
        this['@'] = HidKeystroke(HidUsage.APOSTROPHE, setOf(HidModifier.LEFT_SHIFT))
        this['#'] = HidKeystroke(HidUsage.NON_US_HASH)
        this['~'] = HidKeystroke(HidUsage.NON_US_HASH, setOf(HidModifier.LEFT_SHIFT))
        this['\\'] = HidKeystroke(HidUsage.NON_US_BACKSLASH)
        this['|'] = HidKeystroke(HidUsage.NON_US_BACKSLASH, setOf(HidModifier.LEFT_SHIFT))
    }

    private fun MutableMap<Char, HidKeystroke>.putPair(
        plain: Char,
        shifted: Char,
        usage: HidUsage,
    ) {
        put(plain, HidKeystroke(usage))
        put(shifted, HidKeystroke(usage, setOf(HidModifier.LEFT_SHIFT)))
    }
}

/**
 * NanoKVM mouse button mask used by both relative and absolute reports.
 *
 * Application 2.3.2 added the browser-navigation buttons using the standard HID numbering:
 * Back is button 4 (bit 3) and Forward is button 5 (bit 4).
 */
enum class MouseButton(val bit: Int) {
    LEFT(0x01),
    RIGHT(0x02),
    MIDDLE(0x04),
    BACK(0x08),
    FORWARD(0x10),
}

sealed interface HidMouseReport {
    fun toPayload(): ByteArray
    fun toWireFrame(): ByteArray = byteArrayOf(MESSAGE_MOUSE) + toPayload()
}

class RelativeMouseReport private constructor(
    val buttonMask: Int,
    val deltaX: Int,
    val deltaY: Int,
    val wheel: Int,
) : HidMouseReport {
    override fun toPayload(): ByteArray = byteArrayOf(
        buttonMask.toByte(),
        deltaX.toByte(),
        deltaY.toByte(),
        wheel.toByte(),
    )

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            buttons: Set<MouseButton> = emptySet(),
            deltaX: Int = 0,
            deltaY: Int = 0,
            wheel: Int = 0,
        ): RelativeMouseReport = RelativeMouseReport(
            buttonMask = buttons.fold(0) { mask, button -> mask or button.bit },
            deltaX = deltaX.coerceIn(-127, 127),
            deltaY = deltaY.coerceIn(-127, 127),
            wheel = wheel.coerceIn(-127, 127),
        )
    }
}

class AbsoluteMouseReport private constructor(
    val buttonMask: Int,
    val x: Int,
    val y: Int,
    val wheel: Int,
) : HidMouseReport {
    override fun toPayload(): ByteArray = byteArrayOf(
        buttonMask.toByte(),
        (x and 0xff).toByte(),
        ((x ushr 8) and 0xff).toByte(),
        (y and 0xff).toByte(),
        ((y ushr 8) and 0xff).toByte(),
        wheel.toByte(),
    )

    companion object {
        const val MAX_COORDINATE = 32767

        @JvmStatic
        @JvmOverloads
        fun create(
            buttons: Set<MouseButton> = emptySet(),
            x: Int,
            y: Int,
            wheel: Int = 0,
        ): AbsoluteMouseReport = AbsoluteMouseReport(
            buttonMask = buttons.fold(0) { mask, button -> mask or button.bit },
            x = x.coerceIn(0, MAX_COORDINATE),
            y = y.coerceIn(0, MAX_COORDINATE),
            wheel = wheel.coerceIn(-127, 127),
        )

        @JvmStatic
        @JvmOverloads
        fun normalized(
            buttons: Set<MouseButton> = emptySet(),
            x: Float,
            y: Float,
            wheel: Int = 0,
        ): AbsoluteMouseReport = create(
            buttons = buttons,
            x = (x.coerceIn(0f, 1f) * MAX_COORDINATE).roundToInt(),
            y = (y.coerceIn(0f, 1f) * MAX_COORDINATE).roundToInt(),
            wheel = wheel,
        )
    }
}

internal const val MESSAGE_HEARTBEAT: Byte = 0
internal const val MESSAGE_KEYBOARD: Byte = 1
internal const val MESSAGE_MOUSE: Byte = 2
