package org.nanokvm.protocol

import kotlinx.serialization.Serializable

/** Terminal dimensions encoded as NanoKVM's binary JSON resize message. */
data class NanoKvmTerminalSize(
    val rows: Int,
    val columns: Int,
) {
    init {
        require(rows in 1..UShort.MAX_VALUE.toInt()) {
            "Terminal rows must fit an unsigned 16-bit integer"
        }
        require(columns in 1..UShort.MAX_VALUE.toInt()) {
            "Terminal columns must fit an unsigned 16-bit integer"
        }
    }
}

/** A shell-safe serial device accepted by the NanoKVM WebUI's serial terminal. */
class NanoKvmSerialPort private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is NanoKvmSerialPort && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmField
        val TTY_S1: NanoKvmSerialPort = NanoKvmSerialPort("/dev/ttyS1")

        @JvmField
        val TTY_S2: NanoKvmSerialPort = NanoKvmSerialPort("/dev/ttyS2")

        private val VALID_PORT = Regex("/dev/[A-Za-z0-9._-]+")

        @JvmStatic
        fun parse(value: String): NanoKvmSerialPort {
            require(value.utf8Size() <= MAX_SERIAL_PORT_UTF8_BYTES) {
                "Serial device path is too long"
            }
            require(VALID_PORT.matches(value)) {
                "Serial device must fully match /dev/[A-Za-z0-9._-]+"
            }
            val basename = value.removePrefix("/dev/")
            require(basename != "." && basename != "..") {
                "Serial device must identify a device below /dev"
            }
            return when (value) {
                TTY_S1.value -> TTY_S1
                TTY_S2.value -> TTY_S2
                else -> NanoKvmSerialPort(value)
            }
        }
    }
}

/** Exact baud-rate allowlist exposed by the NanoKVM 2.4.3 serial-terminal WebUI. */
enum class NanoKvmSerialBaud(val wireValue: Int) {
    B50(50),
    B75(75),
    B110(110),
    B134(134),
    B150(150),
    B200(200),
    B300(300),
    B600(600),
    B1200(1200),
    B1800(1800),
    B2400(2400),
    B4800(4800),
    B9600(9600),
    B19200(19200),
    B38400(38400),
    B57600(57600),
    B115200(115200),
    B230400(230400),
    B460800(460800),
    B500000(500000),
    B576000(576000),
    B921600(921600),
    B1000000(1000000),
    B1152000(1152000),
    B1500000(1500000),
    B2000000(2000000),
    B2500000(2500000),
    B3000000(3000000),
    B3500000(3500000),
    B4000000(4000000),
}

enum class NanoKvmSerialParity(val wireValue: String) {
    NONE("none"),
    EVEN("even"),
    ODD("odd"),
}

enum class NanoKvmSerialFlowControl(val wireValue: String) {
    NONE("none"),
    SOFTWARE("soft"),
    HARDWARE("hard"),
}

enum class NanoKvmSerialDataBits(val wireValue: Int) {
    FIVE(5),
    SIX(6),
    SEVEN(7),
    EIGHT(8),
}

enum class NanoKvmSerialStopBits(val wireValue: Int) {
    ONE(1),
    TWO(2),
}

/** Typed serial configuration; no user text is interpolated except a validated device path. */
data class NanoKvmSerialConfiguration(
    val port: NanoKvmSerialPort = NanoKvmSerialPort.TTY_S1,
    val baud: NanoKvmSerialBaud = NanoKvmSerialBaud.B115200,
    val parity: NanoKvmSerialParity = NanoKvmSerialParity.NONE,
    val flowControl: NanoKvmSerialFlowControl = NanoKvmSerialFlowControl.NONE,
    val dataBits: NanoKvmSerialDataBits = NanoKvmSerialDataBits.EIGHT,
    val stopBits: NanoKvmSerialStopBits = NanoKvmSerialStopBits.ONE,
) {
    internal fun toPicocomCommand(): String = buildString {
        append("picocom ")
        append(port.value)
        append(" --baud ")
        append(baud.wireValue)
        append(" --parity ")
        append(parity.wireValue)
        append(" --flow ")
        append(flowControl.wireValue)
        append(" --databits ")
        append(dataBits.wireValue)
        append(" --stopbits ")
        append(stopBits.wireValue)
        append('\r')
    }
}

