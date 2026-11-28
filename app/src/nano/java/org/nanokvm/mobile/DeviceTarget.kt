package org.nanokvm.mobile

import android.content.Context
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.NanoKvmConsoleBackend
import org.nanokvm.video.NanoKvmWebRtcRuntime

internal object DeviceTarget {
    const val profileName = "NanoKVM"
    const val defaultProfileId = "default-nanokvm"
    const val credentialAliasPrefix = "org.nanokvm.mobile.saved-password."
    const val credentialBinding = "nanokvm-mobile-credential-v2"
    const val supportsTotp = false

    fun createBackend(context: Context): ConsoleBackend =
        NanoKvmConsoleBackend { NanoKvmWebRtcRuntime.get(context.applicationContext) }
}
