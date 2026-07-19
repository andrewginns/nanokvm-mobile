package org.nanokvm.protocol

import java.io.Closeable
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A validated Wi-Fi SSID. It is exposed explicitly but redacted from incidental string output. */
public class NanoKvmWifiSsid private constructor(
    public val value: String,
) {
    override fun equals(other: Any?): Boolean = other is NanoKvmWifiSsid && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "<redacted Wi-Fi SSID>"

    public companion object {
        @JvmStatic
        public fun parse(value: String): NanoKvmWifiSsid {
            require(value.isNotEmpty() && value.networkUtf8Size() <= MAX_WIFI_SSID_UTF8_BYTES) {
                "Wi-Fi SSID must contain 1..$MAX_WIFI_SSID_UTF8_BYTES UTF-8 bytes"
            }
            require(value.none(Char::isISOControl)) {
                "Wi-Fi SSID must not contain control characters"
            }
            return NanoKvmWifiSsid(value)
        }
    }
}

/** Read-only Wi-Fi information returned by NanoKVM's authenticated status route. */
@ConsistentCopyVisibility
public data class NanoKvmWifiStatus internal constructor(
    public val supported: Boolean,
    public val accessPointMode: Boolean,
    public val connected: Boolean,
    public val ssid: NanoKvmWifiSsid?,
) {
    override fun toString(): String =
        "NanoKvmWifiStatus(supported=$supported, accessPointMode=$accessPointMode, " +
            "connected=$connected, ssid=<redacted>)"
}

/**
 * A single-use Wi-Fi connection request.
 *
 * Ownership of [password] transfers to this object. It is cleared immediately after request
 * serialization (or by [close]) and never appears in [toString]. NanoKVM 2.4.3 accepts a manual
 * SSID only; it exposes no network-scan endpoint.
 */
public class NanoKvmWifiCredentials(
    ssid: String,
    password: CharArray,
) : Closeable {
    private val mutablePassword: CharArray = password
    public val ssid: NanoKvmWifiSsid
    private var consumed: Boolean = false

    init {
        try {
            this.ssid = NanoKvmWifiSsid.parse(ssid)
            validateWifiSecret(
                value = password,
                label = "Wi-Fi password",
                maximumCharacters = MAX_WIFI_PASSWORD_CHARS,
                maximumUtf8Bytes = MAX_WIFI_PASSWORD_UTF8_BYTES,
            )
        } catch (error: IllegalArgumentException) {
            mutablePassword.fill('\u0000')
            throw error
        }
    }

    internal fun consumeJson(json: Json): String = synchronized(this) {
        check(!consumed) { "Wi-Fi credentials have already been consumed" }
        consumed = true
        try {
            json.encodeToString(
                WifiConnectRequest(
                    ssid = ssid.value,
                    password = mutablePassword.concatToString(),
                ),
            )
        } finally {
            mutablePassword.fill('\u0000')
        }
    }

    override fun close() {
        synchronized(this) {
            consumed = true
            mutablePassword.fill('\u0000')
        }
    }

    override fun toString(): String =
        "NanoKvmWifiCredentials(ssid=$ssid, password=<redacted>, consumed=$consumed)"
}

/**
 * One verified AP-mode password, bound to the API instance that performed verification.
 *
 * The mutable password is retained only until one AP-mode connection attempt. Call [close] when
 * onboarding is abandoned. Its string form is always redacted.
 */
public class NanoKvmWifiAccessPointAuthorization internal constructor(
    private val owner: Any,
    private val mutableApPassword: CharArray,
) : Closeable {
    private var consumed: Boolean = false

    internal fun verificationHeader(requiredOwner: Any): String = synchronized(this) {
        check(!consumed) { "Wi-Fi AP authorization has already been consumed" }
        require(owner === requiredOwner) {
            "Wi-Fi AP authorization belongs to a different NanoKVM API"
        }
        mutableApPassword.concatToString()
    }

    internal fun consumeHeader(requiredOwner: Any): String = synchronized(this) {
        check(!consumed) { "Wi-Fi AP authorization has already been consumed" }
        require(owner === requiredOwner) {
            "Wi-Fi AP authorization belongs to a different NanoKVM API"
        }
        consumed = true
        try {
            mutableApPassword.concatToString()
        } finally {
            mutableApPassword.fill('\u0000')
        }
    }

    override fun close() {
        synchronized(this) {
            consumed = true
            mutableApPassword.fill('\u0000')
        }
    }

    override fun toString(): String =
        "NanoKvmWifiAccessPointAuthorization(password=<redacted>, consumed=$consumed)"
}

public enum class NanoKvmWifiOperation {
    VERIFY_ACCESS_POINT_PASSWORD,
    CONNECT_IN_ACCESS_POINT_MODE,
    CONNECT_AUTHENTICATED,
    DISCONNECT_AUTHENTICATED,
}

