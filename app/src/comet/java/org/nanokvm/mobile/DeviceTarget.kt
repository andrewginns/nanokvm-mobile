package org.nanokvm.mobile

import android.content.Context
import org.nanokvm.mobile.runtime.ConsoleBackend
import org.nanokvm.mobile.runtime.CometConsoleBackend

internal object DeviceTarget {
    const val profileName = "Comet"
    const val defaultProfileId = "default-comet"
    const val credentialAliasPrefix = "org.cometkvm.mobile.saved-password."
    const val credentialBinding = "comet-mobile-credential-v1"
    const val supportsTotp = true

    fun createBackend(@Suppress("UNUSED_PARAMETER") context: Context): ConsoleBackend =
        CometConsoleBackend()
}
