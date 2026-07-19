package org.nanokvm.protocol

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PicoClaw first appeared in the official NanoKVM application at 2.4.0. */
private val MINIMUM_PICOCLAW_VERSION = NanoKvmApplicationVersion(2, 4, 0)

/** Exact agent profiles shipped by NanoKVM 2.4.3. */
enum class NanoKvmPicoClawAgentProfile(internal val wireValue: String) {
    DEFAULT("default"),
    KVM("kvm"),
}

/** A provider endpoint accepted by NanoKVM's PicoClaw model configuration. */
@JvmInline
value class NanoKvmPicoClawApiBase private constructor(val value: String) {
    companion object {
        @JvmStatic
        fun parse(value: String): NanoKvmPicoClawApiBase {
            require(value == value.trim() && value.isNotEmpty()) {
                "PicoClaw model API base must not be blank or surrounded by whitespace"
            }
            require(value.picoUtf8Size() <= MAX_PICOCLAW_API_BASE_BYTES) {
                "PicoClaw model API base is too long"
            }
            val uri = try {
                URI(value)
            } catch (error: URISyntaxException) {
                throw IllegalArgumentException("PicoClaw model API base is invalid", error)
            }
            require(
                uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true),
            ) { "PicoClaw model API base must use HTTP or HTTPS" }
            require(!uri.host.isNullOrBlank()) { "PicoClaw model API base must include a host" }
            require(uri.userInfo == null) {
                "PicoClaw model API base must not contain credentials"
            }
            require(uri.query == null && uri.fragment == null) {
                "PicoClaw model API base must not contain a query or fragment"
            }
            return NanoKvmPicoClawApiBase(value)
        }
    }
}

/**
 * One-shot model configuration containing a memory-only mutable provider key.
 *
 * The supplied [CharArray] remains caller-owned but is cleared by [NanoKvmPicoClaw.updateModel]
 * immediately after JSON serialization, including when serialization fails. This object is
 * deliberately single-use and its string form never contains the key.
 */
class NanoKvmPicoClawModelConfiguration(
    val model: String,
    val apiBase: NanoKvmPicoClawApiBase,
    apiKey: CharArray,
) {
    private val mutableApiKey = apiKey
    private var consumed = false

    init {
        require(model == model.trim() && model.isNotEmpty()) {
            "PicoClaw model identifier must not be blank or surrounded by whitespace"
        }
        require(model.picoUtf8Size() <= MAX_PICOCLAW_MODEL_BYTES) {
            "PicoClaw model identifier is too long"
        }
        require(model.none(Char::isISOControl)) {
            "PicoClaw model identifier must not contain control characters"
        }
        require(apiKey.isNotEmpty() && apiKey.size <= MAX_PICOCLAW_API_KEY_CHARS) {
            "PicoClaw provider API key must contain 1..$MAX_PICOCLAW_API_KEY_CHARS characters"
        }
        require(apiKey.none(Char::isISOControl)) {
            "PicoClaw provider API key must not contain control characters"
        }
        require(apiKey.picoUtf8Size() <= MAX_PICOCLAW_API_KEY_BYTES) {
            "PicoClaw provider API key is too long"
        }
    }

    internal fun consumeJson(json: Json): String = synchronized(this) {
        check(!consumed) { "PicoClaw model configuration has already been consumed" }
        consumed = true
        try {
            json.encodeToString(
                PicoClawModelConfigRequest(
                    model = model,
                    apiBase = apiBase.value,
                    apiKey = mutableApiKey.concatToString(),
                ),
            )
        } finally {
            mutableApiKey.fill('\u0000')
        }
    }

    override fun toString(): String =
        "NanoKvmPicoClawModelConfiguration(model=$model, apiBase=$apiBase, apiKey=<redacted>)"
}

/** Explicit acknowledgement that uninstall erases the runtime and all PicoClaw configuration. */
class NanoKvmPicoClawUninstallApproval private constructor() {
    override fun toString(): String = "PicoClaw uninstall and data-erasure approved"

    companion object {
        @JvmStatic
        fun afterUserConfirmedRuntimeAndConfigurationErasure():
            NanoKvmPicoClawUninstallApproval = NanoKvmPicoClawUninstallApproval()
    }
}

/** Explicit acknowledgement that a gateway can control the appliance and attached host. */
class NanoKvmPicoClawControlApproval private constructor() {
    override fun toString(): String = "PicoClaw broad device and host control approved"

    companion object {
        @JvmStatic
        fun afterUserApprovedBroadDeviceAndHostControl(): NanoKvmPicoClawControlApproval =
            NanoKvmPicoClawControlApproval()
    }
}

sealed interface NanoKvmPicoClawRuntimePhase {
    val wireValue: String

