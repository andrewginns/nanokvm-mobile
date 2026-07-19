package org.nanokvm.mobile

import android.app.Application

/** Process owner for application-scoped dependencies. */
class NanoKvmApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }
}
