package org.nanokvm.protocol

import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The currently configured NanoKVM account. */
public data class NanoKvmAccount(
    public val username: String,
)

/** Whether the factory NanoKVM password has been replaced. */
public data class NanoKvmPasswordStatus(
    public val isUpdated: Boolean,
)

/** Installed and available NanoKVM application versions. */
public data class NanoKvmApplicationVersions(
    public val current: String,
    public val latest: String?,
    public val currentVersion: NanoKvmApplicationVersion?,
    public val latestVersion: NanoKvmApplicationVersion?,
)

/** Whether preview application releases are included in update checks. */
public data class NanoKvmPreviewUpdates(
    public val enabled: Boolean,
)

/** OLED sleep values exposed by the NanoKVM 2.4.3 WebUI. */
public enum class NanoKvmOledSleepPreset(
    public val seconds: Int,
) {
    NEVER(0),
    SECONDS_15(15),
    SECONDS_30(30),
    MINUTE_1(60),
    MINUTES_3(180),
    MINUTES_5(300),
    MINUTES_10(600),
    MINUTES_30(1_800),
    HOUR_1(3_600),
    ;

    public companion object {
        public fun fromSeconds(seconds: Int): NanoKvmOledSleepPreset? =
            entries.firstOrNull { it.seconds == seconds }
    }
}

/** Current OLED availability and sleep setting. Unknown, bounded values remain readable. */
public data class NanoKvmOledConfiguration(
    public val exists: Boolean,
    public val sleepSeconds: Int,
    public val sleepPreset: NanoKvmOledSleepPreset?,
)

public data class NanoKvmSshState(
    public val enabled: Boolean,
)

public data class NanoKvmHostname(
    public val value: String,
)

public data class NanoKvmMdnsState(
    public val enabled: Boolean,
)

/** Current browser title. NanoKVM treats an empty value and "NanoKVM" as the default. */
public data class NanoKvmWebTitle(
    public val value: String,
    public val isDefault: Boolean,
) {
    public companion object {
        public const val DEFAULT: String = "NanoKVM"
    }
}

/** DNS modes returned by NanoKVM. Unknown bounded values are preserved for compatibility. */
public sealed interface NanoKvmDnsMode {
    public val wireValue: String

    public data object Dhcp : NanoKvmDnsMode {
        override val wireValue: String = "dhcp"
    }

    public data object Manual : NanoKvmDnsMode {
        override val wireValue: String = "manual"
    }

    @ConsistentCopyVisibility
    public data class Other internal constructor(
        override val wireValue: String,
    ) : NanoKvmDnsMode
}

/** A validated, canonical IPv4 or IPv6 literal. Hostnames are deliberately not accepted. */
public class NanoKvmIpAddress private constructor(
    public val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is NanoKvmIpAddress && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    public companion object {
        public fun parse(value: String): NanoKvmIpAddress =
            NanoKvmIpAddress(canonicalIpLiteral(value))
    }
}

/** Network metadata accompanying the effective DNS resolver state. */
public data class NanoKvmDnsInfo(
    public val interfaceName: String,
    public val type: String,
    public val address: String,
    public val subnetMask: String,
    public val gateway: String,
    public val searchDomains: List<String>,
)

/** Configured and effective NanoKVM DNS state. */
public data class NanoKvmDnsConfiguration(
    public val mode: NanoKvmDnsMode,
    public val servers: List<NanoKvmIpAddress>,
    public val effectiveServers: List<NanoKvmIpAddress>,
    public val dhcpServers: List<NanoKvmIpAddress>,
    public val info: NanoKvmDnsInfo,
)

@Serializable
internal data class AccountResponse(
    val username: String,
)

@Serializable
internal data class PasswordStatusResponse(
    val isUpdated: Boolean,
)

@Serializable
internal data class ChangePasswordRequest(
    val username: String,
    val password: String,
)

@Serializable
internal data class ApplicationVersionResponse(
    val current: String,
    val latest: String = "",
)

@Serializable
internal data class PreviewUpdatesResponse(
    val enabled: Boolean,
)

@Serializable
internal data class SetPreviewUpdatesRequest(
    val enable: Boolean,
)

@Serializable
internal data class OledResponse(
    val exist: Boolean,
    val sleep: Int,
)

@Serializable
internal data class SetOledSleepRequest(
    val sleep: Int,
)

@Serializable
internal data class EnabledResponse(
    val enabled: Boolean,
)

@Serializable
internal data class HostnameResponse(
    val hostname: String,
)

@Serializable
internal data class HostnameRequest(
    val hostname: String,
)