    data object Checking : NanoKvmPicoClawRuntimePhase { override val wireValue = "checking" }
    data object Installing : NanoKvmPicoClawRuntimePhase { override val wireValue = "installing" }
    data object Installed : NanoKvmPicoClawRuntimePhase { override val wireValue = "installed" }
    data object Ready : NanoKvmPicoClawRuntimePhase { override val wireValue = "ready" }
    data object Stopped : NanoKvmPicoClawRuntimePhase { override val wireValue = "stopped" }
    data object NotInstalled : NanoKvmPicoClawRuntimePhase { override val wireValue = "not_installed" }
    data object ModelNotConfigured : NanoKvmPicoClawRuntimePhase {
        override val wireValue = "model_not_configured"
    }
    data object ConfigError : NanoKvmPicoClawRuntimePhase { override val wireValue = "config_error" }
    data object Unavailable : NanoKvmPicoClawRuntimePhase { override val wireValue = "unavailable" }
    data object Error : NanoKvmPicoClawRuntimePhase { override val wireValue = "error" }

    @ConsistentCopyVisibility
    data class Other internal constructor(override val wireValue: String) :
        NanoKvmPicoClawRuntimePhase
}

/** A canonical UUID used for the public gateway and runtime-session release contract. */
@JvmInline
value class NanoKvmPicoClawRuntimeSessionId private constructor(val value: String) {
    companion object {
        @JvmStatic
        fun generate(): NanoKvmPicoClawRuntimeSessionId =
            NanoKvmPicoClawRuntimeSessionId(UUID.randomUUID().toString())

        @JvmStatic
        fun parse(value: String): NanoKvmPicoClawRuntimeSessionId {
            require(value == value.trim() && value.length == UUID_TEXT_LENGTH) {
                "PicoClaw runtime session ID must be a canonical UUID"
            }
            val parsed = try {
                UUID.fromString(value)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "PicoClaw runtime session ID must be a canonical UUID",
                    error,
                )
            }
            val canonical = parsed.toString()
            require(canonical.equals(value, ignoreCase = true)) {
                "PicoClaw runtime session ID must be a canonical UUID"
            }
            return NanoKvmPicoClawRuntimeSessionId(canonical)
        }
    }
}

data class NanoKvmPicoClawRuntimeStatus(
    val ready: Boolean,
    val installed: Boolean,
    val installing: Boolean,
    val installProgress: Int?,
    val installStage: String?,
    val installPath: String?,
    val agentProfile: NanoKvmPicoClawAgentProfile?,
    val modelConfigured: Boolean,
    val modelName: String?,
    val phase: NanoKvmPicoClawRuntimePhase,
    val configError: String?,
    val lastError: String?,
    val checkedAt: Instant?,
    val currentSession: NanoKvmPicoClawRuntimeSessionId?,
) {
    /** NanoKVM blocks all manual keyboard/mouse reports while this is true. */
    val manualHidLocked: Boolean
        get() = currentSession != null
}

data class NanoKvmPicoClawRuntimeMutationResult(
    val started: Boolean,
    val command: String,
    val output: String?,
    val status: NanoKvmPicoClawRuntimeStatus,
)

data class NanoKvmPicoClawInstallationResult(
    val installed: Boolean,
    val binary: String,
    val download: String,
    val output: String?,
    val status: NanoKvmPicoClawRuntimeStatus,
)

data class NanoKvmPicoClawAgentProfileResult(
    val profile: NanoKvmPicoClawAgentProfile,
    val status: NanoKvmPicoClawRuntimeStatus,
)

data class NanoKvmPicoClawModelConfigurationResult(
    val modelName: String,
    val status: NanoKvmPicoClawRuntimeStatus,
)

/** An opaque history identity returned by one exact list snapshot. */
class NanoKvmPicoClawHistorySession internal constructor(internal val wireId: String) {
    override fun toString(): String = "<opaque PicoClaw history session>"
}

data class NanoKvmPicoClawHistorySummary(
    val session: NanoKvmPicoClawHistorySession,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "NanoKvmPicoClawHistorySummary(" +
            "session=$session, title=<redacted>, preview=<redacted>, " +
            "messageCount=$messageCount, createdAt=$createdAt, updatedAt=$updatedAt)"
}

/** Immutable page of server-issued history handles. */
class NanoKvmPicoClawHistoryCatalog internal constructor(
    entries: List<NanoKvmPicoClawHistorySummary>,
) {
    val entries: List<NanoKvmPicoClawHistorySummary> = entries.toList()

    internal fun requireExactMember(session: NanoKvmPicoClawHistorySession) {
        require(entries.any { it.session === session }) {
            "History session must be an exact handle from the supplied PicoClaw catalog"
        }
    }
}

enum class NanoKvmPicoClawHistoryRole {
    USER,
    ASSISTANT,
}

data class NanoKvmPicoClawHistoryMessage(
    val role: NanoKvmPicoClawHistoryRole,
    val content: String,
) {
    override fun toString(): String =
        "NanoKvmPicoClawHistoryMessage(role=$role, content=<redacted>)"
}

