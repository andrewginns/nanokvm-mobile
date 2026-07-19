package org.nanokvm.mobile.runtime

import java.util.concurrent.atomic.AtomicBoolean
import org.nanokvm.protocol.GpioAction
import org.nanokvm.protocol.GpioStatus
import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmClient
import org.nanokvm.protocol.NanoKvmServerCapabilities
import org.nanokvm.protocol.PasteLanguage
import org.nanokvm.protocol.ScreenSetting
import org.nanokvm.protocol.VmInfo

/**
 * Owns one authenticated origin, its in-memory token, discovery snapshot, and feature gateways.
 *
 * High-rate input and video retain direct access to [client]. Low-frequency feature work goes
 * through a scoped gateway so media, administration, and terminal APIs can be added without
 * turning the console adapter into an unstructured collection of REST calls.
 */
internal class AuthenticatedNanoKvmSession(
    val client: NanoKvmClient,
    val profileId: String,
    val authority: String,
    val vmInfo: VmInfo,
    val capabilities: NanoKvmServerCapabilities,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val console = NanoKvmConsoleFeatureGateway(client.api)
    val clipboard = NanoKvmClipboardFeatureGateway(client.api)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.forgetSession()
        client.close()
    }
}

/**
 * Owns exactly one low-frequency feature gateway for one authenticated destination generation.
 * Replacing or clearing the binding immediately makes the prior gateway unreachable to callers.
 */
internal class SessionBoundFeatureLifecycle<Feature> {
    private var installed: InstalledFeature<Feature>? = null

    @Synchronized
    fun install(
        binding: NanoKvmSessionBinding,
        factory: () -> Feature,
    ): Feature = factory().also { feature ->
        installed = InstalledFeature(binding, feature)
    }

    @Synchronized
    fun resolve(binding: NanoKvmSessionBinding): Feature? =
        installed?.takeIf { it.binding == binding }?.feature

    @Synchronized
    fun clear() {
        installed = null
    }

    @Synchronized
    fun binding(): NanoKvmSessionBinding? = installed?.binding

    private data class InstalledFeature<Feature>(
        val binding: NanoKvmSessionBinding,
        val feature: Feature,
    )
}

/** Side-effecting everyday console controls, kept separate from privileged appliance settings. */
internal class NanoKvmConsoleFeatureGateway(
    private val api: NanoKvmApi,
) {
    suspend fun updateScreen(setting: ScreenSetting, value: Int) = api.updateScreen(setting, value)

    suspend fun resetHid() = api.resetHid()

    suspend fun pressGpio(action: GpioAction, durationMillis: Long) =
        api.pressGpio(action, durationMillis)

    suspend fun gpioStatus(): GpioStatus = api.gpioStatus()
}

/** Explicit one-way HID typing. This is deliberately not named or modelled as clipboard sync. */
internal class NanoKvmClipboardFeatureGateway(
    private val api: NanoKvmApi,
) {
    suspend fun typeText(content: String, language: PasteLanguage = PasteLanguage.ENGLISH) =
        api.paste(content, language)
}