/** A Wi-Fi failure that deliberately discards server response text which could echo credentials. */
public class NanoKvmWifiOperationException internal constructor(
    public val operation: NanoKvmWifiOperation,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
) : NanoKvmException(
    "NanoKVM rejected Wi-Fi operation $operation" +
        (apiCode?.let { " (API $it)" } ?: httpStatus?.let { " (HTTP $it)" }.orEmpty()),
)

/** States emitted by NanoKVM 2.4.3; bounded future values remain readable but are not writable. */
public sealed interface NanoKvmTailscaleState {
    public val wireValue: String

    public data object NotInstalled : NanoKvmTailscaleState {
        override val wireValue: String = "notInstall"
    }

    public data object NotRunning : NanoKvmTailscaleState {
        override val wireValue: String = "notRunning"
    }

    public data object NotLoggedIn : NanoKvmTailscaleState {
        override val wireValue: String = "notLogin"
    }

    public data object Stopped : NanoKvmTailscaleState {
        override val wireValue: String = "stopped"
    }

    public data object Running : NanoKvmTailscaleState {
        override val wireValue: String = "running"
    }

    @ConsistentCopyVisibility
    public data class Other internal constructor(
        override val wireValue: String,
    ) : NanoKvmTailscaleState
}

/** A latest-status snapshot used as the state gate for all Tailscale writes. */
@ConsistentCopyVisibility
public data class NanoKvmTailscaleStatus internal constructor(
    public val state: NanoKvmTailscaleState,
    public val deviceName: String?,
    public val ipv4: NanoKvmIpAddress?,
    public val account: String?,
) {
    override fun toString(): String =
        "NanoKvmTailscaleStatus(state=$state, identity=<redacted>)"
}

/** Exact, distinct Tailscale commands in NanoKVM 2.4.3. */
public enum class NanoKvmTailscaleCommand private constructor(
    internal val path: String,
) {
    INSTALL("/api/extensions/tailscale/install"),
    UNINSTALL("/api/extensions/tailscale/uninstall"),
    START("/api/extensions/tailscale/start"),
    STOP("/api/extensions/tailscale/stop"),
    RESTART("/api/extensions/tailscale/restart"),
    UP("/api/extensions/tailscale/up"),
    DOWN("/api/extensions/tailscale/down"),
    LOGIN("/api/extensions/tailscale/login"),
    LOGOUT("/api/extensions/tailscale/logout"),
}

/**
 * Explicit, single-use acknowledgement of one Tailscale side effect, bound to a status snapshot.
 * Install downloads executable code; start/restart/login/up can create outbound connectivity;
 * stop/down interrupt connectivity; logout changes account state; uninstall removes binaries.
 */
public class NanoKvmTailscaleActionApproval private constructor(
    private val status: NanoKvmTailscaleStatus,
    private val command: NanoKvmTailscaleCommand,
) {
    private var consumed: Boolean = false

    internal fun consume(expected: NanoKvmTailscaleCommand): NanoKvmTailscaleStatus =
        synchronized(this) {
            check(!consumed) { "Tailscale action approval has already been consumed" }
            require(command == expected) {
                "Tailscale action approval does not match the requested command"
            }
            consumed = true
            status
        }

    override fun toString(): String =
        "NanoKvmTailscaleActionApproval(command=$command, status=<redacted>, consumed=$consumed)"

    public companion object {
        /** Create only after the UI has disclosed and the user has confirmed [command]'s effect. */
        @JvmStatic
        public fun afterUserConfirmed(
            status: NanoKvmTailscaleStatus,
            command: NanoKvmTailscaleCommand,
        ): NanoKvmTailscaleActionApproval = NanoKvmTailscaleActionApproval(status, command)
    }
}

/** A short-lived official Tailscale authorization URL. Its auth token is redacted from strings. */
public class NanoKvmTailscaleLoginUrl private constructor(
    public val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is NanoKvmTailscaleLoginUrl && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "<redacted Tailscale authorization URL>"

    internal companion object {
        fun fromServer(value: String): NanoKvmTailscaleLoginUrl {
            require(value.networkUtf8Size() <= MAX_TAILSCALE_LOGIN_URL_UTF8_BYTES) {
                "Tailscale login URL is too long"
            }
            val uri = try {
                URI(value)
            } catch (error: URISyntaxException) {
                throw IllegalArgumentException("Tailscale login URL is invalid", error)
            }
            require(
                uri.scheme == "https" &&
                    uri.host.equals(TAILSCALE_LOGIN_HOST, ignoreCase = true) &&
                    (uri.port == -1 || uri.port == 443) &&
                    uri.userInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null,
            ) { "Tailscale login URL is outside the official HTTPS origin" }
            require(TAILSCALE_LOGIN_PATH.matches(uri.rawPath.orEmpty())) {
                "Tailscale login URL has an unexpected path"
            }
            return NanoKvmTailscaleLoginUrl(value)
        }
    }
}