data class NanoKvmPicoClawHistoryDetail(
    val session: NanoKvmPicoClawHistorySession,
    val messages: List<NanoKvmPicoClawHistoryMessage>,
    val summary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "NanoKvmPicoClawHistoryDetail(" +
            "session=$session, messages=<redacted>, messageCount=${messages.size}, " +
            "summary=<redacted>, createdAt=$createdAt, updatedAt=$updatedAt)"
}

/** Approval bound by identity to one exact server-issued history handle. */
class NanoKvmPicoClawHistoryDeletionApproval private constructor(
    internal val session: NanoKvmPicoClawHistorySession,
) {
    companion object {
        @JvmStatic
        fun afterUserConfirmedPermanentDeletion(
            catalog: NanoKvmPicoClawHistoryCatalog,
            session: NanoKvmPicoClawHistorySession,
        ): NanoKvmPicoClawHistoryDeletionApproval {
            catalog.requireExactMember(session)
            return NanoKvmPicoClawHistoryDeletionApproval(session)
        }
    }
}

data class NanoKvmPicoClawSessionRelease(
    val released: Boolean,
    val currentSession: NanoKvmPicoClawRuntimeSessionId?,
)

class NanoKvmPicoClawApiException(
    val errorCode: String,
    val serverMessage: String,
    val owningSession: NanoKvmPicoClawRuntimeSessionId? = null,
    val actionIndex: Int? = null,
) : NanoKvmException("NanoKVM PicoClaw API error $errorCode")

/**
 * Official NanoKVM 2.4.3 PicoClaw frontend protocol.
 *
 * [enter] performs only a local version check. In particular it does not call runtime status:
 * NanoKVM's status handler can start a permanent probe loop and create PicoClaw configuration.
 * Every mutation is dispatched at most once, with no client retry or reconciliation replay.
 * Loopback-only screenshot, action, MCP, load-image and internal-token routes are intentionally
 * absent and cannot be addressed through this API.
 */
