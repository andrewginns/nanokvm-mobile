package org.nanokvm.protocol

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * A NanoKVM application version with Semantic Versioning precedence.
 *
 * The appliance sometimes decorates this value (for example, `v2.4.3`), so [parse] accepts a
 * semantic version embedded in a human-readable label. Build metadata is retained for display but
 * deliberately does not participate in ordering.
 */
data class NanoKvmApplicationVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
    val buildMetadata: String? = null,
) : Comparable<NanoKvmApplicationVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Version numbers must be non-negative" }
        require(preRelease == null || PRE_RELEASE.matches(preRelease)) {
            "Invalid semantic-version prerelease: $preRelease"
        }
        require(buildMetadata == null || BUILD_METADATA.matches(buildMetadata)) {
            "Invalid semantic-version build metadata: $buildMetadata"
        }
    }

    override fun compareTo(other: NanoKvmApplicationVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        preRelease?.let { append('-').append(it) }
        buildMetadata?.let { append('+').append(it) }
    }

    companion object {
        private val VERSION = Regex(
            """(?<![0-9A-Za-z])[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?![0-9A-Za-z.-])""",
        )
        private val PRE_RELEASE = Regex(
            """(?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[A-Za-z-][0-9A-Za-z-]*))*""",
        )
        private val BUILD_METADATA = Regex("""[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*""")

        /** Returns the first well-formed semantic version in [value], or null when none is usable. */
        @JvmStatic
        fun parse(value: String): NanoKvmApplicationVersion? {
            val match = VERSION.find(value.trim()) ?: return null
            return NanoKvmApplicationVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = match.groupValues[4].ifEmpty { null },
                buildMetadata = match.groupValues[5].ifEmpty { null },
            )
        }

        private fun comparePreRelease(left: String?, right: String?): Int {
            if (left == null && right == null) return 0
            if (left == null) return 1
            if (right == null) return -1

            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val leftPart = leftParts[index]
                val rightPart = rightParts[index]
                val leftIsNumber = leftPart.all(Char::isDigit)
                val rightIsNumber = rightPart.all(Char::isDigit)
                val comparison = when {
                    leftIsNumber && rightIsNumber -> compareNumericIdentifiers(leftPart, rightPart)
                    leftIsNumber -> -1
                    rightIsNumber -> 1
                    else -> leftPart.compareTo(rightPart)
                }
                if (comparison != 0) return comparison
            }
            return leftParts.size.compareTo(rightParts.size)
        }

        private fun compareNumericIdentifiers(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}

/** Capabilities relevant to a native NanoKVM controller, including intentionally unproven ones. */
enum class NanoKvmCapability {
    VM_INFORMATION,
    HARDWARE_INFORMATION,
    GPIO_STATUS,
    GPIO_CONTROL,
    STREAM_CONFIGURATION,
    DIRECT_H264,
    STANDARD_HID_WEBSOCKET,
    SERVER_BATCH_PASTE,
    SAVED_HID_SHORTCUTS,
    HID_LEADER_KEY,
    MOUSE_BACK_FORWARD,
    MEMORY_LIMIT_CONFIGURATION,
    PCIE_HDMI_RESET,
    PCIE_HDMI_CONTROL,
    MOUSE_JIGGLER,
    SWAP_CONFIGURATION,
    TLS_ENABLE,
    VIRTUAL_USB_DEVICE_CONFIGURATION,
    OFFLINE_UPDATE,
    VIRTUAL_MEDIA_UPLOAD,
    VIRTUAL_MEDIA_MOUNT,
    WAKE_ON_LAN,
    WIFI_CONFIGURATION,
    TAILSCALE_EXTENSION,
    OLED_CONFIGURATION,
    TERMINAL,
    SCRIPT_RUNNER,
    AUTOSTART_SCRIPTS,
    RESOLUTION_640_X_480,
    PICOCLAW,
    DNS_CONFIGURATION,
    FRENCH_KEYBOARD_MAPPING,
    CAPTURE_STATUS_REPORTING,
    LT6911D_CAPTURE,
}

