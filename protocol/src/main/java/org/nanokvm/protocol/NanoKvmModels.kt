package org.nanokvm.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.net.URISyntaxException

@Serializable
internal data class RawApiEnvelope(
    val code: Int,
    val msg: String = "",
    val data: JsonElement? = null,
)

@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class SessionToken(val token: String) {
    override fun toString(): String = "SessionToken(token=<redacted>)"
}

@Serializable
data class VmInfo(
    val ips: List<NetworkInterface> = emptyList(),
    val mdns: String = "",
    val image: String = "",
    val application: String = "",
    val deviceKey: String = "",
)

@Serializable
internal data class VmInfoResponse(
    // The Go server leaves this slice nil when no usable IPv4 interface was found.
    val ips: List<NetworkInterface>? = null,
    val mdns: String = "",
    val image: String = "",
    val application: String = "",
    val deviceKey: String = "",
)

internal fun VmInfoResponse.toModel(): VmInfo = VmInfo(
    ips = ips.orEmpty(),
    mdns = mdns,
    image = image,
    application = application,
    deviceKey = deviceKey,
)

@Serializable
data class NetworkInterface(
    val name: String = "",
    val addr: String = "",
    val version: String = "",
    val type: String = "",
)

@Serializable
data class HardwareInfo(val version: String = "")

@Serializable
data class GpioStatus(
    @SerialName("pwr") val powerOn: Boolean = false,
    @SerialName("hdd") val hardDriveActive: Boolean = false,
)

enum class GpioAction(internal val wireName: String) {
    POWER("power"),
    RESET("reset"),
}

@Serializable
internal data class GpioRequest(
    val type: String,
    val duration: Long,
)

enum class ScreenSetting(internal val wireName: String) {
    VIDEO_TYPE("type"),
    RESOLUTION("resolution"),
    FPS("fps"),
    QUALITY("quality"),
    GOP("gop"),
}

@Serializable
internal data class ScreenRequest(
    val type: String,
    val value: Int,
)

@Serializable
internal data class MjpegFrameDetectionRequest(val enabled: Boolean)

@Serializable
internal data class MjpegFrameDetectionPauseRequest(val duration: Int)

enum class PasteLanguage(internal val wireName: String) {
    ENGLISH("en"),
    GERMAN("de"),
    FRENCH("fr"),
    RUSSIAN("ru"),
}

@Serializable
internal data class PasteRequest(
    val content: String,
    // The misspelling is part of NanoKVM's public wire contract.
    val langue: String,
) {
    override fun toString(): String =
        "PasteRequest(content=<redacted>, langue=$langue)"
}

@Serializable
internal data class ImageListResponse(
    // Go's encoding/json represents the server's nil slice as null when /data has no images.
    val files: List<String>?,
)

/**
 * An exact image-list entry returned by this NanoKVM. Instances are intentionally not
 * constructible by callers; mount and delete additionally require the originating catalog.
 */
class NanoKvmImage internal constructor(val path: String) {
    val fileName: String
        get() = path.substringAfterLast('/')

    override fun toString(): String = path
}

/** An immutable snapshot of the server's validated image list. */
class NanoKvmImageCatalog internal constructor(images: List<NanoKvmImage>) {
    val images: List<NanoKvmImage> = images.toList()

    /** Finds the opaque handle for an exact path in this snapshot. */
    fun find(path: String): NanoKvmImage? = images.firstOrNull { it.path == path }

    internal fun requireExactMember(image: NanoKvmImage) {
        require(images.any { it === image }) {
            "Image must be an exact handle from the supplied NanoKVM image catalog"
        }
    }
}

/** The currently mounted server path; null is represented by a null API result. */
@ConsistentCopyVisibility
data class NanoKvmMountedImage internal constructor(val path: String)

@Serializable
internal data class MountedImageResponse(val file: String)

data class NanoKvmCdRomState(val enabled: Boolean)

@Serializable
internal data class CdRomResponse(val cdrom: Int)

enum class NanoKvmImageMountMode(internal val cdrom: Boolean) {
    MASS_STORAGE(false),
    CD_ROM(true),
}

