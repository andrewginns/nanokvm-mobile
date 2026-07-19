package org.nanokvm.mobile.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Platform-light boundary used by the ViewModel before starting a LAN operation. */
fun interface LocalNetworkAccess {
    fun isGranted(): Boolean

    companion object {
        val Unrestricted = LocalNetworkAccess { true }
    }
}

class AndroidLocalNetworkAccess(context: Context) : LocalNetworkAccess {
    private val applicationContext = context.applicationContext

    override fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