@Serializable
internal data class WebTitleResponse(
    val title: String,
)

@Serializable
internal data class WebTitleRequest(
    val title: String,
)

@Serializable
internal data class DnsResponse(
    val mode: String,
    // The official Go handler emits null for an empty nil slice.
    val servers: List<String>? = null,
    val effective: List<String>? = null,
    val dhcp: List<String>? = null,
    val info: DnsInfoResponse = DnsInfoResponse(),
)

@Serializable
internal data class DnsInfoResponse(
    @SerialName("interface") val interfaceName: String = "",
    val type: String = "",
    val address: String = "",
    val subnetMask: String = "",
    val gateway: String = "",
    val searchDomains: List<String>? = null,
)

@Serializable
internal data class DnsRequest(
    val mode: String,
    val servers: List<String>,
)

internal fun AccountResponse.toModel(): NanoKvmAccount =
    NanoKvmAccount(username = boundedText("account username", username, MAX_ACCOUNT_BYTES, false))

internal fun PasswordStatusResponse.toModel(): NanoKvmPasswordStatus =
    NanoKvmPasswordStatus(isUpdated = isUpdated)

internal fun ApplicationVersionResponse.toModel(): NanoKvmApplicationVersions {
    val boundedCurrent = boundedText("current application version", current, MAX_VERSION_BYTES, false)
    val boundedLatest =
        latest.takeIf { it.isNotEmpty() }?.let {
            boundedText("latest application version", it, MAX_VERSION_BYTES, false)
        }
    return NanoKvmApplicationVersions(
        current = boundedCurrent,
        latest = boundedLatest,
        currentVersion = NanoKvmApplicationVersion.parse(boundedCurrent),
        latestVersion = boundedLatest?.let(NanoKvmApplicationVersion::parse),
    )
}

internal fun PreviewUpdatesResponse.toModel(): NanoKvmPreviewUpdates =
    NanoKvmPreviewUpdates(enabled = enabled)

internal fun OledResponse.toModel(): NanoKvmOledConfiguration {
    if (sleep !in 0..MAX_FORWARD_OLED_SLEEP_SECONDS) {
        throw invalidAdministrationData("OLED sleep is outside the supported response bound")
    }
    return NanoKvmOledConfiguration(
        exists = exist,
        sleepSeconds = sleep,
        sleepPreset = NanoKvmOledSleepPreset.fromSeconds(sleep),
    )
}

internal fun EnabledResponse.toSshModel(): NanoKvmSshState = NanoKvmSshState(enabled)

internal fun EnabledResponse.toMdnsModel(): NanoKvmMdnsState = NanoKvmMdnsState(enabled)

internal fun HostnameResponse.toModel(): NanoKvmHostname =
    NanoKvmHostname(
        value = boundedText(
            "reported hostname",
            hostname,
            MAX_REPORTED_HOSTNAME_BYTES,
            allowEmpty = false,
        ),
    )

internal fun WebTitleResponse.toModel(): NanoKvmWebTitle {
    val bounded = boundedText("web title", title, MAX_WEB_TITLE_BYTES, true)
    val isDefault = bounded.isEmpty() || bounded == NanoKvmWebTitle.DEFAULT
    return NanoKvmWebTitle(
        value = if (isDefault) NanoKvmWebTitle.DEFAULT else bounded,
        isDefault = isDefault,
    )
}

internal fun DnsResponse.toModel(): NanoKvmDnsConfiguration {
    val boundedMode = boundedText("DNS mode", mode, MAX_DNS_MODE_BYTES, false)
    val parsedMode =
        when (boundedMode) {
            NanoKvmDnsMode.Dhcp.wireValue -> NanoKvmDnsMode.Dhcp
            NanoKvmDnsMode.Manual.wireValue -> NanoKvmDnsMode.Manual
            else -> NanoKvmDnsMode.Other(boundedMode)
        }
    return NanoKvmDnsConfiguration(
        mode = parsedMode,
        servers = parseResponseAddresses(
            "configured DNS servers",
            servers.orEmpty(),
            MAX_CONFIGURED_DNS_SERVERS,
        ),
        effectiveServers = parseResponseAddresses(
            "effective DNS servers",
            effective.orEmpty(),
            MAX_RESPONSE_DNS_SERVERS,
        ),
        dhcpServers = parseResponseAddresses(
            "DHCP DNS servers",
            dhcp.orEmpty(),
            MAX_RESPONSE_DNS_SERVERS,
        ),
        info = info.toModel(),
    )
}