sealed interface NanoKvmCapabilityEvidence {
    data class VersionFloor(
        val actual: NanoKvmApplicationVersion,
        val minimum: NanoKvmApplicationVersion,
    ) : NanoKvmCapabilityEvidence

    data class Endpoint(val path: String) : NanoKvmCapabilityEvidence
}

enum class NanoKvmCapabilityUnknownReason {
    VERSION_NOT_REPORTED,
    NO_RELIABLE_VERSION_RULE,
    RUNTIME_PROBE_REQUIRED,
    OPTIONAL_ENDPOINT_FAILED,
}

/** A three-state assessment; unknown is never silently promoted to supported. */
sealed interface NanoKvmCapabilitySupport {
    data class Supported(val evidence: NanoKvmCapabilityEvidence) : NanoKvmCapabilitySupport

    data class Unsupported(val evidence: NanoKvmCapabilityEvidence) : NanoKvmCapabilitySupport

    data class Unknown(
        val reason: NanoKvmCapabilityUnknownReason,
        val minimumVersion: NanoKvmApplicationVersion? = null,
    ) : NanoKvmCapabilitySupport
}

/** Immutable capability snapshot produced by [NanoKvmApi.probeCapabilities]. */
class NanoKvmServerCapabilities internal constructor(
    val applicationVersion: NanoKvmApplicationVersion?,
    assessments: Map<NanoKvmCapability, NanoKvmCapabilitySupport>,
) {
    private val assessments = assessments.toMap()
    val all: Map<NanoKvmCapability, NanoKvmCapabilitySupport> = this.assessments

    operator fun get(capability: NanoKvmCapability): NanoKvmCapabilitySupport =
        assessments.getValue(capability)
}

enum class NanoKvmProbeFailureKind {
    HTTP,
    API,
    INVALID_RESPONSE,
    TRANSPORT,
    UNEXPECTED,
}

/** A safe summary of an optional probe failure; response bodies and credentials are never kept. */
data class NanoKvmProbeFailure(
    val kind: NanoKvmProbeFailureKind,
    val statusCode: Int? = null,
    val apiCode: Int? = null,
    val retryable: Boolean = false,
)

/** Result of probing one optional, read-only endpoint. */
sealed interface NanoKvmProbeResult<out T> {
    data class Supported<T>(val value: T) : NanoKvmProbeResult<T>

    data class Unsupported(val httpStatus: Int) : NanoKvmProbeResult<Nothing>

    data class Unknown(val failure: NanoKvmProbeFailure) : NanoKvmProbeResult<Nothing>
}

data class NanoKvmServerProbeResult(
    val vmInfo: NanoKvmProbeResult<VmInfo>,
    val hardware: NanoKvmProbeResult<HardwareInfo>,
    val gpio: NanoKvmProbeResult<GpioStatus>,
    val capabilities: NanoKvmServerCapabilities,
)

internal suspend fun NanoKvmApi.probeServerCapabilities(): NanoKvmServerProbeResult {
    val vmInfo = optionalReadProbe { vmInfo() }
    val hardware = optionalReadProbe { hardware() }
    val gpio = optionalReadProbe { gpioStatus() }
    val version = (vmInfo as? NanoKvmProbeResult.Supported)
        ?.value
        ?.application
        ?.let(NanoKvmApplicationVersion::parse)

    val assessments = NanoKvmCapability.entries.associateWith { capability ->
        supportFromVersion(capability, version)
    }.toMutableMap()
    assessments[NanoKvmCapability.VM_INFORMATION] = vmInfo.toCapabilitySupport("/api/vm/info")
    assessments[NanoKvmCapability.HARDWARE_INFORMATION] =
        hardware.toCapabilitySupport("/api/vm/hardware")
    assessments[NanoKvmCapability.GPIO_STATUS] = gpio.toCapabilitySupport("/api/vm/gpio")

    return NanoKvmServerProbeResult(
        vmInfo = vmInfo,
        hardware = hardware,
        gpio = gpio,
        capabilities = NanoKvmServerCapabilities(version, assessments),
    )
}