/** An opaque safe basename from one exact script-list snapshot. */
class NanoKvmScript internal constructor(val name: String) {
    val extension: String
        get() = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    override fun toString(): String = name
}

/** Immutable script-list snapshot. The API additionally requires it to be the latest snapshot. */
class NanoKvmScriptCatalog internal constructor(scripts: List<NanoKvmScript>) {
    val scripts: List<NanoKvmScript> = scripts.toList()

    fun find(name: String): NanoKvmScript? = scripts.firstOrNull { it.name == name }

    internal fun requireExactMember(script: NanoKvmScript) {
        require(scripts.any { it === script }) {
            "Script must be an exact handle from the supplied NanoKVM script catalog"
        }
    }
}

/** Upload success is only a receipt; list again to obtain an executable opaque handle. */
data class NanoKvmScriptUploadReceipt(
    val fileName: String,
    val byteCount: Int,
)

enum class NanoKvmScriptRunMode(internal val wireValue: String) {
    FOREGROUND("foreground"),
    BACKGROUND("background"),
}

data class NanoKvmScriptRunResult(
    val mode: NanoKvmScriptRunMode,
    /** Foreground combined stdout/stderr. NanoKVM 2.4.3 returns an empty string for background. */
    val output: String,
) {
    override fun toString(): String =
        "NanoKvmScriptRunResult(mode=$mode, output=<redacted>)"
}

enum class NanoKvmScriptOperation {
    LIST,
    UPLOAD,
    RUN,
    DELETE,
}

/** Known 2.4.3 failures plus bounded preservation of codes introduced by future servers. */
sealed interface NanoKvmScriptFailure {
    val code: Int
    val serverMessage: String

    data class Rejected(
        override val code: Int,
        override val serverMessage: String,
    ) : NanoKvmScriptFailure {
        override fun toString(): String =
            "NanoKvmScriptFailure.Rejected(code=$code, serverMessage=<redacted>)"
    }

    data class OperationFailed(
        override val code: Int,
        override val serverMessage: String,
    ) : NanoKvmScriptFailure {
        override fun toString(): String =
            "NanoKvmScriptFailure.OperationFailed(code=$code, serverMessage=<redacted>)"
    }

    data class Other(
        override val code: Int,
        override val serverMessage: String,
    ) : NanoKvmScriptFailure {
        override fun toString(): String =
            "NanoKvmScriptFailure.Other(code=$code, serverMessage=<redacted>)"
    }
}

class NanoKvmScriptOperationException(
    val operation: NanoKvmScriptOperation,
    val failure: NanoKvmScriptFailure,
) : NanoKvmException(
    "NanoKVM script ${operation.name.lowercase()} failed with API code ${failure.code}",
)

@Serializable
internal data class TerminalResizeRequest(
    val rows: Int,
    val cols: Int,
)

@Serializable
internal data class ScriptListResponse(
    // Empty script directories are returned by the Go server as a nil slice (JSON null).
    val files: List<String>?,
)

@Serializable
internal data class ScriptUploadResponse(val file: String)

@Serializable
internal data class ScriptRunRequest(
    val name: String,
    val type: String,
)

@Serializable
internal data class ScriptRunResponse(val log: String = "")

@Serializable
internal data class ScriptNameRequest(val name: String)

internal const val MAX_SERIAL_PORT_UTF8_BYTES = 128
internal const val MAX_TERMINAL_CLIENT_TEXT_UTF8_BYTES = 64 * 1024
internal const val MAX_TERMINAL_SERVER_CHUNK_BYTES = 64 * 1024
internal const val MAX_SCRIPT_COUNT = 512
internal const val MAX_SCRIPT_BASENAME_UTF8_BYTES = 255
internal const val MAX_SCRIPT_UPLOAD_BYTES = 512 * 1024
internal const val MAX_SCRIPT_OUTPUT_UTF8_BYTES = 256 * 1024
internal const val MAX_SCRIPT_ERROR_MESSAGE_UTF8_BYTES = 512

private val SAFE_SCRIPT_BASENAME = Regex(
    pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:sh|py)",
    option = RegexOption.IGNORE_CASE,
)

internal fun validateScriptBasename(value: String): String {
    require(value.utf8Size() <= MAX_SCRIPT_BASENAME_UTF8_BYTES) {
        "Script basename is too long"
    }
    require(SAFE_SCRIPT_BASENAME.matches(value) && ".." !in value) {
        "Script must be a safe .sh or .py basename"
    }
    return value
}
