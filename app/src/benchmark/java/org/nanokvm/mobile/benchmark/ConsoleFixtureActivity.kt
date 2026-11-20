package org.nanokvm.mobile.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.clipboard.ClipboardGateway
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.runtime.AdministrationUiState
import org.nanokvm.mobile.runtime.BackendSession
import org.nanokvm.mobile.runtime.ConnectionState
import org.nanokvm.mobile.runtime.ConsoleFeatureBundle
import org.nanokvm.mobile.runtime.NanoKvmConsoleBackend
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateFeatureOwner
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdateGateway
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePayload
import org.nanokvm.mobile.runtime.NanoKvmOfflineUpdatePort
import org.nanokvm.mobile.runtime.NanoKvmSessionBinding
import org.nanokvm.mobile.ui.screens.ConsoleScreen
import org.nanokvm.mobile.ui.screens.ConsoleSessionDraftOwner
import org.nanokvm.mobile.ui.theme.NanoKvmTheme

/** Predictable console surface in the local benchmark APK, backed by no network connection. */
class ConsoleFixtureActivity : ComponentActivity() {
    private lateinit var state: ConsoleFixtureState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = ViewModelProvider(this)[ConsoleFixtureState::class.java]
        setContent {
            NanoKvmTheme(useDynamicColor = false) {
                ConsoleScreen(
                    profile = state.profile,
                    session = state.session,
                    input = state.backend,
                    videoSurface = state.backend,
                    features = state.features,
                    clipboardGateway = ClipboardGateway { ClipboardReadResult.Unavailable },
                    onSharedPasteConsumed = {},
                    sessionDraftOwner = state.drafts,
                    sensitiveWorkGeneration = state.sensitiveGeneration,
                    readSensitiveWorkGeneration = { state.sensitiveGeneration },
                    onScrollSensitivityChange = {},
                    onMjpegFrameDetectionEnabledChange = {},
                    onDisconnect = ::finish,
                    onPasswordChange = { _, _, secret, _ -> secret.fill('\u0000') },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        state.gateway.setForeground(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            state.sensitiveGeneration++
            state.drafts.offlineDocumentHandoff.clear()
            state.drafts.clear(keepOfflinePicker = true)
            state.gateway.setForeground(false)
        }
        super.onStop()
    }
}

internal class ConsoleFixtureState : ViewModel(), NanoKvmOfflineUpdateFeatureOwner {
    internal val drafts = ConsoleSessionDraftOwner()
    internal val backend = NanoKvmConsoleBackend()
    internal val profile = HostProfile(id = "benchmark-console", name = "Lab NanoKVM", host = "192.0.2.44")
    internal val session = BackendSession(
        connection = ConnectionState.Connected,
        sessionGeneration = 7L,
        administration = AdministrationUiState(available = true),
    )
    private val binding = NanoKvmSessionBinding(profile.id, profile.authority, 7L)
    internal val gateway = NanoKvmOfflineUpdateGateway(
        port = object : NanoKvmOfflineUpdatePort {
            override suspend fun upload(
                payload: NanoKvmOfflineUpdatePayload,
                onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
            ) = error("The fixture must never dispatch an update")
        },
        binding = binding,
        currentBinding = { binding },
        scope = viewModelScope,
        destinationAuthority = binding.authority,
        installedVersion = "2.4.3",
        supported = true,
    )
    internal var sensitiveGeneration by mutableLongStateOf(0L)
    internal val features = ConsoleFeatureBundle(
        core = backend,
        administration = backend,
        offlineUpdate = this,
    )

    override fun setOfflineUpdateSurfaceVisible(visible: Boolean) = gateway.setSurfaceVisible(visible)

    override fun currentOfflineUpdateGateway(): NanoKvmOfflineUpdateGateway = gateway

    override fun onCleared() {
        gateway.close()
        drafts.close()
        backend.close()
    }
}
