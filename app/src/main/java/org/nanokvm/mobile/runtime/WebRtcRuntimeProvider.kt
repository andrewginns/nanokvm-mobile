package org.nanokvm.mobile.runtime

import org.nanokvm.video.NanoKvmVideoPreference
import org.nanokvm.video.NanoKvmWebRtcRuntime

/** Resolves the process WebRTC runtime only for an explicit WebRTC video request. */
internal class WebRtcRuntimeProvider(
    createRuntime: () -> NanoKvmWebRtcRuntime?,
) {
    private val runtime = lazy(LazyThreadSafetyMode.SYNCHRONIZED, createRuntime)

    fun resolve(preference: NanoKvmVideoPreference): NanoKvmWebRtcRuntime? = when (preference) {
        NanoKvmVideoPreference.WEBRTC -> runtime.value
        NanoKvmVideoPreference.AUTO,
        NanoKvmVideoPreference.H264,
        NanoKvmVideoPreference.MJPEG,
        -> null
    }
}
