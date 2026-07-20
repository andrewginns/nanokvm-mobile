package org.nanokvm.mobile

import android.app.Application
import android.os.StrictMode

/** Process owner for application-scoped dependencies. */
class NanoKvmApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableDevelopmentStrictMode()
    }

    private fun enableDevelopmentStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectCleartextNetwork()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }
}