@Serializable
internal data class MountImageRequest(
    val file: String,
    val cdrom: Boolean,
)

@Serializable
internal data class ImagePathRequest(val file: String)

sealed interface NanoKvmHidMode {
    val wireValue: String

    data object Normal : NanoKvmHidMode {
        override val wireValue: String = "normal"
    }

    data object HidOnly : NanoKvmHidMode {
        override val wireValue: String = "hid-only"
    }

    /** A bounded value introduced by a newer appliance version. */
    @ConsistentCopyVisibility
    data class Other internal constructor(override val wireValue: String) : NanoKvmHidMode
}

@Serializable
internal data class HidModeResponse(val mode: String)

@Serializable
internal data class HidModeRequest(val mode: String)

data class NanoKvmVirtualDevices(
    val network: Boolean,
    /** Vestigial 2.4.3 response field. The pinned server exposes no matching mutation. */
    val media: Boolean,
    val disk: Boolean,
)

@Serializable
internal data class VirtualDevicesResponse(
    val network: Boolean,
    val media: Boolean,
    val disk: Boolean,
)

/** Devices accepted by the 2.4.3 toggle endpoint; `media` is intentionally not selectable. */
enum class NanoKvmVirtualDevice(internal val wireName: String) {
    DISK("disk"),
    NETWORK("network"),
}

@Serializable
internal data class VirtualDeviceRequest(val device: String)

@Serializable
internal data class VirtualDeviceToggleResponse(val on: Boolean)

/** The observed state returned by one non-idempotent virtual-device toggle. */
data class NanoKvmVirtualDeviceToggleResult(
    val device: NanoKvmVirtualDevice,
    val enabled: Boolean,
)

@Serializable
internal data class ImageTransferEnabledResponse(val enabled: Boolean)

/** A validated remote HTTP(S) `.iso` or `.img` source accepted by NanoKVM 2.4.3. */
class NanoKvmRemoteImageUrl private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        other is NanoKvmRemoteImageUrl && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private val SAFE_IMAGE_BASENAME = Regex(
            pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.(?:iso|img)",
            option = RegexOption.IGNORE_CASE,
        )

        @JvmStatic
        fun parse(value: String): NanoKvmRemoteImageUrl {
            require(value == value.trim() && value.isNotEmpty()) {
                "Remote image URL must not be blank or surrounded by whitespace"
            }
            require(value.utf8Size() <= MAX_TRANSFER_URL_UTF8_BYTES) {
                "Remote image URL is too long"
            }
            val uri = try {
                URI(value)
            } catch (error: URISyntaxException) {
                throw IllegalArgumentException("Remote image URL is invalid", error)
            }
            require(uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) {
                "Remote image URL must use HTTP or HTTPS"
            }
            require(!uri.host.isNullOrBlank()) { "Remote image URL must include a host" }
            require(uri.userInfo == null) { "Remote image URL must not contain credentials" }
            require(uri.fragment == null) { "Remote image URL must not contain a fragment" }
            val rawPath = uri.rawPath.orEmpty()
            val basename = rawPath.substringAfterLast('/')
            require(".." !in basename) { "Remote image basename must not contain '..'" }
            require(SAFE_IMAGE_BASENAME.matches(basename)) {
                "Remote image URL must end in a safe .iso or .img basename"
            }
            return NanoKvmRemoteImageUrl(value)
        }
    }
}

@Serializable
internal data class StartImageTransferRequest(val file: String)

@Serializable
internal data class ImageTransferResponse(
    val status: String,
    val file: String = "",
    val percentage: String = "",
)

sealed interface NanoKvmImageTransferState {
    val wireValue: String

    data object Idle : NanoKvmImageTransferState {
        override val wireValue: String = "idle"
    }

    data object InProgress : NanoKvmImageTransferState {
        override val wireValue: String = "in_progress"
    }

    /** A bounded state introduced by a newer appliance version. */
    @ConsistentCopyVisibility
    data class Other internal constructor(override val wireValue: String) :
        NanoKvmImageTransferState
}