class NanoKvmPicoClaw private constructor(
    private val endpoint: NanoKvmEndpoint,
    private val transport: OkHttpClient,
    private val tokenStore: SessionTokenStore,
    private val webSocketRequest: (String) -> Request,
) {
    private val historyLock = Any()
    private var latestHistoryCatalog: NanoKvmPicoClawHistoryCatalog? = null

    /** Explicit feature-entry probe; this is the first method allowed to call runtime status. */
    suspend fun runtimeStatus(): NanoKvmPicoClawRuntimeStatus =
        get(PICOCLAW_RUNTIME_STATUS_PATH, PicoClawRuntimeStatusResponse.serializer())
            .toValidatedModel()

    suspend fun installRuntime(): NanoKvmPicoClawInstallationResult =
        postEmpty(PICOCLAW_RUNTIME_INSTALL_PATH, PicoClawRuntimeInstallResponse.serializer())
            .toValidatedModel()

    suspend fun uninstallRuntime(
        @Suppress("UNUSED_PARAMETER") approval: NanoKvmPicoClawUninstallApproval,
    ): NanoKvmPicoClawInstallationResult =
        postEmpty(PICOCLAW_RUNTIME_UNINSTALL_PATH, PicoClawRuntimeInstallResponse.serializer())
            .toValidatedModel()

    suspend fun startRuntime(): NanoKvmPicoClawRuntimeMutationResult =
        postEmpty(PICOCLAW_RUNTIME_START_PATH, PicoClawRuntimeMutationResponse.serializer())
            .toValidatedModel(expectedStarted = true)

    suspend fun stopRuntime(): NanoKvmPicoClawRuntimeMutationResult =
        postEmpty(PICOCLAW_RUNTIME_STOP_PATH, PicoClawRuntimeMutationResponse.serializer())
            .toValidatedModel(expectedStarted = false)

    suspend fun setAgentProfile(
        profile: NanoKvmPicoClawAgentProfile,
    ): NanoKvmPicoClawAgentProfileResult {
        val result = postJson(
            PICOCLAW_AGENT_PROFILE_PATH,
            JSON.encodeToString(PicoClawAgentProfileRequest(profile.wireValue)),
            PicoClawAgentProfileResponse.serializer(),
        ).toValidatedModel()
        if (result.profile != profile) {
            throw InvalidApiResponseException("NanoKVM returned a different PicoClaw agent profile")
        }
        return result
    }

    /** Consumes and clears [configuration]'s mutable key immediately after serialization. */
    suspend fun updateModel(
        configuration: NanoKvmPicoClawModelConfiguration,
    ): NanoKvmPicoClawModelConfigurationResult {
        val serialized = configuration.consumeJson(JSON)
        return postJson(
            PICOCLAW_MODEL_CONFIG_PATH,
            serialized,
            PicoClawModelConfigResponse.serializer(),
        ).toValidatedModel()
    }

    suspend fun histories(
        offset: Int = 0,
        limit: Int = DEFAULT_PICOCLAW_HISTORY_LIMIT,
    ): NanoKvmPicoClawHistoryCatalog {
        require(offset in 0..MAX_PICOCLAW_HISTORY_OFFSET) { "History offset is out of range" }
        require(limit in 1..MAX_PICOCLAW_HISTORY_PAGE_SIZE) {
            "History limit must be in 1..$MAX_PICOCLAW_HISTORY_PAGE_SIZE"
        }
        val url = endpoint.apiUrl(PICOCLAW_SESSIONS_PATH).newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val response = executeData(
            Request.Builder().url(url).header("Accept", "application/json").get().build(),
            PicoClawHistoryListResponseSerializer,
        )
        val catalog = response.toValidatedCatalog(limit)
        synchronized(historyLock) { latestHistoryCatalog = catalog }
        return catalog
    }

    suspend fun history(
        catalog: NanoKvmPicoClawHistoryCatalog,
        session: NanoKvmPicoClawHistorySession,
    ): NanoKvmPicoClawHistoryDetail {
        requireLatestHistory(catalog, session)
        val response = get(
            "$PICOCLAW_SESSIONS_PATH/${session.wireId}",
            PicoClawHistoryDetailResponse.serializer(),
        )
        return response.toValidatedModel(session)
    }

    suspend fun deleteHistory(
        catalog: NanoKvmPicoClawHistoryCatalog,
        session: NanoKvmPicoClawHistorySession,
        approval: NanoKvmPicoClawHistoryDeletionApproval,
    ) {
        requireLatestHistory(catalog, session)
        require(approval.session === session) {
            "History deletion approval is not bound to the selected session"
        }
        val result = delete(
            "$PICOCLAW_SESSIONS_PATH/${session.wireId}",
            PicoClawHistoryDeleteResponse.serializer(),
        )
        picoInvalidServerData("history deletion response") {
            require(result.deleted && result.id == session.wireId) {
                "PicoClaw did not confirm deletion of the selected history"
            }
        }
        synchronized(historyLock) { latestHistoryCatalog = null }
    }

    suspend fun releaseRuntimeSession(
        session: NanoKvmPicoClawRuntimeSessionId,
    ): NanoKvmPicoClawSessionRelease {
        val request = requestBuilder(PICOCLAW_RUNTIME_SESSION_PATH)
            .header(PICOCLAW_SESSION_ID_HEADER, session.value)
            .delete()
            .build()
        return executeData(request, PicoClawSessionReleaseResponse.serializer())
            .toValidatedModel()
    }

    fun newGateway(
        generation: Long,
        @Suppress("UNUSED_PARAMETER") approval: NanoKvmPicoClawControlApproval,
        session: NanoKvmPicoClawRuntimeSessionId = NanoKvmPicoClawRuntimeSessionId.generate(),
    ): NanoKvmPicoClawGateway {
        require(generation >= 0) { "PicoClaw gateway generation must be non-negative" }
        return NanoKvmPicoClawGateway(
            endpoint = endpoint,
            transport = transport,
            requestFactory = webSocketRequest,
            session = session,
            generation = generation,
            releaseSession = ::releaseRuntimeSession,
        )
    }

    private fun requireLatestHistory(
        catalog: NanoKvmPicoClawHistoryCatalog,
        session: NanoKvmPicoClawHistorySession,
    ) {
        synchronized(historyLock) {
            require(latestHistoryCatalog === catalog) {
                "History catalog must be the latest page returned by this PicoClaw API"
            }
            catalog.requireExactMember(session)
        }
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        executeData(requestBuilder(path).get().build(), serializer)

    private suspend fun <T> delete(path: String, serializer: KSerializer<T>): T =
        executeData(requestBuilder(path).delete().build(), serializer)

    private suspend fun <T> postEmpty(path: String, serializer: KSerializer<T>): T = executeData(
        requestBuilder(path).post(EMPTY_JSON_REQUEST_BODY).build(),
        serializer,
    )

    private suspend fun <T> postJson(
        path: String,
        body: String,
        serializer: KSerializer<T>,
    ): T = executeData(
        requestBuilder(path).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
        serializer,
    )

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(endpoint.apiUrl(path))
        .header("Accept", "application/json")

    private suspend fun <T> executeData(request: Request, serializer: KSerializer<T>): T {
        val response = transport.newCall(request).picoAwait()
        response.use {
            if (it.code == 401) {
                synchronized(historyLock) { latestHistoryCatalog = null }
                tokenStore.write(null)
                throw AuthenticationExpiredException()
            }
            val body = it.body.picoReadUtf8WithinLimit()
            val root = try {
                JSON.parseToJsonElement(body) as? JsonObject
                    ?: throw InvalidApiResponseException("NanoKVM returned a non-object response")
            } catch (error: SerializationException) {
                throw InvalidApiResponseException("NanoKVM returned invalid PicoClaw JSON", error)
            }

            root.picoclawErrorOrNull()?.let { error -> throw error }
            if (!it.isSuccessful) {
                throw HttpResponseException(it.code)
            }
            val codePrimitive = root["code"] as? JsonPrimitive
            val code = codePrimitive?.takeUnless { it.isString }?.intOrNull
                ?: throw InvalidApiResponseException("NanoKVM PicoClaw response omitted numeric code")
            if (code != 0) {
                val message = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                throw ApiResponseException(code, message.picoTakeUtf8(MAX_PICOCLAW_ERROR_BYTES))
            }
            val data = root["data"]
                ?: throw InvalidApiResponseException("Successful PicoClaw response contained no data")
            return try {
                JSON.decodeFromJsonElement(serializer, data)
            } catch (error: SerializationException) {
                throw InvalidApiResponseException("NanoKVM returned invalid PicoClaw data", error)
            }
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON_REQUEST_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)

        /** Creates a local feature entry. No network request occurs here. */
        @JvmStatic
        fun enter(
            client: NanoKvmClient,
            applicationVersion: NanoKvmApplicationVersion,
        ): NanoKvmPicoClaw {
            require(applicationVersion >= MINIMUM_PICOCLAW_VERSION) {
                "PicoClaw requires NanoKVM application $MINIMUM_PICOCLAW_VERSION or newer"
            }
            return NanoKvmPicoClaw(
                endpoint = client.endpoint,
                transport = client.transport,
                tokenStore = client.tokenStore,
                webSocketRequest = client::webSocketRequest,
            )
        }
    }
}

@Serializable
private data class PicoClawRuntimeStatusResponse(
    val ready: Boolean,
    val installed: Boolean,
    val installing: Boolean,
    @SerialName("install_progress") val installProgress: Int? = null,
    @SerialName("install_stage") val installStage: String? = null,
    @SerialName("install_path") val installPath: String? = null,
    @SerialName("agent_profile") val agentProfile: String? = null,
    @SerialName("model_configured") val modelConfigured: Boolean,
    @SerialName("model_name") val modelName: String? = null,
    val status: String,
    @SerialName("config_error") val configError: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    @SerialName("current_session") val currentSession: String? = null,
)

@Serializable
private data class PicoClawRuntimeMutationResponse(
    val started: Boolean,
    val command: String,
    val output: String? = null,
    val status: PicoClawRuntimeStatusResponse,
)

@Serializable
private data class PicoClawRuntimeInstallResponse(
    val installed: Boolean,
    val binary: String,
    val download: String,
    val output: String? = null,
    val status: PicoClawRuntimeStatusResponse,
)

@Serializable
private data class PicoClawAgentProfileRequest(val profile: String)

@Serializable
private data class PicoClawAgentProfileResponse(
    val profile: String,
    val status: PicoClawRuntimeStatusResponse,
)

@Serializable
private data class PicoClawModelConfigRequest(
    val model: String,
    @SerialName("api_base") val apiBase: String,
    @SerialName("api_key") val apiKey: String,
) {
    override fun toString(): String =
        "PicoClawModelConfigRequest(model=$model, apiBase=$apiBase, apiKey=<redacted>)"
}

@Serializable
private data class PicoClawModelConfigResponse(
    @SerialName("model_name") val modelName: String,
    val status: PicoClawRuntimeStatusResponse,
)

@Serializable
private data class PicoClawHistoryListEntryResponse(
    val id: String,
    val title: String,
    val preview: String,
    @SerialName("message_count") val messageCount: Int,
    val created: String,
    val updated: String,
)

private object PicoClawHistoryListResponseSerializer :
    KSerializer<List<PicoClawHistoryListEntryResponse>> by
    kotlinx.serialization.builtins.ListSerializer(PicoClawHistoryListEntryResponse.serializer())

private typealias PicoClawHistoryListResponse = List<PicoClawHistoryListEntryResponse>

@Serializable
private data class PicoClawHistoryMessageResponse(
    val role: String,
    val content: String,
)

@Serializable
private data class PicoClawHistoryDetailResponse(
    val id: String,
    val messages: List<PicoClawHistoryMessageResponse>,
    val summary: String? = null,
    val created: String,
    val updated: String,
)

@Serializable
private data class PicoClawHistoryDeleteResponse(
    val id: String,
    val deleted: Boolean,
)

@Serializable
private data class PicoClawSessionReleaseResponse(
    val released: Boolean,
    @SerialName("current_session") val currentSession: String? = null,
)

private fun PicoClawRuntimeStatusResponse.toValidatedModel(): NanoKvmPicoClawRuntimeStatus =
    picoInvalidServerData("runtime status") {
        require(installProgress == null || installProgress in 0..100) {
            "Install progress is outside 0..100"
        }
        val validatedStatus = status.picoBounded(
            "runtime status",
            MAX_PICOCLAW_STATUS_BYTES,
            allowEmpty = false,
        )
        val profile = agentProfile?.takeIf(String::isNotBlank)?.let { value ->
            when (value) {
                NanoKvmPicoClawAgentProfile.DEFAULT.wireValue ->
                    NanoKvmPicoClawAgentProfile.DEFAULT
                NanoKvmPicoClawAgentProfile.KVM.wireValue -> NanoKvmPicoClawAgentProfile.KVM
                else -> throw IllegalArgumentException("Unknown PicoClaw agent profile")
            }
        }
        NanoKvmPicoClawRuntimeStatus(
            ready = ready,
            installed = installed,
            installing = installing,
            installProgress = installProgress,
            installStage = installStage.picoOptionalBounded(
                "install stage",
                MAX_PICOCLAW_STATUS_BYTES,
            ),
            installPath = installPath.picoOptionalBounded(
                "install path",
                MAX_PICOCLAW_PATH_BYTES,
            ),
            agentProfile = profile,
            modelConfigured = modelConfigured,
            modelName = modelName.picoOptionalBounded("model name", MAX_PICOCLAW_MODEL_BYTES),
            phase = validatedStatus.toRuntimePhase(),
            configError = configError.picoOptionalBounded(
                "configuration error",
                MAX_PICOCLAW_ERROR_BYTES,
            ),
            lastError = lastError.picoOptionalBounded("last error", MAX_PICOCLAW_ERROR_BYTES),
            checkedAt = checkedAt?.takeIf(String::isNotBlank)?.picoInstant("checked_at"),
            currentSession = currentSession?.takeIf(String::isNotBlank)
                ?.let(NanoKvmPicoClawRuntimeSessionId::parse),
        )
    }

private fun String.toRuntimePhase(): NanoKvmPicoClawRuntimePhase = when (this) {
    "checking" -> NanoKvmPicoClawRuntimePhase.Checking
    "installing" -> NanoKvmPicoClawRuntimePhase.Installing
    "installed" -> NanoKvmPicoClawRuntimePhase.Installed
    "ready" -> NanoKvmPicoClawRuntimePhase.Ready
    "stopped" -> NanoKvmPicoClawRuntimePhase.Stopped
    "not_installed" -> NanoKvmPicoClawRuntimePhase.NotInstalled
    "model_not_configured" -> NanoKvmPicoClawRuntimePhase.ModelNotConfigured
    "config_error" -> NanoKvmPicoClawRuntimePhase.ConfigError
    "unavailable" -> NanoKvmPicoClawRuntimePhase.Unavailable
    "error" -> NanoKvmPicoClawRuntimePhase.Error
    else -> NanoKvmPicoClawRuntimePhase.Other(this)
}

private fun PicoClawRuntimeMutationResponse.toValidatedModel(expectedStarted: Boolean) =
    picoInvalidServerData("runtime mutation response") {
        require(started == expectedStarted) {
            "Runtime mutation result does not match the requested start/stop operation"
        }
        NanoKvmPicoClawRuntimeMutationResult(
            started = started,
            command = command.picoBounded("runtime command", MAX_PICOCLAW_COMMAND_BYTES),
            output = output.picoOptionalBounded("runtime output", MAX_PICOCLAW_OUTPUT_BYTES),
            status = status.toValidatedModel(),
        )
    }

private fun PicoClawRuntimeInstallResponse.toValidatedModel() =
    picoInvalidServerData("runtime installation response") {
        NanoKvmPicoClawInstallationResult(
            installed = installed,
            binary = binary.picoBounded("runtime binary", MAX_PICOCLAW_PATH_BYTES),
            download = download.picoBounded("runtime download", MAX_PICOCLAW_URL_BYTES),
            output = output.picoOptionalBounded("installation output", MAX_PICOCLAW_OUTPUT_BYTES),
            status = status.toValidatedModel(),
        )
    }

private fun PicoClawAgentProfileResponse.toValidatedModel() =
    picoInvalidServerData("agent profile response") {
        val typedProfile = NanoKvmPicoClawAgentProfile.entries.singleOrNull {
            it.wireValue == profile
        } ?: throw IllegalArgumentException("Unknown PicoClaw agent profile")
        NanoKvmPicoClawAgentProfileResult(typedProfile, status.toValidatedModel())
    }

private fun PicoClawModelConfigResponse.toValidatedModel() =
    picoInvalidServerData("model configuration response") {
        NanoKvmPicoClawModelConfigurationResult(
            modelName = modelName.picoBounded("model name", MAX_PICOCLAW_MODEL_BYTES),
            status = status.toValidatedModel(),
        )
    }

private fun PicoClawHistoryListResponse.toValidatedCatalog(
    requestedLimit: Int,
): NanoKvmPicoClawHistoryCatalog = picoInvalidServerData("history list") {
    require(size <= requestedLimit && size <= MAX_PICOCLAW_HISTORY_PAGE_SIZE) {
        "History response exceeds the requested page size"
    }
    val seen = mutableSetOf<String>()
    NanoKvmPicoClawHistoryCatalog(map { item ->
        val id = item.id.validatePicoHistoryId()
        require(seen.add(id)) { "History response contains duplicate session IDs" }
        val handle = NanoKvmPicoClawHistorySession(id)
        NanoKvmPicoClawHistorySummary(
            session = handle,
            title = item.title.picoBounded("history title", MAX_PICOCLAW_HISTORY_PREVIEW_BYTES),
            preview = item.preview.picoBounded(
                "history preview",
                MAX_PICOCLAW_HISTORY_PREVIEW_BYTES,
            ),
            messageCount = item.messageCount.also {
                require(it in 0..MAX_PICOCLAW_HISTORY_MESSAGES) {
                    "History message count is out of range"
                }
            },
            createdAt = item.created.picoInstant("history created time"),
            updatedAt = item.updated.picoInstant("history updated time"),
        )
    })
}

private fun PicoClawHistoryDetailResponse.toValidatedModel(
    expected: NanoKvmPicoClawHistorySession,
): NanoKvmPicoClawHistoryDetail = picoInvalidServerData("history detail") {
    require(id == expected.wireId) { "History detail ID does not match the requested handle" }
    require(messages.size <= MAX_PICOCLAW_HISTORY_MESSAGES) {
        "History contains too many messages"
    }
    NanoKvmPicoClawHistoryDetail(
        session = expected,
        messages = messages.map { message ->
            NanoKvmPicoClawHistoryMessage(
                role = when (message.role) {
                    "user" -> NanoKvmPicoClawHistoryRole.USER
                    "assistant" -> NanoKvmPicoClawHistoryRole.ASSISTANT
                    else -> throw IllegalArgumentException("Unknown PicoClaw history role")
                },
                content = message.content.picoBounded(
                    "history message",
                    MAX_PICOCLAW_HISTORY_MESSAGE_BYTES,
                    allowEmpty = false,
                ),
            )
        },
        summary = summary.picoOptionalBounded(
            "history summary",
            MAX_PICOCLAW_HISTORY_SUMMARY_BYTES,
        ),
        createdAt = created.picoInstant("history created time"),
        updatedAt = updated.picoInstant("history updated time"),
    )
}

private fun PicoClawSessionReleaseResponse.toValidatedModel() =
    picoInvalidServerData("runtime-session release") {
        NanoKvmPicoClawSessionRelease(
            released = released,
            currentSession = currentSession?.takeIf(String::isNotBlank)
                ?.let(NanoKvmPicoClawRuntimeSessionId::parse),
        )
    }

private fun JsonObject.picoclawErrorOrNull(): NanoKvmPicoClawApiException? {
    val primitive = this["code"] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val code = primitive.contentOrNull.orEmpty()
    if (code.isEmpty() || code.picoUtf8Size() > MAX_PICOCLAW_ERROR_CODE_BYTES ||
        code.any(Char::isISOControl)
    ) {
        throw InvalidApiResponseException("NanoKVM returned an invalid PicoClaw error code")
    }
    val validatedCode = code
    val message = (this["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        .picoTakeUtf8(MAX_PICOCLAW_ERROR_BYTES)
    val session = (this["session_id"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { NanoKvmPicoClawRuntimeSessionId.parse(it) }.getOrNull() }
    val index = (this["index"] as? JsonPrimitive)?.intOrNull
    return NanoKvmPicoClawApiException(validatedCode, message, session, index)
}

private fun String.validatePicoHistoryId(): String {
    require(length in 1..MAX_PICOCLAW_HISTORY_ID_CHARS && picoUtf8Size() <= MAX_PICOCLAW_HISTORY_ID_BYTES) {
        "History session ID is blank or too long"
    }
    val isUuid = runCatching { NanoKvmPicoClawRuntimeSessionId.parse(this) }.isSuccess
    val isOpaque = PICOCLAW_OPAQUE_HISTORY_ID.matches(this)
    require(isUuid || isOpaque) { "History session ID is not a safe UUID or opaque key" }
    return this
}

private fun String.picoInstant(label: String): Instant = try {
    Instant.parse(picoBounded(label, MAX_PICOCLAW_TIMESTAMP_BYTES))
} catch (error: DateTimeParseException) {
    throw IllegalArgumentException("$label is not RFC3339", error)
}

private fun String?.picoOptionalBounded(label: String, maxBytes: Int): String? =
    this?.takeIf(String::isNotEmpty)?.picoBounded(label, maxBytes)

private fun String.picoBounded(label: String, maxBytes: Int, allowEmpty: Boolean = true): String {
    require((allowEmpty || isNotEmpty()) && picoUtf8Size() <= maxBytes) {
        "$label is blank or exceeds $maxBytes UTF-8 bytes"
    }
    require(none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        "$label contains unsupported control characters"
    }
    return this
}

private inline fun <T> picoInvalidServerData(label: String, block: () -> T): T = try {
    block()
} catch (error: IllegalArgumentException) {
    throw InvalidApiResponseException("NanoKVM returned invalid PicoClaw $label", error)
}

private fun String.picoTakeUtf8(limit: Int): String {
    if (picoUtf8Size() <= limit) return this
    val result = StringBuilder(minOf(length, limit))
    var bytes = 0
    var index = 0
    while (index < length) {
        val value = this[index]
        val chars: Int
        val encoded: Int
        if (value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            chars = 2
            encoded = 4
        } else {
            chars = 1
            encoded = when {
                value.code < 0x80 -> 1
                value.code < 0x800 -> 2
                else -> 3
            }
        }
        if (bytes + encoded > limit) break
        result.append(value)
        if (chars == 2) result.append(this[index + 1])
        bytes += encoded
        index += chars
    }
    return result.toString()
}

private fun String.picoUtf8Size(): Int = encodeToByteArray().size

private fun CharArray.picoUtf8Size(): Int {
    var bytes = 0
    var index = 0
    while (index < size) {
        val value = this[index]
        bytes += when {
            value.code < 0x80 -> 1
            value.code < 0x800 -> 2
            value.isHighSurrogate() && index + 1 < size && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        index++
    }
    return bytes
}

private suspend fun Call.picoAwait(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun ResponseBody.picoReadUtf8WithinLimit(): String {
    val declared = contentLength()
    if (declared > MAX_PICOCLAW_REST_RESPONSE_BYTES) {
        throw InvalidApiResponseException("PicoClaw REST response exceeds the 1 MiB limit")
    }
    val source = source()
    if (source.request(MAX_PICOCLAW_REST_RESPONSE_BYTES.toLong() + 1L)) {
        throw InvalidApiResponseException("PicoClaw REST response exceeds the 1 MiB limit")
    }
    return source.buffer.readUtf8()
}

private const val PICOCLAW_MODEL_CONFIG_PATH = "/api/picoclaw/model/config"
private const val PICOCLAW_AGENT_PROFILE_PATH = "/api/picoclaw/agent/profile"
private const val PICOCLAW_SESSIONS_PATH = "/api/picoclaw/sessions"
private const val PICOCLAW_RUNTIME_STATUS_PATH = "/api/picoclaw/runtime/status"
private const val PICOCLAW_RUNTIME_SESSION_PATH = "/api/picoclaw/runtime/session"
private const val PICOCLAW_RUNTIME_INSTALL_PATH = "/api/picoclaw/runtime/install"
private const val PICOCLAW_RUNTIME_UNINSTALL_PATH = "/api/picoclaw/runtime/uninstall"
private const val PICOCLAW_RUNTIME_START_PATH = "/api/picoclaw/runtime/start"
private const val PICOCLAW_RUNTIME_STOP_PATH = "/api/picoclaw/runtime/stop"
internal const val PICOCLAW_GATEWAY_PATH = "/api/picoclaw/gateway/ws"
internal const val PICOCLAW_SESSION_ID_HEADER = "X-PicoClaw-Session-ID"

private val PICOCLAW_OPAQUE_HISTORY_ID = Regex("sk_v1_[A-Za-z0-9_-]{1,120}")
private const val UUID_TEXT_LENGTH = 36
private const val DEFAULT_PICOCLAW_HISTORY_LIMIT = 20
private const val MAX_PICOCLAW_HISTORY_PAGE_SIZE = 100
private const val MAX_PICOCLAW_HISTORY_OFFSET = 100_000
private const val MAX_PICOCLAW_HISTORY_MESSAGES = 2_048
private const val MAX_PICOCLAW_HISTORY_ID_CHARS = 128
private const val MAX_PICOCLAW_HISTORY_ID_BYTES = 128
private const val MAX_PICOCLAW_HISTORY_PREVIEW_BYTES = 1_024
private const val MAX_PICOCLAW_HISTORY_MESSAGE_BYTES = 128 * 1_024
private const val MAX_PICOCLAW_HISTORY_SUMMARY_BYTES = 16 * 1_024
private const val MAX_PICOCLAW_MODEL_BYTES = 512
private const val MAX_PICOCLAW_API_BASE_BYTES = 4_096
private const val MAX_PICOCLAW_API_KEY_CHARS = 4_096
private const val MAX_PICOCLAW_API_KEY_BYTES = 16 * 1_024
private const val MAX_PICOCLAW_STATUS_BYTES = 128
private const val MAX_PICOCLAW_ERROR_CODE_BYTES = 128
private const val MAX_PICOCLAW_ERROR_BYTES = 16 * 1_024
private const val MAX_PICOCLAW_PATH_BYTES = 4_096
private const val MAX_PICOCLAW_URL_BYTES = 4_096
private const val MAX_PICOCLAW_COMMAND_BYTES = 4_096
private const val MAX_PICOCLAW_OUTPUT_BYTES = 64 * 1_024
private const val MAX_PICOCLAW_TIMESTAMP_BYTES = 128
private const val MAX_PICOCLAW_REST_RESPONSE_BYTES = 1 * 1_024 * 1_024