private fun DnsInfoResponse.toModel(): NanoKvmDnsInfo {
    val normalizedSearchDomains = searchDomains.orEmpty()
    if (normalizedSearchDomains.size > MAX_SEARCH_DOMAINS) {
        throw invalidAdministrationData("DNS search domain count exceeds $MAX_SEARCH_DOMAINS")
    }
    return NanoKvmDnsInfo(
        interfaceName = boundedText("DNS interface", interfaceName, MAX_NETWORK_FIELD_BYTES, true),
        type = boundedText("DNS network type", type, MAX_NETWORK_FIELD_BYTES, true),
        address = boundedText("DNS address", address, MAX_NETWORK_FIELD_BYTES, true),
        subnetMask = boundedText("DNS subnet mask", subnetMask, MAX_NETWORK_FIELD_BYTES, true),
        gateway = boundedText("DNS gateway", gateway, MAX_NETWORK_FIELD_BYTES, true),
        searchDomains =
            normalizedSearchDomains.mapIndexed { index, domain ->
                boundedText("DNS search domain $index", domain, MAX_NETWORK_FIELD_BYTES, false)
            },
    )
}

internal fun validateChangedUsername(username: String): String {
    val bounded = boundedTextArgument("username", username, MAX_ACCOUNT_BYTES, false)
    if (bounded != bounded.trim()) {
        throw IllegalArgumentException("username must not have leading or trailing whitespace")
    }
    if (bounded.any { it in INVALID_ACCOUNT_CHARACTERS }) {
        throw IllegalArgumentException("username contains a character rejected by the NanoKVM WebUI")
    }
    return bounded
}

internal fun validateChangedPassword(password: CharArray) {
    if (password.isEmpty()) {
        throw IllegalArgumentException("password must not be empty")
    }
    if (password.size > MAX_CHANGED_PASSWORD_CHARS) {
        throw IllegalArgumentException("password exceeds $MAX_CHANGED_PASSWORD_CHARS characters")
    }
    var utf8Bytes = 0
    var index = 0
    while (index < password.size) {
        val character = password[index]
        utf8Bytes +=
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= password.size || !password[index + 1].isLowSurrogate()) {
                        throw IllegalArgumentException("password contains invalid text")
                    }
                    index += 1
                    4
                }
                character.isLowSurrogate() ->
                    throw IllegalArgumentException("password contains invalid text")
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
        if (utf8Bytes > MAX_CHANGED_PASSWORD_UTF8_BYTES) {
            throw IllegalArgumentException(
                "password exceeds $MAX_CHANGED_PASSWORD_UTF8_BYTES UTF-8 bytes",
            )
        }
        index += 1
    }
    if (password.any { it in INVALID_ACCOUNT_CHARACTERS }) {
        throw IllegalArgumentException("password contains a character rejected by the NanoKVM WebUI")
    }
}

internal fun validateHostname(hostname: String): String {
    if (hostname.isEmpty()) throw IllegalArgumentException("hostname must not be empty")
    if (hostname.length > MAX_HOSTNAME_BYTES || hostname.encodeToByteArray().size > MAX_HOSTNAME_BYTES) {
        throw IllegalArgumentException("hostname exceeds $MAX_HOSTNAME_BYTES bytes")
    }
    if (!HOSTNAME_PATTERN.matches(hostname)) {
        throw IllegalArgumentException(
            "hostname must be a single ASCII label with alphanumeric ends and optional interior hyphens",
        )
    }
    return hostname
}

internal fun validateWebTitle(title: String): String {
    val bounded = boundedTextArgument("web title", title, MAX_WEB_TITLE_BYTES, false)
    if (bounded == NanoKvmWebTitle.DEFAULT) {
        throw IllegalArgumentException("use resetWebTitle() to restore the NanoKVM default title")
    }
    return bounded
}

internal fun validateManualDnsServers(
    servers: List<NanoKvmIpAddress>,
): List<NanoKvmIpAddress> {
    if (servers.isEmpty()) {
        throw IllegalArgumentException("manual DNS requires at least one server")
    }
    if (servers.size > MAX_CONFIGURED_DNS_SERVERS) {
        throw IllegalArgumentException("manual DNS accepts at most $MAX_CONFIGURED_DNS_SERVERS servers")
    }
    if (servers.distinct().size != servers.size) {
        throw IllegalArgumentException("manual DNS servers must be unique")
    }
    return servers.toList()
}

private fun parseResponseAddresses(
    field: String,
    values: List<String>,
    maximum: Int,
): List<NanoKvmIpAddress> {
    if (values.size > maximum) {
        throw invalidAdministrationData("$field count exceeds $maximum")
    }
    return values.mapIndexed { index, value ->
        try {
            NanoKvmIpAddress.parse(value)
        } catch (exception: IllegalArgumentException) {
            throw invalidAdministrationData("$field contains an invalid IP literal at index $index", exception)
        }
    }
}

