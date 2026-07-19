package org.nanokvm.mobile

import android.content.Context
import org.nanokvm.mobile.data.AppSettingsRepository
import org.nanokvm.mobile.data.ProfileRepository
import org.nanokvm.mobile.platform.AndroidLocalNetworkAccess
import org.nanokvm.mobile.platform.AndroidClipboardGateway
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.NanoKvmConsoleBackend
import org.nanokvm.mobile.security.SavedCredentialStore
import org.nanokvm.video.NanoKvmWebRtcRuntime

/**
 * Small manual dependency container. The process owns repositories and factories; each
 * [org.nanokvm.mobile.ui.AppViewModel] owns the backend instance produced for its session.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val profileRepository: ProfileRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProfileRepository(applicationContext)
    }

    val appSettingsRepository: AppSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppSettingsRepository(applicationContext)
    }

    val savedCredentialStore: SavedCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SavedCredentialStore(applicationContext)
    }

    val localNetworkAccess: AndroidLocalNetworkAccess by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidLocalNetworkAccess(applicationContext)
    }

    val clipboardGateway: AndroidClipboardGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidClipboardGateway(applicationContext)
    }

    val backendFactory: ConsoleBackendFactory = ConsoleBackendFactory {
        NanoKvmConsoleBackend(NanoKvmWebRtcRuntime.get(applicationContext))
    }
}

fun interface ConsoleBackendFactory {
    fun create(): ConsoleBackend
}
