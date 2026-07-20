package org.nanokvm.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Suspend-based client for the console-first NanoKVM REST surface (application >= 2.3.2). */
class NanoKvmApi internal constructor(
    private val endpoint: NanoKvmEndpoint,
    private val httpClient: OkHttpClient,
    private val tokenStore: SessionTokenStore,
    private val json: Json,
) {
    private val scriptCatalogLock = Any()
    private var latestScriptCatalog: NanoKvmScriptCatalog? = null
    private val hidShortcutCatalogLock = Any()
    private var latestHidShortcutCatalog: NanoKvmSavedHidShortcutCatalog? = null
    private val autostartCatalogLock = Any()
    private var latestAutostartCatalog: NanoKvmAutostartCatalog? = null
    private val tailscaleStatusLock = Any()
    private var latestTailscaleStatus: NanoKvmTailscaleStatus? = null
    private var tailscaleStatusEpoch: Long = 0L
    private var hidShortcutCatalogEpoch: Long = 0L
    private var autostartCatalogEpoch: Long = 0L

    /** Logs in and atomically makes the returned cookie token available to REST and WebSockets. */
    suspend fun login(username: String, password: CharArray): SessionToken {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(password.isNotEmpty()) { "Password must not be empty" }

        val encryptedPassword = NanoKvmPasswordCipher.encrypt(password)
        val token = post(
            path = "/api/auth/login",
            body = LoginRequest(username.trim(), encryptedPassword),
            responseSerializer = serializer<SessionToken>(),
        )
        validateCookieValue(token.token)
        invalidateSessionScopedHandles()
        tokenStore.write(token.token)
        return token
    }

    /** Returns the authenticated account name. */
    suspend fun currentAccount(): NanoKvmAccount =
        get("/api/auth/account", serializer<AccountResponse>()).toModel()

    /** Reports whether the factory account password has been replaced. */
    suspend fun passwordStatus(): NanoKvmPasswordStatus =
        get("/api/auth/password", serializer<PasswordStatusResponse>()).toModel()

    /**
     * Changes credentials with NanoKVM's CryptoJS/OpenSSL-compatible passphrase AES envelope.
     *
     * This mutation is never automatically retried. The caller retains ownership of [password]
     * and should clear it after this call completes.
     */
    suspend fun changePassword(username: String, password: CharArray) {
        val validatedUsername = validateChangedUsername(username)
        validateChangedPassword(password)
        postWithoutData(
            "/api/auth/password",
            ChangePasswordRequest(
                username = validatedUsername,
                password = NanoKvmPasswordCipher.encrypt(password),
            ),
        )
    }

    /** Reads installed/latest application versions; an unavailable latest version is null. */
    suspend fun applicationVersions(): NanoKvmApplicationVersions =
        get("/api/application/version", serializer<ApplicationVersionResponse>()).toModel()

    suspend fun previewUpdates(): NanoKvmPreviewUpdates =
        get("/api/application/preview", serializer<PreviewUpdatesResponse>()).toModel()

    /** Explicit preview-channel setter. This mutation is never automatically retried. */
    suspend fun setPreviewUpdates(enabled: Boolean) {
        postWithoutData("/api/application/preview", SetPreviewUpdatesRequest(enable = enabled))
    }

    /**
     * Starts the one-shot online updater and waits up to fifteen minutes for its response.
     *
     * A successful update restarts NanoKVM services. This mutation is never automatically
     * retried; after success or an ambiguous disconnect, callers must rediscover/reconnect and
     * read [applicationVersions] before deciding whether another attempt is appropriate.
     */
    suspend fun startOnlineUpdate() {
        postWithoutData(
            "/api/application/update",
            callTimeoutMillis = ONLINE_UPDATE_CALL_TIMEOUT_MILLIS,
        )
    }

    /**
     * Streams and installs one exact, pre-validated NanoKVM application archive.
     *
     * The package is consumed before dispatch and can never be used for a second attempt. The
     * request also disables HTTP redirects and marks its body one-shot. Success normally causes
     * NanoKVM services to restart; cancellation, timeout, an invalid response, or transport loss
     * must be reconciled by reconnecting and reading [applicationVersions], never by replaying the
     * consumed package automatically.
     */
    suspend fun startOfflineUpdate(
        packageFile: NanoKvmOfflineUpdatePackage,
        onProgress: (NanoKvmOfflineUpdateProgress) -> Unit = {},
    ): NanoKvmOfflineUpdateReceipt {
        // Avoid consuming a caller's one-shot stream when no authenticated session exists locally.
        tokenStore.read() ?: throw AuthenticationExpiredException()

        val source = packageFile.consume()
        val requestBody = NanoKvmOfflineUpdateRequestBody(
            source = source,
            length = packageFile.contentLength,
            progress = onProgress,
            mediaType = OFFLINE_UPDATE_MEDIA_TYPE,
        )
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", packageFile.fileName, requestBody)
            .build()
        val request = requestBuilder("/api/application/update/offline")
            .post(multipart)
            .build()
        val oneShotTransport = httpClient.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        try {
            executeEnvelope<Unit>(
                request = request,
                responseSerializer = null,
                callTimeoutMillis = OFFLINE_UPDATE_CALL_TIMEOUT_MILLIS,
                transport = oneShotTransport,
            )
        } catch (error: CancellationException) {
            requestBody.cancelSource()
            throw error
        } catch (error: AuthenticationExpiredException) {
            throw error
        } catch (error: ApiResponseException) {
            throw NanoKvmOfflineUpdateException(
                NanoKvmOfflineUpdateFailure.ApiRejected(error.code),
            )
        } catch (error: HttpResponseException) {
            throw NanoKvmOfflineUpdateException(
                NanoKvmOfflineUpdateFailure.HttpRejected(
                    statusCode = error.statusCode,
                    outcomeUnknown = error.statusCode == 408 || error.statusCode == 425 ||
                        error.statusCode == 429 || error.statusCode >= 500,
                ),
            )
        } catch (_: InvalidApiResponseException) {
            throw NanoKvmOfflineUpdateException(
                NanoKvmOfflineUpdateFailure.InvalidResponseOutcomeUnknown,
            )
        } catch (error: IOException) {
            val failure = if (error.isOfflineUpdateSourceFailure()) {
                NanoKvmOfflineUpdateFailure.LocalSourceUnavailable
            } else {
                NanoKvmOfflineUpdateFailure.TransportOutcomeUnknown
            }
            throw NanoKvmOfflineUpdateException(failure)
        }

        return NanoKvmOfflineUpdateReceipt(
            fileName = packageFile.fileName,
            uploadedBytes = packageFile.contentLength,
        )
    }

    /**
     * Reboots the NanoKVM appliance. This one-shot mutation is never automatically retried.
     * A connection loss after dispatch is an ambiguous outcome; reconnect and probe the device.
     */
    suspend fun rebootSystem() {
        postWithoutData("/api/vm/system/reboot")
    }

    suspend fun oledConfiguration(): NanoKvmOledConfiguration =
        get("/api/vm/oled", serializer<OledResponse>()).toModel()

    /** Sets one of the exact OLED sleep values offered by the NanoKVM 2.4.3 WebUI. */
    suspend fun setOledSleep(preset: NanoKvmOledSleepPreset) {
        postWithoutData("/api/vm/oled", SetOledSleepRequest(sleep = preset.seconds))
    }

    suspend fun sshState(): NanoKvmSshState =
        get("/api/vm/ssh", serializer<EnabledResponse>()).toSshModel()

    /** Uses NanoKVM's explicit enable/disable endpoints; it is never a toggle request. */
    suspend fun setSshEnabled(enabled: Boolean) {
        postWithoutData(if (enabled) "/api/vm/ssh/enable" else "/api/vm/ssh/disable")
    }

    suspend fun hostname(): NanoKvmHostname =
        get("/api/vm/hostname", serializer<HostnameResponse>()).toModel()

    suspend fun setHostname(hostname: String) {
        postWithoutData("/api/vm/hostname", HostnameRequest(validateHostname(hostname)))
    }

    suspend fun mdnsState(): NanoKvmMdnsState =
        get("/api/vm/mdns", serializer<EnabledResponse>()).toMdnsModel()

    /** Uses NanoKVM's explicit enable/disable endpoints; it is never a toggle request. */
    suspend fun setMdnsEnabled(enabled: Boolean) {
        postWithoutData(if (enabled) "/api/vm/mdns/enable" else "/api/vm/mdns/disable")
    }

    /**
     * Reads the title, mapping only 2.4.3's exact absent-file error to the default title.
     * Other API errors remain visible to callers.
     */
    suspend fun webTitle(): NanoKvmWebTitle = try {
        get("/api/vm/web-title", serializer<WebTitleResponse>()).toModel()
    } catch (error: ApiResponseException) {
        if (
            error.code == MISSING_WEB_TITLE_CODE &&
            error.serverMessageKind == ApiResponseServerMessageKind.MISSING_WEB_TITLE
        ) {
            NanoKvmWebTitle(NanoKvmWebTitle.DEFAULT, isDefault = true)
        } else {
            throw error
        }
    }

    /** Sets a custom title. Use [resetWebTitle] for empty/default semantics. */
    suspend fun setWebTitle(title: String) {
        postWithoutData("/api/vm/web-title", WebTitleRequest(validateWebTitle(title)))
    }

    /** Restores the default "NanoKVM" title using the server's empty-title reset contract. */
    suspend fun resetWebTitle() {
        postWithoutData("/api/vm/web-title", WebTitleRequest(title = ""))
    }

    suspend fun dnsConfiguration(): NanoKvmDnsConfiguration =
        get("/api/network/dns", serializer<DnsResponse>()).toModel()

    /** Selects manual DNS after canonical IP-only, uniqueness, and maximum-six validation. */
    suspend fun setManualDns(servers: List<String>) {
        val addresses = validateManualDnsServers(servers.map(NanoKvmIpAddress::parse))
        postWithoutData(
            "/api/network/dns",
            DnsRequest(
                mode = NanoKvmDnsMode.Manual.wireValue,
                servers = addresses.map(NanoKvmIpAddress::value),
            ),
        )
    }

    /** Selects DHCP DNS with the exact empty-server request required by NanoKVM. */
    suspend fun setDhcpDns() {
        postWithoutData(
            "/api/network/dns",
            DnsRequest(mode = NanoKvmDnsMode.Dhcp.wireValue, servers = emptyList()),
        )
    }

    /**
     * Reads the authenticated Wi-Fi status. NanoKVM 2.4.3 has no scan/list route: callers must
     * collect an SSID manually before connecting.
     */
    suspend fun wifiStatus(): NanoKvmWifiStatus =
        get("/api/network/wifi", serializer<WifiStatusResponse>()).toValidatedModel()

    /**
     * Connects through the authenticated route using one single-use, mutable credential object.
     * The write can move the appliance to another network and is never retried automatically.
     */
    suspend fun connectWifi(credentials: NanoKvmWifiCredentials) {
        wifiOperation(NanoKvmWifiOperation.CONNECT_AUTHENTICATED) {
            val body = credentials.consumeJson(json)
            val request = requestBuilder("/api/network/wifi/connect")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeEnvelope<Unit>(request, responseSerializer = null)
        }
    }

    /**
     * Disconnects Wi-Fi and deletes the saved SSID/password on the appliance. This may terminate
     * the active Android session; an ambiguous disconnect is not permission to replay the write.
     */
    suspend fun disconnectWifi() {
        wifiOperation(NanoKvmWifiOperation.DISCONNECT_AUTHENTICATED) {
            postWithoutData("/api/network/wifi/disconnect")
        }
    }

    /** Reads and records the only status snapshot accepted by a subsequent Tailscale command. */
    suspend fun tailscaleStatus(): NanoKvmTailscaleStatus {
        val epoch = synchronized(tailscaleStatusLock) {
            latestTailscaleStatus = null
            ++tailscaleStatusEpoch
        }
        val status = get(
            "/api/extensions/tailscale/status",
            serializer<TailscaleStatusResponse>(),
        ).toValidatedModel()
        synchronized(tailscaleStatusLock) {
            if (tailscaleStatusEpoch == epoch) latestTailscaleStatus = status
        }
        return status
    }

    /** Downloads official binaries and starts the service; requires an exact confirmed snapshot. */
    suspend fun installTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.INSTALL, approval)
    }

    /** Removes the Tailscale binaries after a state-bound user confirmation. */
    suspend fun uninstallTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.UNINSTALL, approval)
    }

    /** Starts tailscaled and restores its boot script; this does not mean `tailscale up`. */
    suspend fun startTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.START, approval)
    }

    /** Stops tailscaled and removes its boot script; connectivity can disappear immediately. */
    suspend fun stopTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.STOP, approval)
    }

    /** Restarts tailscaled once. It is distinct from both service start and tailnet `up`. */
    suspend fun restartTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.RESTART, approval)
    }

    /** Runs `tailscale up --accept-dns=false`, enabling tailnet connectivity. */
    suspend fun bringTailscaleUp(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.UP, approval)
    }

    /** Runs `tailscale down`, retaining the daemon and account while disabling connectivity. */
    suspend fun bringTailscaleDown(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.DOWN, approval)
    }

    /**
     * Begins official external authentication. An empty URL means a racing status was already
     * authenticated; otherwise the returned URL is HTTPS-origin/path allowlisted and redacted.
     */
    suspend fun loginTailscale(
        approval: NanoKvmTailscaleActionApproval,
    ): NanoKvmTailscaleLoginResult {
        consumeLatestTailscaleStatus(NanoKvmTailscaleCommand.LOGIN, approval)
        return tailscaleOperation(NanoKvmTailscaleCommand.LOGIN) {
            val response = executeEnvelope(
                requestBuilder(NanoKvmTailscaleCommand.LOGIN.path)
                    .post(EMPTY_JSON_BODY)
                    .build(),
                serializer<TailscaleLoginResponse>(),
            ) ?: throw InvalidApiResponseException(
                "NanoKVM Tailscale login response did not contain data",
            )
            response.toValidatedModel()
        }
    }

    /** Logs the appliance out of its Tailscale account once. */
    suspend fun logoutTailscale(approval: NanoKvmTailscaleActionApproval) {
        executeTailscaleCommand(NanoKvmTailscaleCommand.LOGOUT, approval)
    }

    suspend fun vmInfo(): VmInfo =
        get("/api/vm/info", serializer<VmInfoResponse>()).toModel()

    suspend fun hardware(): HardwareInfo = get("/api/vm/hardware", serializer())

    suspend fun hdmiState(): NanoKvmHdmiState = NanoKvmHdmiState(
        enabled = get("/api/vm/hdmi", serializer<HdmiStateResponse>()).enabled,
    )

    /**
     * Uses NanoKVM's explicit HDMI endpoint. This can terminate the active video stream and is
     * never retried automatically; a caller should read [hdmiState] before and after the write.
     */
    suspend fun setHdmiEnabled(enabled: Boolean) {
        postWithoutData(if (enabled) "/api/vm/hdmi/enable" else "/api/vm/hdmi/disable")
    }

    /**
     * Performs NanoKVM's one-shot HDMI reset. The 2.4.3 handler interrupts capture for one second,
     * so an ambiguous disconnect is not permission to replay the request.
     */
    suspend fun resetHdmi() {
        postWithoutData("/api/vm/hdmi/reset")
    }

    /** Reads the jiggler state while retaining bounded future modes as read-only values. */
    suspend fun mouseJigglerState(): NanoKvmMouseJigglerState =
        get(
            "/api/vm/mouse-jiggler",
            serializer<MouseJigglerResponse>(),
        ).toModel()

    /** Enables one known 2.4.3 mode exactly once. Unknown modes returned by a future server cannot be written. */
    suspend fun enableMouseJiggler(mode: NanoKvmMouseJigglerMode) {
        require(
            mode === NanoKvmMouseJigglerMode.Relative ||
                mode === NanoKvmMouseJigglerMode.Absolute,
        ) {
            "Only known NanoKVM mouse-jiggler modes can be selected"
        }
        postWithoutData(
            "/api/vm/mouse-jiggler/",
            SetMouseJigglerRequest(enabled = true, mode = mode.wireValue),
        )
    }

    /**
     * Sends the exact 2.4.3 disable request. The server errors when already disabled, so callers
     * should preflight with [mouseJigglerState] rather than treating this as idempotent.
     */
    suspend fun disableMouseJiggler() {
        postWithoutData(
            "/api/vm/mouse-jiggler/",
            SetMouseJigglerRequest(
                enabled = false,
                mode = NanoKvmMouseJigglerMode.Relative.wireValue,
            ),
        )
    }

    suspend fun memoryLimitState(): NanoKvmMemoryLimitState =
        get(
            "/api/vm/memory/limit",
            serializer<MemoryLimitResponse>(),
        ).toModel()

    /** Enables the only memory-limit preset written by the pinned 2.4.3 WebUI. */
    suspend fun setMemoryLimit(preset: NanoKvmMemoryLimitPreset) {
        postWithoutData(
            "/api/vm/memory/limit",
            SetMemoryLimitRequest(enabled = true, limit = preset.megabytes),
        )
    }

    /**
     * Sends the exact disabled state. NanoKVM errors if the backing setting is already absent;
     * callers should read [memoryLimitState] first and never blindly replay this mutation.
     */
    suspend fun disableMemoryLimit() {
        postWithoutData(
            "/api/vm/memory/limit",
            SetMemoryLimitRequest(enabled = false, limit = 0L),
        )
    }

    suspend fun swapState(): NanoKvmSwapState =
        get("/api/vm/swap", serializer<SwapResponse>()).toModel()

    /** Writes one exact swap size offered by the pinned 2.4.3 WebUI, including DISABLED. */
    suspend fun setSwapSize(preset: NanoKvmSwapSizePreset) {
        postWithoutData("/api/vm/swap", SetSwapRequest(size = preset.megabytes))
    }

    /**
     * Enables appliance TLS with a generated self-signed certificate and restarts NanoKVM.
     * There is deliberately no disable counterpart. Reconnect using HTTPS and perform the normal
     * certificate trust flow after success or an ambiguous disconnect; never replay automatically.
     */
    suspend fun enableApplianceTls() {
        postWithoutData("/api/vm/tls", EnableTlsRequest(enabled = true))
    }

    suspend fun gpioStatus(): GpioStatus = get("/api/vm/gpio", serializer())

    /**
     * Reads the small, side-effect-free discovery surface without letting an unavailable optional
     * endpoint invalidate an authenticated session. Authentication expiry still propagates.
     */
    suspend fun probeCapabilities(): NanoKvmServerProbeResult = probeServerCapabilities()

    /** Triggers a guarded physical power or reset button press. */
    suspend fun pressGpio(action: GpioAction, durationMillis: Long = 800L) {
        require(durationMillis in 1L..30_000L) { "GPIO press duration must be 1..30000 ms" }
        postWithoutData(
            "/api/vm/gpio",
            GpioRequest(type = action.wireName, duration = durationMillis),
        )
    }

    /**
     * Updates a stream setting. Values are validated to the stable NanoKVM ranges exposed in v2.3.
     */
    suspend fun updateScreen(setting: ScreenSetting, value: Int) {
        validateScreenValue(setting, value)
        postWithoutData(
            "/api/vm/screen",
            ScreenRequest(type = setting.wireName, value = value),
        )
    }

    /** Enables or disables the MJPEG difference detector once; NanoKVM exposes no state GET. */
    suspend fun setMjpegFrameDetectionEnabled(enabled: Boolean) {
        postWithoutData(
            "/api/stream/mjpeg/detect",
            MjpegFrameDetectionRequest(enabled),
        )
    }

    /**
     * Temporarily wakes an enabled MJPEG detector while the first visible frames arrive. The
     * 2.4.3 endpoint keeps its HTTP request open for the supplied duration, so this is capped and
     * must never be retried or interpreted as authoritative persisted state.
     */
    suspend fun temporarilyPauseMjpegFrameDetection(durationSeconds: Int = 10) {
        require(durationSeconds in 1..30) { "Frame-detection pause must be 1..30 seconds" }
        postWithoutData(
            "/api/stream/mjpeg/detect/stop",
            MjpegFrameDetectionPauseRequest(durationSeconds),
        )
    }

    suspend fun resetHid() {
        postWithoutData("/api/hid/reset")
    }

    /** Server-side batch paste; live IME input should use keyboard WebSocket reports instead. */
    suspend fun paste(content: String, language: PasteLanguage = PasteLanguage.ENGLISH) {
        require(content.isNotEmpty()) { "Paste content must not be empty" }
        require(content.encodeToByteArray().size <= 1024) {
            "NanoKVM paste content must be at most 1024 UTF-8 bytes"
        }
        postWithoutData(
            "/api/hid/paste",
            PasteRequest(content = content, langue = language.wireName),
        )
    }

    /**
     * Reads a bounded saved-shortcut snapshot. Unknown future key codes remain visible but are
     * read-only and cannot be dispatched through [NanoKvmInputSocket.sendSavedHidShortcut].
     */
    suspend fun savedHidShortcuts(): NanoKvmSavedHidShortcutCatalog {
        val requestEpoch = synchronized(hidShortcutCatalogLock) { hidShortcutCatalogEpoch }
        val catalog = get(
            "/api/hid/shortcuts",
            serializer<HidShortcutsResponse>(),
        ).toValidatedCatalog()
        synchronized(hidShortcutCatalogLock) {
            // A concurrent add/delete/session change makes the response ordering ambiguous. Keep
            // the bounded read visible, but do not authorize it as a mutation handle snapshot.
            if (hidShortcutCatalogEpoch == requestEpoch) {
                latestHidShortcutCatalog = catalog
            }
        }
        return catalog
    }

    /**
     * Stores one locally allowlisted recording. The 2.4.3 response has no created ID, so this
     * one-shot mutation invalidates the prior snapshot before dispatch and must be followed by a
     * fresh [savedHidShortcuts] read. An ambiguous failure is never permission to replay it.
     */
    suspend fun addSavedHidShortcut(draft: NanoKvmHidShortcutDraft) {
        synchronized(hidShortcutCatalogLock) {
            hidShortcutCatalogEpoch++
            latestHidShortcutCatalog = null
        }
        postWithoutData(
            "/api/hid/shortcut",
            AddHidShortcutRequest(keys = draft.toRequestKeys()),
        )
    }

    /**
     * Deletes one exact handle from the latest list snapshot using NanoKVM's JSON DELETE body.
     * The snapshot is consumed before dispatch, preventing blind replay after a lost response.
     */
    suspend fun deleteSavedHidShortcut(
        catalog: NanoKvmSavedHidShortcutCatalog,
        shortcut: NanoKvmSavedHidShortcut,
    ) {
        synchronized(hidShortcutCatalogLock) {
            require(latestHidShortcutCatalog === catalog) {
                "Shortcut catalog must be the latest snapshot returned by this NanoKVM API"
            }
            catalog.requireExactMember(shortcut)
            hidShortcutCatalogEpoch++
            latestHidShortcutCatalog = null
        }
        deleteWithoutData(
            "/api/hid/shortcut",
            DeleteHidShortcutRequest(id = shortcut.id),
        )
    }

    /** Reads the persisted leader key; an empty code is the server's disabled state. */
    suspend fun leaderKey(): NanoKvmLeaderKeyState =
        get(
            "/api/hid/shortcut/leader-key",
            serializer<LeaderKeyResponse>(),
        ).toValidatedState()

    /** Writes one allowlisted 2.4.3 browser key code exactly once. */
    suspend fun setLeaderKey(key: NanoKvmHidKeyCode) {
        postWithoutData(
            "/api/hid/shortcut/leader-key",
            SetLeaderKeyRequest(key = key.wireValue),
        )
    }

    /** Disables the leader key using the exact empty-string 2.4.3 contract. */
    suspend fun disableLeaderKey() {
        postWithoutData(
            "/api/hid/shortcut/leader-key",
            SetLeaderKeyRequest(key = ""),
        )
    }

    /**
     * Reads the bounded autostart-script catalog exposed by NanoKVM 2.4.3. A new list request
     * immediately invalidates older mutation handles, including when this read later fails.
     */
    suspend fun autostartScripts(): NanoKvmAutostartCatalog =
        autostartOperation(NanoKvmAutostartOperation.LIST) {
            val requestEpoch = synchronized(autostartCatalogLock) {
                autostartCatalogEpoch++
                latestAutostartCatalog = null
                autostartCatalogEpoch
            }
            val catalog = get(
                "/api/vm/autostart",
                serializer<AutostartListResponse>(),
            ).toValidatedCatalog()
            synchronized(autostartCatalogLock) {
                // Only the newest initiated list can authorize a later write. A concurrent list,
                // session change, or mutation makes an older response unsuitable as authority.
                if (autostartCatalogEpoch == requestEpoch) {
                    latestAutostartCatalog = catalog
                }
            }
            catalog
        }

    /** Reads bounded UTF-8 content through one exact handle from the latest catalog. */
    suspend fun autostartContent(
        catalog: NanoKvmAutostartCatalog,
        script: NanoKvmAutostartScript,
    ): NanoKvmAutostartContent = autostartOperation(NanoKvmAutostartOperation.READ) {
        requireLatestAutostartHandle(catalog, script)
        autostartContentFromResponse(
            get("/api/vm/autostart/${script.name}", serializer<String>()),
        )
    }

    /**
     * Creates a safe .sh or .py basename proven absent from the latest catalog. The catalog and
     * single-use content are consumed before dispatch; list again after every outcome.
     */
    suspend fun createAutostartScript(
        catalog: NanoKvmAutostartCatalog,
        fileName: String,
        content: NanoKvmAutostartWriteContent,
    ): NanoKvmAutostartWriteReceipt = try {
        val validatedName = validateAutostartBasename(fileName)
        consumeAutostartCatalogForCreate(catalog, validatedName)
        autostartOperation(NanoKvmAutostartOperation.CREATE) {
            writeAutostartScript(
                fileName = validatedName,
                content = content,
                kind = NanoKvmAutostartWriteKind.CREATE,
            )
        }
    } finally {
        content.close()
    }

    /**
     * Replaces one exact script from the latest catalog. The catalog and single-use content are
     * consumed before dispatch; an ambiguous response is never permission to replay the write.
     */
    suspend fun updateAutostartScript(
        catalog: NanoKvmAutostartCatalog,
        script: NanoKvmAutostartScript,
        content: NanoKvmAutostartWriteContent,
    ): NanoKvmAutostartWriteReceipt = try {
        consumeAutostartCatalogForMutation(catalog, script)
        autostartOperation(NanoKvmAutostartOperation.UPDATE) {
            writeAutostartScript(
                fileName = script.name,
                content = content,
                kind = NanoKvmAutostartWriteKind.UPDATE,
            )
        }
    } finally {
        content.close()
    }

    /** Deletes one exact handle from the latest catalog and consumes the catalog before dispatch. */
    suspend fun deleteAutostartScript(
        catalog: NanoKvmAutostartCatalog,
        script: NanoKvmAutostartScript,
    ) {
        consumeAutostartCatalogForMutation(catalog, script)
        autostartOperation(NanoKvmAutostartOperation.DELETE) {
            executeEnvelope<Unit>(
                requestBuilder("/api/vm/autostart/${script.name}").delete().build(),
                responseSerializer = null,
            )
        }
    }

    /** Returns a bounded snapshot whose opaque handles are required by mount and delete. */
    suspend fun listImages(): NanoKvmImageCatalog {
        val response = get("/api/storage/image", serializer<ImageListResponse>())
        return invalidServerData("image list") {
            val files = response.files.orEmpty()
            require(files.size <= MAX_IMAGE_COUNT) {
                "NanoKVM image list exceeds $MAX_IMAGE_COUNT entries"
            }
            val validated = files.map(::validateServerImagePath)
            require(validated.toSet().size == validated.size) {
                "NanoKVM image list contains duplicate paths"
            }
            NanoKvmImageCatalog(validated.map(::NanoKvmImage))
        }
    }

    /** Returns null when the appliance has restored its physical/default media backing. */
    suspend fun mountedImage(): NanoKvmMountedImage? {
        val file = get(
            "/api/storage/image/mounted",
            serializer<MountedImageResponse>(),
        ).file
        if (file.isEmpty()) return null
        return invalidServerData("mounted image") {
            NanoKvmMountedImage(validateServerImagePath(file))
        }
    }

    suspend fun cdRomState(): NanoKvmCdRomState {
        val cdrom = get("/api/storage/cdrom", serializer<CdRomResponse>()).cdrom
        return invalidServerData("CD-ROM state") {
            require(cdrom == 0 || cdrom == 1) { "NanoKVM CD-ROM state must be 0 or 1" }
            NanoKvmCdRomState(enabled = cdrom == 1)
        }
    }

    /** Mounts one exact image handle from [catalog]; arbitrary filesystem paths are not accepted. */
    suspend fun mountImage(
        catalog: NanoKvmImageCatalog,
        image: NanoKvmImage,
        mode: NanoKvmImageMountMode = NanoKvmImageMountMode.MASS_STORAGE,
    ) {
        catalog.requireExactMember(image)
        postWithoutData(
            "/api/storage/image/mount",
            MountImageRequest(file = image.path, cdrom = mode.cdrom),
        )
    }

    /** Restores the appliance's physical/default media backing. */
    suspend fun restorePhysicalMedia() {
        postWithoutData(
            "/api/storage/image/mount",
            MountImageRequest(file = "", cdrom = false),
        )
    }

    /** Deletes one exact image handle from [catalog]. */
    suspend fun deleteImage(catalog: NanoKvmImageCatalog, image: NanoKvmImage) {
        catalog.requireExactMember(image)
        postWithoutData("/api/storage/image/delete", ImagePathRequest(image.path))
    }

    /** Reads the stable mode while preserving bounded values added by newer servers. */
    suspend fun hidMode(): NanoKvmHidMode {
        val mode = get("/api/hid/mode", serializer<HidModeResponse>()).mode
        return invalidServerData("HID mode") {
            require(mode.isNotBlank() && mode.utf8Size() <= MAX_HID_MODE_UTF8_BYTES) {
                "NanoKVM HID mode is blank or too long"
            }
            require(mode.none(Char::isISOControl)) { "NanoKVM HID mode contains control characters" }
            when (mode) {
                NanoKvmHidMode.Normal.wireValue -> NanoKvmHidMode.Normal
                NanoKvmHidMode.HidOnly.wireValue -> NanoKvmHidMode.HidOnly
                else -> NanoKvmHidMode.Other(mode)
            }
        }
    }

    /**
     * Reconfigures the appliance USB gadget exactly once. The server may tear down the active
     * input socket while applying this change, so callers must release input first and create a
     * fresh input generation after the request is dispatched. Unknown future modes are read-only.
     */
    suspend fun setHidMode(mode: NanoKvmHidMode) {
        require(mode === NanoKvmHidMode.Normal || mode === NanoKvmHidMode.HidOnly) {
            "Only known NanoKVM HID modes can be selected"
        }
        postWithoutData("/api/hid/mode", HidModeRequest(mode.wireValue))
    }

    suspend fun virtualDevices(): NanoKvmVirtualDevices {
        val response = get(
            "/api/vm/device/virtual",
            serializer<VirtualDevicesResponse>(),
        )
        return NanoKvmVirtualDevices(
            network = response.network,
            media = response.media,
            disk = response.disk,
        )
    }

    /**
     * Performs exactly one non-idempotent server toggle and returns the server-observed state.
     * Callers must read [virtualDevices] before deciding whether a toggle is needed.
     */
    suspend fun toggleVirtualDevice(
        device: NanoKvmVirtualDevice,
    ): NanoKvmVirtualDeviceToggleResult {
        val response = post(
            "/api/vm/device/virtual",
            VirtualDeviceRequest(device.wireName),
            serializer<VirtualDeviceToggleResponse>(),
        )
        return NanoKvmVirtualDeviceToggleResult(device, response.on)
    }

    suspend fun isImageTransferEnabled(): Boolean = get(
        "/api/download/image/enabled",
        serializer<ImageTransferEnabledResponse>(),
    ).enabled

    /** Starts a remote-URL transfer using the stable 2.4.3 request (the `file` field only). */
    suspend fun startImageTransfer(source: NanoKvmRemoteImageUrl): NanoKvmImageTransferStatus =
        post(
            "/api/download/image",
            StartImageTransferRequest(file = source.value),
            serializer<ImageTransferResponse>(),
        ).toValidatedTransferStatus()

    suspend fun imageTransferStatus(): NanoKvmImageTransferStatus = get(
        "/api/download/image/status",
        serializer<ImageTransferResponse>(),
    ).toValidatedTransferStatus()

    /** Sends one Wake-on-LAN request. No automatic retry is applied. */
    suspend fun sendWakeOnLan(mac: NanoKvmMacAddress) {
        postWithoutData("/api/network/wol", WakeOnLanRequest(mac.value))
    }

    /**
     * Reads and canonically parses the saved WOL list. Stable <=2.4.1 returned API code -2 on
     * first use when the backing file did not yet exist; that exact legacy result is empty only
     * when the caller supplies a matching application version.
     */
    @JvmOverloads
    suspend fun wakeOnLanHistory(
        applicationVersion: NanoKvmApplicationVersion? = null,
    ): List<NanoKvmWakeOnLanEntry> {
        val response = try {
            get("/api/network/wol/mac", serializer<WakeOnLanHistoryResponse>())
        } catch (error: ApiResponseException) {
            if (error.isLegacyEmptyWakeOnLanHistory(applicationVersion)) {
                return emptyList()
            }
            throw error
        }
        return invalidServerData("Wake-on-LAN history") {
            val macs = response.macs.orEmpty()
            require(macs.size <= MAX_WOL_HISTORY_ENTRIES) {
                "NanoKVM WOL history exceeds $MAX_WOL_HISTORY_ENTRIES entries"
            }
            val entries = macs.map(::parseWakeOnLanHistoryEntry)
            require(entries.map { it.mac }.toSet().size == entries.size) {
                "NanoKVM WOL history contains duplicate MAC addresses"
            }
            entries
        }
    }

    suspend fun renameWakeOnLanEntry(mac: NanoKvmMacAddress, name: String) {
        val normalized = normalizeWakeOnLanName(name, allowEmpty = false)
        postWithoutData(
            "/api/network/wol/mac/name",
            RenameWakeOnLanRequest(mac = mac.value, name = normalized),
        )
    }

    /** Deletes one saved WOL entry with the JSON body required by NanoKVM 2.4.3. */
    suspend fun deleteWakeOnLanEntry(mac: NanoKvmMacAddress) {
        deleteWithoutData("/api/network/wol/mac", WakeOnLanRequest(mac.value))
    }

    /**
     * Returns a bounded snapshot of safe `.sh` and `.py` basenames.
     *
     * NanoKVM 2.4.3 recursively walks its script directory but returns only basenames. Duplicate,
     * path-like, traversal, unsupported-extension, and oversized entries are rejected. A later
     * list call invalidates every handle from an earlier snapshot.
     */
    suspend fun listScripts(): NanoKvmScriptCatalog = scriptOperation(
        NanoKvmScriptOperation.LIST,
    ) {
        val response = get("/api/vm/script", serializer<ScriptListResponse>())
        val catalog = invalidServerData("script list") {
            val files = response.files.orEmpty()
            require(files.size <= MAX_SCRIPT_COUNT) {
                "NanoKVM script list exceeds $MAX_SCRIPT_COUNT entries"
            }
            val names = files.map(::validateScriptBasename)
            require(names.toSet().size == names.size) {
                "NanoKVM script list contains duplicate basenames"
            }
            NanoKvmScriptCatalog(names.map(::NanoKvmScript))
        }
        synchronized(scriptCatalogLock) {
            latestScriptCatalog = catalog
        }
        catalog
    }

    /**
     * Uploads one bounded in-memory script using 2.4.3's multipart `file` field.
     *
     * Upload overwrites an existing basename on the appliance and is never automatically retried.
     * The receipt is deliberately not executable; call [listScripts] after success for a new
     * opaque handle.
     */
    suspend fun uploadScript(
        fileName: String,
        content: ByteArray,
    ): NanoKvmScriptUploadReceipt = scriptOperation(NanoKvmScriptOperation.UPLOAD) {
        val validatedName = validateScriptBasename(fileName)
        require(content.isNotEmpty()) { "Script upload must not be empty" }
        require(content.size <= MAX_SCRIPT_UPLOAD_BYTES) {
            "Script upload exceeds the $MAX_SCRIPT_UPLOAD_BYTES-byte limit"
        }
        val retainedContent = content.copyOf()
        // The server overwrites by basename. Invalidate before dispatch so an ambiguous transport
        // result cannot leave a caller believing an older list is still authoritative.
        synchronized(scriptCatalogLock) {
            latestScriptCatalog = null
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                validatedName,
                retainedContent.toRequestBody(OCTET_STREAM_MEDIA_TYPE),
            )
            .build()
        val response = executeEnvelope(
            requestBuilder("/api/vm/script/upload").post(multipart).build(),
            serializer<ScriptUploadResponse>(),
        ) ?: throw InvalidApiResponseException(
            "NanoKVM response for /api/vm/script/upload did not contain data",
        )
        val returnedName = invalidServerData("script-upload response") {
            val result = validateScriptBasename(response.file)
            require(result == validatedName) {
                "NanoKVM returned a different script basename after upload"
            }
            result
        }
        NanoKvmScriptUploadReceipt(returnedName, retainedContent.size)
    }

    /**
     * Runs one exact handle from the latest list snapshot. Foreground has no server-side timeout or
     * cancellation in 2.4.3, so cancelling the HTTP call does not prove the process stopped.
     * Background returns no PID, status, output stream, or cancellation handle.
     */
    suspend fun runScript(
        catalog: NanoKvmScriptCatalog,
        script: NanoKvmScript,
        mode: NanoKvmScriptRunMode,
    ): NanoKvmScriptRunResult = scriptOperation(NanoKvmScriptOperation.RUN) {
        requireLatestScriptHandle(catalog, script)
        val response = post(
            "/api/vm/script/run",
            ScriptRunRequest(name = script.name, type = mode.wireValue),
            serializer<ScriptRunResponse>(),
        )
        invalidServerData("script output") {
            require(response.log.hasBoundedUtf8Length(MAX_SCRIPT_OUTPUT_UTF8_BYTES)) {
                "NanoKVM script output exceeds $MAX_SCRIPT_OUTPUT_UTF8_BYTES UTF-8 bytes"
            }
            NanoKvmScriptRunResult(mode, response.log)
        }
    }

    /** Deletes one exact handle from the latest list snapshot using a JSON DELETE body. */
    suspend fun deleteScript(
        catalog: NanoKvmScriptCatalog,
        script: NanoKvmScript,
    ) = scriptOperation(NanoKvmScriptOperation.DELETE) {
        requireLatestScriptHandle(catalog, script)
        // Consume the snapshot before dispatch. A lost response is not permission to repeat the
        // delete without reconciling against a fresh list.
        synchronized(scriptCatalogLock) {
            latestScriptCatalog = null
        }
        deleteWithoutData("/api/vm/script", ScriptNameRequest(name = script.name))
    }

    internal fun invalidateSessionScopedHandles() {
        synchronized(scriptCatalogLock) {
            latestScriptCatalog = null
        }
        synchronized(hidShortcutCatalogLock) {
            hidShortcutCatalogEpoch++
            latestHidShortcutCatalog = null
        }
        synchronized(autostartCatalogLock) {
            autostartCatalogEpoch++
            latestAutostartCatalog = null
        }
        synchronized(tailscaleStatusLock) {
            tailscaleStatusEpoch++
            latestTailscaleStatus = null
        }
    }

    private fun requireLatestScriptHandle(
        catalog: NanoKvmScriptCatalog,
        script: NanoKvmScript,
    ) {
        synchronized(scriptCatalogLock) {
            require(latestScriptCatalog === catalog) {
                "Script catalog must be the latest snapshot returned by this NanoKVM API"
            }
            catalog.requireExactMember(script)
        }
    }

    private fun requireLatestAutostartHandle(
        catalog: NanoKvmAutostartCatalog,
        script: NanoKvmAutostartScript,
    ) {
        synchronized(autostartCatalogLock) {
            require(latestAutostartCatalog === catalog) {
                "Autostart catalog must be the latest snapshot returned by this NanoKVM API"
            }
            catalog.requireExactMember(script)
        }
    }

    private fun consumeAutostartCatalogForCreate(
        catalog: NanoKvmAutostartCatalog,
        fileName: String,
    ) {
        synchronized(autostartCatalogLock) {
            require(latestAutostartCatalog === catalog) {
                "Autostart catalog must be the latest snapshot returned by this NanoKVM API"
            }
            require(catalog.find(fileName) == null) {
                "Create requires a basename absent from the latest autostart catalog"
            }
            autostartCatalogEpoch++
            latestAutostartCatalog = null
        }
    }

    private fun consumeAutostartCatalogForMutation(
        catalog: NanoKvmAutostartCatalog,
        script: NanoKvmAutostartScript,
    ) {
        synchronized(autostartCatalogLock) {
            require(latestAutostartCatalog === catalog) {
                "Autostart catalog must be the latest snapshot returned by this NanoKVM API"
            }
            catalog.requireExactMember(script)
            autostartCatalogEpoch++
            latestAutostartCatalog = null
        }
    }

    private suspend fun writeAutostartScript(
        fileName: String,
        content: NanoKvmAutostartWriteContent,
        kind: NanoKvmAutostartWriteKind,
    ): NanoKvmAutostartWriteReceipt {
        val jsonBody = content.consumeJsonBody()
        try {
            val response = executeEnvelope(
                requestBuilder("/api/vm/autostart/$fileName")
                    .post(jsonBody.bytes.toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
                serializer<String>(),
            ) ?: throw InvalidApiResponseException(
                "NanoKVM autostart write response contained no data",
            )
            return NanoKvmAutostartWriteReceipt(
                fileName = validateAutostartWriteResponse(fileName, response),
                byteCount = jsonBody.contentByteCount,
                kind = kind,
            )
        } finally {
            jsonBody.close()
        }
    }

    private fun consumeLatestTailscaleStatus(
        command: NanoKvmTailscaleCommand,
        approval: NanoKvmTailscaleActionApproval,
    ) {
        val status = approval.consume(command)
        synchronized(tailscaleStatusLock) {
            require(latestTailscaleStatus === status) {
                "Tailscale command requires the latest status snapshot from this NanoKVM API"
            }
            require(status.state.allows(command)) {
                "Tailscale command $command is not valid from the observed known state"
            }
            // Consume before dispatch. Any response loss requires a fresh status read and a new
            // user confirmation rather than replaying a potentially completed network mutation.
            latestTailscaleStatus = null
        }
    }

    private suspend fun executeTailscaleCommand(
        command: NanoKvmTailscaleCommand,
        approval: NanoKvmTailscaleActionApproval,
    ) {
        consumeLatestTailscaleStatus(command, approval)
        tailscaleOperation(command) {
            postWithoutData(command.path)
        }
    }

    private suspend inline fun <reified RequestType : Any> postWithoutData(
        path: String,
        body: RequestType,
    ) {
        val request = requestBuilder(path).post(
            json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE),
        ).build()
        executeEnvelope<Unit>(request, responseSerializer = null)
    }

    private suspend inline fun <reified RequestType : Any> deleteWithoutData(
        path: String,
        body: RequestType,
    ) {
        val request = requestBuilder(path).delete(
            json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE),
        ).build()
        executeEnvelope<Unit>(request, responseSerializer = null)
    }

    private suspend fun postWithoutData(
        path: String,
        callTimeoutMillis: Long? = null,
    ) {
        val request = requestBuilder(path).post(EMPTY_JSON_BODY).build()
        executeEnvelope<Unit>(
            request,
            responseSerializer = null,
            callTimeoutMillis = callTimeoutMillis,
        )
    }

    private suspend fun <ResponseType> get(
        path: String,
        responseSerializer: kotlinx.serialization.KSerializer<ResponseType>,
    ): ResponseType = executeEnvelope(
        requestBuilder(path).get().build(),
        responseSerializer,
    ) ?: throw InvalidApiResponseException("NanoKVM response for $path did not contain data")

    private suspend inline fun <reified RequestType : Any, ResponseType> post(
        path: String,
        body: RequestType,
        responseSerializer: kotlinx.serialization.KSerializer<ResponseType>,
    ): ResponseType = executeEnvelope(
        requestBuilder(path).post(
            json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE),
        ).build(),
        responseSerializer,
    ) ?: throw InvalidApiResponseException("NanoKVM response for $path did not contain data")

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(endpoint.apiUrl(path))
        .header("Accept", "application/json")

    private suspend fun <T> executeEnvelope(
        request: Request,
        responseSerializer: kotlinx.serialization.KSerializer<T>?,
        callTimeoutMillis: Long? = null,
        transport: OkHttpClient = httpClient,
    ): T? {
        val call = transport.newCall(request)
        callTimeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        return try {
            runInterruptible(API_IO_DISPATCHER) {
                call.execute().use {
                    if (it.code == 401) {
                        invalidateSessionScopedHandles()
                        tokenStore.write(null)
                        throw AuthenticationExpiredException()
                    }
                    if (!it.isSuccessful) {
                        throw HttpResponseException(it.code)
                    }
                    val body = it.body.readUtf8WithinLimit()
                    val envelope = try {
                        json.decodeFromString<RawApiEnvelope>(body)
                    } catch (error: SerializationException) {
                        throw InvalidApiResponseException(
                            "NanoKVM returned an invalid API envelope",
                            error,
                        )
                    }
                    if (envelope.code != 0) {
                        throw ApiResponseException(envelope.code, envelope.msg)
                    }
                    if (responseSerializer == null) return@runInterruptible null
                    val data = envelope.data ?: throw InvalidApiResponseException(
                        "Successful NanoKVM response contained no data",
                    )
                    try {
                        json.decodeFromJsonElement(responseSerializer, data)
                    } catch (error: SerializationException) {
                        throw InvalidApiResponseException(
                            "NanoKVM returned invalid response data",
                            error,
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            call.cancel()
            throw error
        } catch (error: IOException) {
            val context = currentCoroutineContext()
            if (!context.isActive) {
                call.cancel()
                context.ensureActive()
            }
            throw error
        }
    }

    private fun validateScreenValue(setting: ScreenSetting, value: Int) {
        val valid = when (setting) {
            ScreenSetting.VIDEO_TYPE -> value == 0 || value == 1
            ScreenSetting.RESOLUTION -> value in setOf(0, 480, 600, 720, 1080)
            ScreenSetting.FPS -> value in 10..60
            ScreenSetting.QUALITY -> value in setOf(50, 60, 80, 100, 1000, 2000, 3000, 5000)
            ScreenSetting.GOP -> value in 1..100
        }
        require(valid) { "Unsupported ${setting.wireName} value: $value" }
    }

    companion object {
        private val API_IO_DISPATCHER = Dispatchers.IO
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val OFFLINE_UPDATE_MEDIA_TYPE = "application/gzip".toMediaType()
        private val EMPTY_JSON_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
        private const val ONLINE_UPDATE_CALL_TIMEOUT_MILLIS = 15L * 60L * 1_000L
        private const val OFFLINE_UPDATE_CALL_TIMEOUT_MILLIS = 30L * 60L * 1_000L
        private const val MISSING_WEB_TITLE_CODE = -1
    }
}

private fun IOException.isOfflineUpdateSourceFailure(): Boolean {
    var candidate: Throwable? = this
    repeat(8) {
        if (candidate is OfflineUpdateSourceIOException) return true
        candidate = candidate?.cause ?: return false
    }
    return false
}

private suspend inline fun <Result> scriptOperation(
    operation: NanoKvmScriptOperation,
    crossinline block: suspend () -> Result,
): Result = try {
    block()
} catch (error: ApiResponseException) {
    val message = "NanoKVM rejected the script operation."
    val failure = when (error.code) {
        -1 -> NanoKvmScriptFailure.Rejected(error.code, message)
        -2 -> NanoKvmScriptFailure.OperationFailed(error.code, message)
        else -> NanoKvmScriptFailure.Other(error.code, message)
    }
    // Do not retain the generic exception or appliance-controlled response text.
    throw NanoKvmScriptOperationException(operation, failure)
}

private suspend inline fun <Result> autostartOperation(
    operation: NanoKvmAutostartOperation,
    crossinline block: suspend () -> Result,
): Result = try {
    block()
} catch (error: ApiResponseException) {
    // Autostart content is executable as root on the appliance. Do not retain an untrusted server
    // message because it may echo script content or other root-equivalent material.
    throw NanoKvmAutostartOperationException(
        operation,
        NanoKvmAutostartFailure.Api(error.code),
    )
} catch (error: HttpResponseException) {
    // Do not chain the generic HTTP exception into feature diagnostics.
    throw NanoKvmAutostartOperationException(
        operation,
        NanoKvmAutostartFailure.Http(error.statusCode),
    )
} catch (_: InvalidApiResponseException) {
    throw NanoKvmAutostartOperationException(
        operation,
        NanoKvmAutostartFailure.InvalidResponse,
    )
} catch (_: IOException) {
    throw NanoKvmAutostartOperationException(
        operation,
        NanoKvmAutostartFailure.Transport,
    )
}

private inline fun <T> invalidServerData(label: String, block: () -> T): T = try {
    block()
} catch (error: IllegalArgumentException) {
    throw InvalidApiResponseException("NanoKVM returned invalid $label data", error)
}

private fun validateServerImagePath(path: String): String {
    require(path.isNotEmpty() && path.utf8Size() <= MAX_IMAGE_PATH_UTF8_BYTES) {
        "Image path is blank or too long"
    }
    require(path.startsWith("/data/")) { "Image path is outside /data" }
    require('\\' !in path && path.none(Char::isISOControl)) {
        "Image path contains unsafe characters"
    }
    val segments = path.split('/')
    require(segments.drop(1).all { it.isNotEmpty() && it != "." && it != ".." }) {
        "Image path is not canonical"
    }
    require(path.endsWith(".iso", ignoreCase = true) || path.endsWith(".img", ignoreCase = true)) {
        "Image path must end in .iso or .img"
    }
    return path
}

private fun ImageTransferResponse.toValidatedTransferStatus(): NanoKvmImageTransferStatus =
    invalidServerData("image-transfer status") {
        require(status.isNotBlank() && status.utf8Size() <= MAX_TRANSFER_STATUS_UTF8_BYTES) {
            "Transfer status is blank or too long"
        }
        require(file.utf8Size() <= MAX_TRANSFER_FILE_UTF8_BYTES) {
            "Transfer source is too long"
        }
        require(percentage.utf8Size() <= MAX_TRANSFER_PERCENTAGE_UTF8_BYTES) {
            "Transfer percentage is too long"
        }
        require(status.none(Char::isISOControl) && file.none(Char::isISOControl) &&
            percentage.none(Char::isISOControl)) {
            "Transfer fields contain control characters"
        }
        val transferState = when (status) {
            NanoKvmImageTransferState.Idle.wireValue -> NanoKvmImageTransferState.Idle
            NanoKvmImageTransferState.InProgress.wireValue -> NanoKvmImageTransferState.InProgress
            else -> NanoKvmImageTransferState.Other(status)
        }
        NanoKvmImageTransferStatus(
            state = transferState,
            source = file,
            percentageText = percentage,
            percentage = percentage.trim().removeSuffix("%")
                .takeIf { percentage.trim().endsWith('%') }
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 },
        )
    }

private fun parseWakeOnLanHistoryEntry(value: String): NanoKvmWakeOnLanEntry {
    require(value.isNotBlank() && value.utf8Size() <= MAX_WOL_HISTORY_ENTRY_UTF8_BYTES) {
        "WOL history entry is blank or too long"
    }
    require(value.none { it.isISOControl() && !it.isWhitespace() }) {
        "WOL history entry contains control characters"
    }
    val trimmed = value.trim()
    val separator = trimmed.indexOfFirst(Char::isWhitespace)
    val macValue = if (separator == -1) trimmed else trimmed.substring(0, separator)
    val nameValue = if (separator == -1) "" else trimmed.substring(separator + 1)
    return NanoKvmWakeOnLanEntry(
        mac = NanoKvmMacAddress.parse(macValue),
        name = normalizeWakeOnLanName(nameValue, allowEmpty = true).ifEmpty { null },
    )
}

private fun normalizeWakeOnLanName(value: String, allowEmpty: Boolean): String {
    require(value.utf8Size() <= MAX_WOL_NAME_UTF8_BYTES) { "WOL name is too long" }
    require(value.none { it.isISOControl() && !it.isWhitespace() }) {
        "WOL name contains control characters"
    }
    val normalized = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
    require(normalized.utf8Size() <= MAX_WOL_NAME_UTF8_BYTES) { "WOL name is too long" }
    require(allowEmpty || normalized.isNotEmpty()) { "WOL name must not be blank" }
    return normalized
}

private fun ApiResponseException.isLegacyEmptyWakeOnLanHistory(
    applicationVersion: NanoKvmApplicationVersion?,
): Boolean = code == LEGACY_EMPTY_WOL_HISTORY_CODE &&
    serverMessageKind == ApiResponseServerMessageKind.LEGACY_EMPTY_WOL_HISTORY &&
    applicationVersion != null &&
    applicationVersion <= LEGACY_EMPTY_WOL_LAST_VERSION

private const val LEGACY_EMPTY_WOL_HISTORY_CODE = -2
private val LEGACY_EMPTY_WOL_LAST_VERSION = NanoKvmApplicationVersion(2, 4, 1)

internal const val MAX_REST_RESPONSE_BYTES = 1024 * 1024

/**
 * NanoKVM tokens are printable ASCII cookie values. A 2 KiB ceiling comfortably accommodates
 * normal session tokens while keeping the complete Cookie header below common 4 KiB limits and
 * bounding the credential retained in memory and repeated on every authenticated request.
 */
internal const val MAX_SESSION_TOKEN_LENGTH = 2_048

/** Reads at most one byte beyond the accepted limit, never buffering an unbounded response. */
private fun ResponseBody.readUtf8WithinLimit(): String {
    val declaredLength = contentLength()
    if (declaredLength > MAX_REST_RESPONSE_BYTES) {
        throw InvalidApiResponseException(
            "NanoKVM REST response is $declaredLength bytes; limit is $MAX_REST_RESPONSE_BYTES",
        )
    }

    val source = source()
    if (source.request(MAX_REST_RESPONSE_BYTES.toLong() + 1L)) {
        throw InvalidApiResponseException(
            "NanoKVM REST response exceeds the $MAX_REST_RESPONSE_BYTES-byte limit",
        )
    }
    return source.buffer.readUtf8()
}

internal fun validateCookieValue(token: String) {
    require(token.isNotEmpty()) { "NanoKVM returned an empty token" }
    require(token.length <= MAX_SESSION_TOKEN_LENGTH) {
        "NanoKVM returned a token longer than the $MAX_SESSION_TOKEN_LENGTH-character limit"
    }
    require(token.none { it.code < 0x21 || it.code > 0x7e || it == ';' || it == ',' }) {
        "NanoKVM returned a token that is unsafe for a Cookie header"
    }
}