private data class VersionRule(
    val minimum: NanoKvmApplicationVersion,
    val versionIsSufficient: Boolean,
)

private val VERSION_RULES: Map<NanoKvmCapability, VersionRule> = mapOf(
    // Manual Wi-Fi configuration first appeared for NanoKVM-PCIe in 2.1.2. Hardware support is
    // still authoritative and is reported by `/api/network/wifi`; no scan route exists.
    NanoKvmCapability.WIFI_CONFIGURATION to versionRule("2.1.2", runtimeProbe = true),
    NanoKvmCapability.MEMORY_LIMIT_CONFIGURATION to versionRule("2.1.4"),
    NanoKvmCapability.PCIE_HDMI_RESET to versionRule("2.1.5", runtimeProbe = true),
    // 2.1.6 moved the stable controls to `/api/extensions/tailscale`; status remains usable when
    // the optional binaries are not installed, so application version is sufficient.
    NanoKvmCapability.TAILSCALE_EXTENSION to versionRule("2.1.6"),
    NanoKvmCapability.MOUSE_JIGGLER to versionRule("2.2.6"),
    NanoKvmCapability.SWAP_CONFIGURATION to versionRule("2.2.6"),
    NanoKvmCapability.DIRECT_H264 to versionRule("2.2.7"),
    NanoKvmCapability.TLS_ENABLE to versionRule("2.2.7"),
    NanoKvmCapability.OLED_CONFIGURATION to versionRule("2.1.4"),
    NanoKvmCapability.PCIE_HDMI_CONTROL to versionRule("2.2.8", runtimeProbe = true),
    NanoKvmCapability.OFFLINE_UPDATE to versionRule("2.3.1"),
    // The endpoint family first appears in upstream commit 53924dc8 and is present from the
    // 2.3.1 tag onward, although the release changelog does not call it out.
    NanoKvmCapability.AUTOSTART_SCRIPTS to versionRule("2.3.1"),
    NanoKvmCapability.VIRTUAL_MEDIA_UPLOAD to versionRule("2.3.1", runtimeProbe = true),
    NanoKvmCapability.STREAM_CONFIGURATION to versionRule("2.3.2"),
    NanoKvmCapability.STANDARD_HID_WEBSOCKET to versionRule("2.3.2"),
    NanoKvmCapability.SERVER_BATCH_PASTE to versionRule("2.3.2"),
    NanoKvmCapability.SAVED_HID_SHORTCUTS to versionRule("2.3.2"),
    NanoKvmCapability.HID_LEADER_KEY to versionRule("2.3.4"),
    NanoKvmCapability.MOUSE_BACK_FORWARD to versionRule("2.3.2"),
    NanoKvmCapability.GPIO_CONTROL to versionRule("2.3.2", runtimeProbe = true),
    NanoKvmCapability.VIRTUAL_MEDIA_MOUNT to versionRule("2.3.2", runtimeProbe = true),
    NanoKvmCapability.VIRTUAL_USB_DEVICE_CONFIGURATION to
        versionRule("2.3.2", runtimeProbe = true),
    NanoKvmCapability.WAKE_ON_LAN to versionRule("2.3.2", runtimeProbe = true),
    // 2.3.1 fixed PTY disconnect cleanup; the app's 2.3.2 floor therefore has the
    // authenticated `/api/vm/terminal` contract without a separate hardware gate.
    NanoKvmCapability.TERMINAL to versionRule("2.3.2"),
    NanoKvmCapability.RESOLUTION_640_X_480 to versionRule("2.3.6"),
    NanoKvmCapability.PICOCLAW to versionRule("2.4.0", runtimeProbe = true),
    NanoKvmCapability.DNS_CONFIGURATION to versionRule("2.4.1"),
    NanoKvmCapability.FRENCH_KEYBOARD_MAPPING to versionRule("2.4.1"),
    NanoKvmCapability.CAPTURE_STATUS_REPORTING to versionRule("2.4.2"),
    NanoKvmCapability.LT6911D_CAPTURE to versionRule("2.4.3", runtimeProbe = true),
)