/** Transfer status without fields from the newer, unstable cancellation/checksum protocol. */
data class NanoKvmImageTransferStatus(
    val state: NanoKvmImageTransferState,
    val source: String,
    val percentageText: String,
    val percentage: Double?,
)

/** Canonical uppercase, colon-separated MAC address used by Wake-on-LAN operations. */
class NanoKvmMacAddress private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        other is NanoKvmMacAddress && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun parse(value: String): NanoKvmMacAddress {
            require(value.utf8Size() <= MAX_MAC_INPUT_UTF8_BYTES) { "MAC address is too long" }
            val trimmed = value.trim()
            require(trimmed.isNotEmpty() && trimmed.none(Char::isWhitespace)) {
                "MAC address must not be blank or contain whitespace"
            }
            val compact = trimmed.replace(":", "").replace("-", "").replace(".", "")
            require(compact.length == 12 && compact.all {
                it in '0'..'9' || it.uppercaseChar() in 'A'..'F'
            }) {
                "MAC address must contain exactly 12 hexadecimal digits"
            }
            val canonical = compact.uppercase().chunked(2).joinToString(":")
            return NanoKvmMacAddress(canonical)
        }
    }
}

data class NanoKvmWakeOnLanEntry(
    val mac: NanoKvmMacAddress,
    val name: String?,
)

@Serializable
internal data class WakeOnLanRequest(val mac: String)

@Serializable
internal data class WakeOnLanHistoryResponse(
    // Older/forked servers can return a nil slice for an empty history.
    val macs: List<String>?,
)

@Serializable
internal data class RenameWakeOnLanRequest(
    val mac: String,
    val name: String,
)

internal const val MAX_IMAGE_COUNT = 1024
internal const val MAX_IMAGE_PATH_UTF8_BYTES = 4096
internal const val MAX_HID_MODE_UTF8_BYTES = 64
internal const val MAX_TRANSFER_URL_UTF8_BYTES = 4096
internal const val MAX_TRANSFER_STATUS_UTF8_BYTES = 64
internal const val MAX_TRANSFER_FILE_UTF8_BYTES = 4096
internal const val MAX_TRANSFER_PERCENTAGE_UTF8_BYTES = 32
internal const val MAX_WOL_HISTORY_ENTRIES = 512
internal const val MAX_WOL_HISTORY_ENTRY_UTF8_BYTES = 512
internal const val MAX_WOL_NAME_UTF8_BYTES = 128
internal const val MAX_MAC_INPUT_UTF8_BYTES = 64

internal fun String.utf8Size(): Int = encodeToByteArray().size

/** Base type for errors that are meaningful to NanoKVM protocol callers. */
sealed class NanoKvmException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AuthenticationExpiredException(
    val statusCode: Int = 401,
) : NanoKvmException("NanoKVM session is missing or expired")

class HttpResponseException(
    val statusCode: Int,
) : NanoKvmException("NanoKVM returned HTTP $statusCode")

internal enum class ApiResponseServerMessageKind {
    MISSING_WEB_TITLE,
    LEGACY_EMPTY_WOL_HISTORY,
    OTHER,
}

class ApiResponseException(
    val code: Int,
    rawServerMessage: String,
) : NanoKvmException("NanoKVM API error $code") {
    /** A narrow compatibility classification; appliance-controlled response text is not retained. */
    internal val serverMessageKind: ApiResponseServerMessageKind = when {
        rawServerMessage == "read web title failed" ->
            ApiResponseServerMessageKind.MISSING_WEB_TITLE
        rawServerMessage.trim() == "open file error" ->
            ApiResponseServerMessageKind.LEGACY_EMPTY_WOL_HISTORY
        else -> ApiResponseServerMessageKind.OTHER
    }

    /** Kept source-compatible for callers while deliberately exposing no server response text. */
    val serverMessage: String
        get() = ""
}

class InvalidApiResponseException(message: String, cause: Throwable? = null) :
    NanoKvmException(message, cause)