public sealed interface NanoKvmTailscaleLoginResult {
    public data object AlreadyAuthenticated : NanoKvmTailscaleLoginResult

    @ConsistentCopyVisibility
    public data class AuthorizationRequired internal constructor(
        public val url: NanoKvmTailscaleLoginUrl,
    ) : NanoKvmTailscaleLoginResult {
        override fun toString(): String =
            "NanoKvmTailscaleLoginResult.AuthorizationRequired(url=<redacted>)"
    }
}

/** A Tailscale failure that never retains server response text or an authorization URL. */
public class NanoKvmTailscaleOperationException internal constructor(
    public val command: NanoKvmTailscaleCommand,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
) : NanoKvmException(
    "NanoKVM rejected Tailscale command $command" +
        (apiCode?.let { " (API $it)" } ?: httpStatus?.let { " (HTTP $it)" }.orEmpty()),
)

@Serializable
internal data class WifiStatusResponse(
    val supported: Boolean,
    val apMode: Boolean,
    val connected: Boolean,
    val ssid: String = "",
)

@Serializable
internal data class WifiConnectRequest(
    val ssid: String,
    val password: String,
)

@Serializable
internal data class TailscaleStatusResponse(
    val state: String,
    val name: String = "",
    val ip: String = "",
    val account: String = "",
)

@Serializable
internal data class TailscaleLoginResponse(
    val url: String = "",
)

internal fun WifiStatusResponse.toValidatedModel(): NanoKvmWifiStatus =
    networkInvalidServerData("Wi-Fi status") {
        NanoKvmWifiStatus(
            supported = supported,
            accessPointMode = apMode,
            connected = connected,
            ssid = ssid.takeIf(String::isNotEmpty)?.let(NanoKvmWifiSsid::parse),
        )
    }

internal fun TailscaleStatusResponse.toValidatedModel(): NanoKvmTailscaleStatus =
    networkInvalidServerData("Tailscale status") {
        require(
            state.isNotEmpty() &&
                state.networkUtf8Size() <= MAX_TAILSCALE_STATE_UTF8_BYTES &&
                state.none(Char::isISOControl),
        ) { "Tailscale state is blank, too long, or contains controls" }
        val typedState = when (state) {
            NanoKvmTailscaleState.NotInstalled.wireValue -> NanoKvmTailscaleState.NotInstalled
            NanoKvmTailscaleState.NotRunning.wireValue -> NanoKvmTailscaleState.NotRunning
            NanoKvmTailscaleState.NotLoggedIn.wireValue -> NanoKvmTailscaleState.NotLoggedIn
            NanoKvmTailscaleState.Stopped.wireValue -> NanoKvmTailscaleState.Stopped
            NanoKvmTailscaleState.Running.wireValue -> NanoKvmTailscaleState.Running
            else -> NanoKvmTailscaleState.Other(state)
        }
        val ipv4 = ip.networkOptionalBounded("Tailscale IPv4", MAX_TAILSCALE_IP_UTF8_BYTES)
            ?.let(NanoKvmIpAddress::parse)
            ?.also { require(':' !in it.value) { "Tailscale status returned a non-IPv4 address" } }
        NanoKvmTailscaleStatus(
            state = typedState,
            deviceName = name.networkOptionalBounded(
                "Tailscale device name",
                MAX_TAILSCALE_NAME_UTF8_BYTES,
            ),
            ipv4 = ipv4,
            account = account.networkOptionalBounded(
                "Tailscale account",
                MAX_TAILSCALE_ACCOUNT_UTF8_BYTES,
            ),
        )
    }

internal fun TailscaleLoginResponse.toValidatedModel(): NanoKvmTailscaleLoginResult =
    networkInvalidServerData("Tailscale login response") {
        if (url.isEmpty()) {
            NanoKvmTailscaleLoginResult.AlreadyAuthenticated
        } else {
            NanoKvmTailscaleLoginResult.AuthorizationRequired(
                NanoKvmTailscaleLoginUrl.fromServer(url),
            )
        }
    }

