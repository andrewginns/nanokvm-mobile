package org.nanokvm.protocol

import kotlinx.serialization.Serializable

/** Persisted HDMI capture state reported by the NanoKVM appliance. */
data class NanoKvmHdmiState(
    val enabled: Boolean,
)

/** Mouse-jiggler modes understood by NanoKVM 2.4.3. */
sealed interface NanoKvmMouseJigglerMode {
    val wireValue: String

    data object Relative : NanoKvmMouseJigglerMode {
        override val wireValue: String = "relative"
    }

    data object Absolute : NanoKvmMouseJigglerMode {
        override val wireValue: String = "absolute"
    }

    /** A bounded value returned by a newer appliance. Unknown modes are read-only. */
    @ConsistentCopyVisibility
    data class Other internal constructor(
        override val wireValue: String,
    ) : NanoKvmMouseJigglerMode
}

data class NanoKvmMouseJigglerState(
    val enabled: Boolean,
    val mode: NanoKvmMouseJigglerMode,
)

/** The only memory-limit value written by the pinned NanoKVM 2.4.3 WebUI. */
enum class NanoKvmMemoryLimitPreset(
    val megabytes: Long,
) {
    TAILSCALE_RECOMMENDED(75L),
}

/**
 * Current Go memory-limit configuration.
 *
 * [preset] is null for a bounded value not offered by the 2.4.3 WebUI, keeping such values
 * visible but read-only.
 */
data class NanoKvmMemoryLimitState(
    val enabled: Boolean,
    val limitMegabytes: Long,
    val preset: NanoKvmMemoryLimitPreset?,
)

/** Exact swap sizes offered by the pinned NanoKVM 2.4.3 WebUI. */
enum class NanoKvmSwapSizePreset(
    val megabytes: Long,
) {
    DISABLED(0L),
    MB_64(64L),
    MB_128(128L),
    MB_256(256L),
    MB_512(512L),
    ;

    companion object {
        fun fromMegabytes(megabytes: Long): NanoKvmSwapSizePreset? =
            entries.firstOrNull { it.megabytes == megabytes }
    }
}

/** A bounded swap-file size; [preset] is null for a newer read-only value. */
data class NanoKvmSwapState(
    val sizeMegabytes: Long,
    val preset: NanoKvmSwapSizePreset?,
)

/** Every state field serialized by NanoKVM's virtual-device GET response. */
enum class NanoKvmVirtualDeviceComponent {
    NETWORK,
    MEDIA,
    DISK,
}

/** Reads one component without implying that the appliance accepts a mutation for it. */
fun NanoKvmVirtualDevices.isEnabled(component: NanoKvmVirtualDeviceComponent): Boolean =
    when (component) {
        NanoKvmVirtualDeviceComponent.NETWORK -> network
        NanoKvmVirtualDeviceComponent.MEDIA -> media
        NanoKvmVirtualDeviceComponent.DISK -> disk
    }

@Serializable
internal data class HdmiStateResponse(
    val enabled: Boolean,
)

@Serializable
internal data class MouseJigglerResponse(
    val enabled: Boolean,
    val mode: String,
)

@Serializable
internal data class SetMouseJigglerRequest(
    val enabled: Boolean,
    val mode: String,
)

@Serializable
internal data class MemoryLimitResponse(
    val enabled: Boolean,
    val limit: Long,
)

@Serializable
internal data class SetMemoryLimitRequest(
    val enabled: Boolean,
    val limit: Long,
)

@Serializable
internal data class SwapResponse(
    val size: Long,
)

@Serializable
internal data class SetSwapRequest(
    val size: Long,
)

@Serializable
internal data class EnableTlsRequest(
    val enabled: Boolean,
)

internal fun MouseJigglerResponse.toModel(): NanoKvmMouseJigglerState {
    if (
        mode.isBlank() ||
        !mode.hasBoundedUtf8Length(MAX_MOUSE_JIGGLER_MODE_UTF8_BYTES) ||
        mode.any(Char::isISOControl)
    ) {
        throw invalidDeviceControlData("mouse-jiggler mode is blank, too long, or contains control text")
    }
    val parsedMode =
        when (mode) {
            NanoKvmMouseJigglerMode.Relative.wireValue -> NanoKvmMouseJigglerMode.Relative
            NanoKvmMouseJigglerMode.Absolute.wireValue -> NanoKvmMouseJigglerMode.Absolute
            else -> NanoKvmMouseJigglerMode.Other(mode)
        }
    return NanoKvmMouseJigglerState(enabled = enabled, mode = parsedMode)
}

internal fun MemoryLimitResponse.toModel(): NanoKvmMemoryLimitState {
    validateReportedMegabytes("memory limit", limit)
    return NanoKvmMemoryLimitState(
        enabled = enabled,
        limitMegabytes = limit,
        preset = NanoKvmMemoryLimitPreset.entries.firstOrNull { it.megabytes == limit },
    )
}

internal fun SwapResponse.toModel(): NanoKvmSwapState {
    validateReportedMegabytes("swap size", size)
    return NanoKvmSwapState(
        sizeMegabytes = size,
        preset = NanoKvmSwapSizePreset.fromMegabytes(size),
    )
}

private fun validateReportedMegabytes(field: String, value: Long) {
    if (value !in 0L..MAX_REPORTED_DEVICE_MEMORY_MEGABYTES) {
        throw invalidDeviceControlData(
            "$field is outside 0..$MAX_REPORTED_DEVICE_MEMORY_MEGABYTES MB",
        )
    }
}

private fun invalidDeviceControlData(message: String): InvalidApiResponseException =
    InvalidApiResponseException("NanoKVM returned invalid device-control response data: $message")

internal const val MAX_MOUSE_JIGGLER_MODE_UTF8_BYTES: Int = 32
internal const val MAX_REPORTED_DEVICE_MEMORY_MEGABYTES: Long = 1_048_576L
