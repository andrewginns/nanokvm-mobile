package org.nanokvm.protocol

import kotlinx.serialization.Serializable

/** Separate firmware families: NanoKVM OS does not use Sipeed's version thresholds. */
enum class NanoKvmFirmware { SIPEED, NANOKVM_OS, UNKNOWN }

/**
 * Read-only contract from NanoKVM OS /api/vm/video/capabilities.
 * Required fields deliberately have no defaults: an HTML fallback or partial response cannot
 * identify the firmware or enable controls. Unknown fields remain forward compatible.
 */
@Serializable
data class NanoKvmOsVideoCapabilities(
    val input: Input,
    val stream: Stream,
    val transports: Transports,
) {
    @Serializable
    data class Input(val width: Int, val height: Int, val maxFps: Int)

    @Serializable
    data class Stream(
        val limits: List<Limit>,
        val minFps: Int,
        val maxFps: Int,
        val mjpeg: Mjpeg,
    )

    @Serializable
    data class Limit(val height: Int, val width: Int, val available: Boolean)

    @Serializable
    data class Mjpeg(val available: Boolean, val maxSide: Int)

    @Serializable
    data class Transports(val direct: List<String>, val webrtc: List<String>, val mjpeg: List<String>)

    fun validated(): NanoKvmOsVideoCapabilities = apply {
        require(input.width in 0..8192 && input.height in 0..8192)
        require(stream.minFps in 1..120 && stream.maxFps in stream.minFps..120)
        require(input.maxFps in 1..120)
        require(stream.limits.size in 1..32 && stream.limits.any { it.height == 0 })
        require(stream.limits.all { it.height in 0..8192 && it.width in 0..8192 })
        require("h264" in transports.direct && "h264" in transports.webrtc)
        require("mjpeg" in transports.mjpeg && stream.mjpeg.maxSide in 1..8192)
    }

    /** The first adapter exposes the intersection with the client's tested H.264 controls. */
    val supportedHeights: List<Int>
        get() = listOf(0, 600, 720, 1080).filter { height ->
            stream.limits.any { it.height == height && it.available }
        }

    val maximumFrameRate: Int
        get() = minOf(60, stream.maxFps, input.maxFps)

    fun accepts(settings: NanoKvmOsVideoSettings): Boolean =
        settings.height in supportedHeights &&
            settings.fps in stream.minFps..maximumFrameRate
}

/** No monitor/portrait/codec/GOP fields: applying this cannot switch the shared codec or EDID. */
@Serializable
data class NanoKvmOsVideoSettings(
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    val quality: Int,
) {
    init {
        require(height in setOf(0, 600, 720, 1080))
        require(fps in 10..60)
        require(bitRate in setOf(1000, 2000, 3000, 5000))
        require(quality in setOf(50, 60, 80, 100))
    }
}

@Serializable
internal data class NanoKvmOsAccount(
    val role: String,
    val mustChangePassword: Boolean,
)

data class NanoKvmOsSupport(
    val video: NanoKvmOsVideoCapabilities,
    val canConfigureVideo: Boolean,
)

/** OS release labels are not SemVer. A label only triggers probing; it never proves support. */
internal fun isNanoKvmOsReleaseLabel(value: String): Boolean =
    Regex("""[vV]?[0-9]+\.[0-9]+(?:-(?:a|b)[0-9]+)?""").matches(value.trim())

internal val NANO_KVM_OS_CORE_PATHS = setOf(
    "/api/auth/login", "/api/auth/account",
    "/api/vm/info", "/api/vm/hardware", "/api/vm/gpio",
    "/api/vm/video/capabilities", "/api/vm/video",
)