internal fun NanoKvmTailscaleState.allows(command: NanoKvmTailscaleCommand): Boolean = when (this) {
    NanoKvmTailscaleState.NotInstalled -> command == NanoKvmTailscaleCommand.INSTALL
    NanoKvmTailscaleState.NotRunning ->
        command == NanoKvmTailscaleCommand.START ||
            command == NanoKvmTailscaleCommand.UNINSTALL
    NanoKvmTailscaleState.NotLoggedIn ->
        command == NanoKvmTailscaleCommand.LOGIN ||
            command == NanoKvmTailscaleCommand.STOP ||
            command == NanoKvmTailscaleCommand.RESTART ||
            command == NanoKvmTailscaleCommand.UNINSTALL
    NanoKvmTailscaleState.Stopped ->
        command == NanoKvmTailscaleCommand.UP ||
            command == NanoKvmTailscaleCommand.LOGOUT ||
            command == NanoKvmTailscaleCommand.STOP ||
            command == NanoKvmTailscaleCommand.RESTART ||
            command == NanoKvmTailscaleCommand.UNINSTALL
    NanoKvmTailscaleState.Running ->
        command == NanoKvmTailscaleCommand.DOWN ||
            command == NanoKvmTailscaleCommand.LOGOUT ||
            command == NanoKvmTailscaleCommand.STOP ||
            command == NanoKvmTailscaleCommand.RESTART ||
            command == NanoKvmTailscaleCommand.UNINSTALL
    is NanoKvmTailscaleState.Other -> false
}

internal suspend inline fun <T> wifiOperation(
    operation: NanoKvmWifiOperation,
    crossinline block: suspend () -> T,
): T = try {
    block()
} catch (error: ApiResponseException) {
    throw NanoKvmWifiOperationException(operation = operation, apiCode = error.code)
} catch (error: HttpResponseException) {
    throw NanoKvmWifiOperationException(operation = operation, httpStatus = error.statusCode)
}

internal suspend inline fun <T> tailscaleOperation(
    command: NanoKvmTailscaleCommand,
    crossinline block: suspend () -> T,
): T = try {
    block()
} catch (error: ApiResponseException) {
    throw NanoKvmTailscaleOperationException(command = command, apiCode = error.code)
} catch (error: HttpResponseException) {
    throw NanoKvmTailscaleOperationException(command = command, httpStatus = error.statusCode)
}

private fun validateWifiSecret(
    value: CharArray,
    label: String,
    maximumCharacters: Int,
    maximumUtf8Bytes: Int,
) {
    require(value.isNotEmpty() && value.size <= maximumCharacters) {
        "$label must contain 1..$maximumCharacters characters"
    }
    require(value.networkUtf8Size() <= maximumUtf8Bytes) { "$label is too long" }
    require(value.none(Char::isISOControl) && value.hasValidUtf16()) {
        "$label contains unsupported characters"
    }
}

internal fun validateWifiApPassword(value: CharArray) {
    validateWifiSecret(
        value = value,
        label = "Wi-Fi AP password",
        maximumCharacters = MAX_WIFI_AP_PASSWORD_CHARS,
        maximumUtf8Bytes = MAX_WIFI_AP_PASSWORD_UTF8_BYTES,
    )
}

private fun String.networkOptionalBounded(label: String, maximumUtf8Bytes: Int): String? =
    takeIf(String::isNotEmpty)?.also { value ->
        require(value.networkUtf8Size() <= maximumUtf8Bytes) { "$label is too long" }
        require(value.none(Char::isISOControl)) { "$label contains control characters" }
    }

private inline fun <T> networkInvalidServerData(label: String, block: () -> T): T = try {
    block()
} catch (error: IllegalArgumentException) {
    throw InvalidApiResponseException("NanoKVM returned invalid $label data", error)
}

private fun CharArray.hasValidUtf16(): Boolean {
    var index = 0
    while (index < size) {
        val value = this[index]
        when {
            value.isHighSurrogate() -> {
                if (index + 1 >= size || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            value.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun CharArray.networkUtf8Size(): Int = concatToString().encodeToByteArray().size

private fun String.networkUtf8Size(): Int = encodeToByteArray().size

private const val MAX_WIFI_SSID_UTF8_BYTES = 32
private const val MAX_WIFI_PASSWORD_CHARS = 128
private const val MAX_WIFI_PASSWORD_UTF8_BYTES = 256
private const val MAX_WIFI_AP_PASSWORD_CHARS = 128
private const val MAX_WIFI_AP_PASSWORD_UTF8_BYTES = 256
private const val MAX_TAILSCALE_STATE_UTF8_BYTES = 64
private const val MAX_TAILSCALE_NAME_UTF8_BYTES = 253
private const val MAX_TAILSCALE_IP_UTF8_BYTES = 64
private const val MAX_TAILSCALE_ACCOUNT_UTF8_BYTES = 512
private const val MAX_TAILSCALE_LOGIN_URL_UTF8_BYTES = 2_048
private const val TAILSCALE_LOGIN_HOST = "login.tailscale.com"
private val TAILSCALE_LOGIN_PATH = Regex("/a/[A-Za-z0-9_-]{1,512}")