private fun versionRule(value: String, runtimeProbe: Boolean = false): VersionRule = VersionRule(
    minimum = requireNotNull(NanoKvmApplicationVersion.parse(value)),
    versionIsSufficient = !runtimeProbe,
)

private fun supportFromVersion(
    capability: NanoKvmCapability,
    actual: NanoKvmApplicationVersion?,
): NanoKvmCapabilitySupport {
    val rule = VERSION_RULES[capability]
        ?: return NanoKvmCapabilitySupport.Unknown(
            NanoKvmCapabilityUnknownReason.NO_RELIABLE_VERSION_RULE,
        )
    if (actual == null) {
        return NanoKvmCapabilitySupport.Unknown(
            reason = NanoKvmCapabilityUnknownReason.VERSION_NOT_REPORTED,
            minimumVersion = rule.minimum,
        )
    }
    val evidence = NanoKvmCapabilityEvidence.VersionFloor(actual, rule.minimum)
    if (actual < rule.minimum) return NanoKvmCapabilitySupport.Unsupported(evidence)
    if (!rule.versionIsSufficient) {
        return NanoKvmCapabilitySupport.Unknown(
            reason = NanoKvmCapabilityUnknownReason.RUNTIME_PROBE_REQUIRED,
            minimumVersion = rule.minimum,
        )
    }
    return NanoKvmCapabilitySupport.Supported(evidence)
}

private suspend fun <T> optionalReadProbe(block: suspend () -> T): NanoKvmProbeResult<T> = try {
    NanoKvmProbeResult.Supported(block())
} catch (error: CancellationException) {
    throw error
} catch (error: AuthenticationExpiredException) {
    throw error
} catch (error: HttpResponseException) {
    if (error.statusCode in OPTIONAL_ENDPOINT_UNSUPPORTED_STATUS) {
        NanoKvmProbeResult.Unsupported(error.statusCode)
    } else {
        NanoKvmProbeResult.Unknown(
            NanoKvmProbeFailure(
                kind = NanoKvmProbeFailureKind.HTTP,
                statusCode = error.statusCode,
                retryable = error.statusCode == 408 || error.statusCode == 429 ||
                    error.statusCode >= 500,
            ),
        )
    }
} catch (error: ApiResponseException) {
    NanoKvmProbeResult.Unknown(
        NanoKvmProbeFailure(kind = NanoKvmProbeFailureKind.API, apiCode = error.code),
    )
} catch (_: InvalidApiResponseException) {
    NanoKvmProbeResult.Unknown(
        NanoKvmProbeFailure(kind = NanoKvmProbeFailureKind.INVALID_RESPONSE),
    )
} catch (_: IOException) {
    NanoKvmProbeResult.Unknown(
        NanoKvmProbeFailure(kind = NanoKvmProbeFailureKind.TRANSPORT, retryable = true),
    )
} catch (_: Exception) {
    NanoKvmProbeResult.Unknown(
        NanoKvmProbeFailure(kind = NanoKvmProbeFailureKind.UNEXPECTED),
    )
}

private fun NanoKvmProbeResult<*>.toCapabilitySupport(path: String): NanoKvmCapabilitySupport =
    when (this) {
        is NanoKvmProbeResult.Supported -> NanoKvmCapabilitySupport.Supported(
            NanoKvmCapabilityEvidence.Endpoint(path),
        )
        is NanoKvmProbeResult.Unsupported -> NanoKvmCapabilitySupport.Unsupported(
            NanoKvmCapabilityEvidence.Endpoint(path),
        )
        is NanoKvmProbeResult.Unknown -> NanoKvmCapabilitySupport.Unknown(
            NanoKvmCapabilityUnknownReason.OPTIONAL_ENDPOINT_FAILED,
        )
    }

private val OPTIONAL_ENDPOINT_UNSUPPORTED_STATUS = setOf(404, 405, 501)