private fun canonicalIpLiteral(value: String): String {
    if (value.isEmpty() || value != value.trim() || value.any(Char::isISOControl)) {
        throw IllegalArgumentException("IP address must be a plain IPv4 or IPv6 literal")
    }
    parseIpv4(value)?.let { return it }
    if (':' !in value || '%' in value || value.length > MAX_IP_LITERAL_CHARS) {
        throw IllegalArgumentException("invalid IPv4 or IPv6 literal: $value")
    }
    val parsed =
        runCatching { InetAddress.getByName(value) }
            .getOrElse { throw IllegalArgumentException("invalid IPv6 literal: $value", it) }
    if (parsed !is Inet6Address) {
        throw IllegalArgumentException("invalid IPv6 literal: $value")
    }
    return canonicalIpv6(parsed.address)
}

private fun parseIpv4(value: String): String? {
    if (value.any { it != '.' && !it.isDigit() }) return null
    val parts = value.split('.')
    if (parts.size != 4) return null
    val octets =
        parts.map { part ->
            if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            octet
        }
    return octets.joinToString(".")
}

private fun canonicalIpv6(bytes: ByteArray): String {
    val groups =
        IntArray(8) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or
                (bytes[index * 2 + 1].toInt() and 0xff)
        }
    var bestStart = -1
    var bestLength = 0
    var index = 0
    while (index < groups.size) {
        if (groups[index] != 0) {
            index += 1
            continue
        }
        val start = index
        while (index < groups.size && groups[index] == 0) index += 1
        val length = index - start
        if (length >= 2 && length > bestLength) {
            bestStart = start
            bestLength = length
        }
    }
    if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
    val prefix = groups.take(bestStart).joinToString(":") { it.toString(16) }
    val suffix = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return when {
        prefix.isEmpty() && suffix.isEmpty() -> "::"
        prefix.isEmpty() -> "::$suffix"
        suffix.isEmpty() -> "$prefix::"
        else -> "$prefix::$suffix"
    }
}

private fun boundedText(
    field: String,
    value: String,
    maximumBytes: Int,
    allowEmpty: Boolean,
): String {
    if (!allowEmpty && value.isEmpty()) throw invalidAdministrationData("$field must not be empty")
    if (value.encodeToByteArray().size > maximumBytes) {
        throw invalidAdministrationData("$field exceeds $maximumBytes bytes")
    }
    if (value.any(Char::isISOControl)) throw invalidAdministrationData("$field contains a control character")
    return value
}

private fun boundedTextArgument(
    field: String,
    value: String,
    maximumBytes: Int,
    allowEmpty: Boolean,
): String {
    if (!allowEmpty && value.isEmpty()) throw IllegalArgumentException("$field must not be empty")
    if (value.encodeToByteArray().size > maximumBytes) {
        throw IllegalArgumentException("$field exceeds $maximumBytes bytes")
    }
    if (value.any(Char::isISOControl)) throw IllegalArgumentException("$field contains a control character")
    return value
}

private fun invalidAdministrationData(
    message: String,
    cause: Throwable? = null,
): InvalidApiResponseException =
    InvalidApiResponseException(
        "NanoKVM returned invalid administration response data: $message",
        cause,
    )

private val INVALID_ACCOUNT_CHARACTERS: Set<Char> = setOf('\'', '"', '\\', '/')
private val HOSTNAME_PATTERN: Regex = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
private const val MAX_ACCOUNT_BYTES: Int = 256
private const val MAX_CHANGED_PASSWORD_CHARS: Int = 256
private const val MAX_CHANGED_PASSWORD_UTF8_BYTES: Int = 256
private const val MAX_VERSION_BYTES: Int = 64
private const val MAX_FORWARD_OLED_SLEEP_SECONDS: Int = 86_400
private const val MAX_HOSTNAME_BYTES: Int = 63
private const val MAX_REPORTED_HOSTNAME_BYTES: Int = 253
private const val MAX_WEB_TITLE_BYTES: Int = 256
private const val MAX_DNS_MODE_BYTES: Int = 32
private const val MAX_CONFIGURED_DNS_SERVERS: Int = 6
private const val MAX_RESPONSE_DNS_SERVERS: Int = 16
private const val MAX_SEARCH_DOMAINS: Int = 16
private const val MAX_NETWORK_FIELD_BYTES: Int = 256
private const val MAX_IP_LITERAL_CHARS: Int = 64
