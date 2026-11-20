package org.nanokvm.video

import android.graphics.Bitmap
import android.view.Surface
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class NanoKvmVideoPreference {
    AUTO,
    WEBRTC,
    H264,
    MJPEG,
}

enum class NanoKvmVideoTransport {
    WEBRTC,
    H264,
    MJPEG,
}

/** Ordered, fresh transport attempts for each user preference. */
internal fun NanoKvmVideoPreference.transportChain(): List<NanoKvmVideoTransport> = when (this) {
    NanoKvmVideoPreference.AUTO -> listOf(
        NanoKvmVideoTransport.H264,
        NanoKvmVideoTransport.MJPEG,
    )
    NanoKvmVideoPreference.WEBRTC -> listOf(
        NanoKvmVideoTransport.WEBRTC,
        NanoKvmVideoTransport.H264,
        NanoKvmVideoTransport.MJPEG,
    )
    NanoKvmVideoPreference.H264 -> listOf(NanoKvmVideoTransport.H264)
    NanoKvmVideoPreference.MJPEG -> listOf(NanoKvmVideoTransport.MJPEG)
}

data class NanoKvmVideoConfig(
    val preference: NanoKvmVideoPreference = NanoKvmVideoPreference.AUTO,
    val decoder: H264DecoderConfig = H264DecoderConfig(),
    val mjpegBitmapOptions: MjpegBitmapDecodeOptions = MjpegBitmapDecodeOptions(),
    val maxMjpegFrameBytes: Int = MjpegMultipartParser.DEFAULT_MAX_FRAME_BYTES,
    val webRtcFirstFrameTimeoutMillis: Long = 7_000L,
    val webRtcStallTimeoutMillis: Long = 5_000L,
    val h264FirstFrameTimeoutMillis: Long = 5_000L,
    val h264StallTimeoutMillis: Long = 5_000L,
    val mjpegFirstFrameTimeoutMillis: Long = 5_000L,
    val mjpegStallTimeoutMillis: Long = 5_000L,
) {
    init {
        require(maxMjpegFrameBytes > 0) { "Maximum MJPEG frame size must be positive" }
        require(webRtcFirstFrameTimeoutMillis >= 1_000L) {
            "WebRTC first-frame timeout must be at least one second"
        }
        require(webRtcStallTimeoutMillis >= 1_000L) {
            "WebRTC stall timeout must be at least one second"
        }
        require(h264FirstFrameTimeoutMillis >= 1_000L) {
            "H.264 first-frame timeout must be at least one second"
        }
        require(h264StallTimeoutMillis >= 1_000L) {
            "H.264 stall timeout must be at least one second"
        }
        require(mjpegFirstFrameTimeoutMillis >= 1_000L) {
            "MJPEG first-frame timeout must be at least one second"
        }
        require(mjpegStallTimeoutMillis >= 1_000L) {
            "MJPEG stall timeout must be at least one second"
        }
    }
}

sealed interface NanoKvmVideoFailureReason {
    data object Unavailable : NanoKvmVideoFailureReason
    data object FirstFrameTimeout : NanoKvmVideoFailureReason
    data object Stalled : NanoKvmVideoFailureReason
    data object Decoder : NanoKvmVideoFailureReason
    data object Transport : NanoKvmVideoFailureReason
    data class HttpRejected(val statusCode: Int) : NanoKvmVideoFailureReason
}

sealed interface NanoKvmVideoStatus {
    data class Connecting(val transport: NanoKvmVideoTransport) : NanoKvmVideoStatus
    data class Streaming(val transport: NanoKvmVideoTransport) : NanoKvmVideoStatus
    data class FallingBack(
        val from: NanoKvmVideoTransport,
        val to: NanoKvmVideoTransport,
        val cause: Throwable,
        val reason: NanoKvmVideoFailureReason,
    ) : NanoKvmVideoStatus
    data object Stopped : NanoKvmVideoStatus
    data class Error(
        val transport: NanoKvmVideoTransport,
        val cause: Throwable,
        val reason: NanoKvmVideoFailureReason,
    ) : NanoKvmVideoStatus
}

interface NanoKvmVideoListener {
    fun onStatusChanged(status: NanoKvmVideoStatus) = Unit
    fun onVideoSizeChanged(width: Int, height: Int) = Unit
    fun onWebRtcFrameRendered(timestampNs: Long) = Unit
    fun onH264FrameRendered(timestampUs: Long) = Unit
    /**
     * Return true only when the bitmap was posted to the display target; the receiver then owns
     * its lifecycle. Return false when it was not consumed so the session recycles it and can
     * report a display failure.
     */
    fun onMjpegBitmapFrame(bitmap: Bitmap): Boolean = false
    fun onFramesDropped(count: Int, reason: H264FrameDropReason) = Unit
    /** Emitted only when the active transport's rendered-frame watchdog actually expires. */
    fun onVideoStalled(transport: NanoKvmVideoTransport) = Unit
}

/**
 * One cohesive video lifecycle. AUTO preserves direct H.264 -> MJPEG; the explicit WEBRTC
 * preference uses a fresh WebRTC -> direct H.264 -> MJPEG chain.
 * Callbacks are delivered on [callbackExecutor]. A Surface passed to [start] remains caller-owned.
 */
class NanoKvmVideoSession(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String,
    private val listener: NanoKvmVideoListener,
    private val webRtcRuntime: NanoKvmWebRtcRuntime? = null,
    private val callbackExecutor: Executor = Executor(Runnable::run),
) : AutoCloseable {
    private val stateLock = Any()
    private var generation = 0L
    private var webRtcSource: NanoKvmWebRtcSource? = null
    private var h264Source: DirectH264WebSocketSource? = null
    private var mjpegAttempt: MjpegAttempt? = null
    private var renderer: MediaCodecH264Renderer? = null
    private var webRtcWatchdog: ScheduledFuture<*>? = null
    private var h264Watchdog: ScheduledFuture<*>? = null
    private var mjpegWatchdog: ScheduledFuture<*>? = null
    private var webRtcWatchdogEpoch = 0L
    private var h264WatchdogEpoch = 0L
    private var mjpegWatchdogEpoch = 0L
    private var webRtcHasRendered = false
    private var webRtcFailureClaimed = false
    private var h264HasRendered = false
    private var h264FailureClaimed = false
    private val watchdogExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "NanoKVM-video-watchdog").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }

    fun start(surface: Surface, config: NanoKvmVideoConfig = NanoKvmVideoConfig()) {
        require(config.preference != NanoKvmVideoPreference.MJPEG) {
            "Use startMjpeg when no decoder Surface is required"
        }
        val decoderReleased = stopInternal(notify = false)
        val run = synchronized(stateLock) {
            generation++
            generation
        }
        decoderReleased.whenComplete { _, _ ->
            if (isCurrent(run)) {
                when (config.preference.transportChain().first()) {
                    NanoKvmVideoTransport.WEBRTC -> startWebRtc(run, surface, config)
                    NanoKvmVideoTransport.H264 -> startH264(run, surface, config)
                    NanoKvmVideoTransport.MJPEG -> error("MJPEG does not use a decoder Surface")
                }
            }
        }
    }

    fun startMjpeg(config: NanoKvmVideoConfig = NanoKvmVideoConfig(NanoKvmVideoPreference.MJPEG)) {
        val decoderReleased = stopInternal(notify = false)
        val normalized = config.copy(preference = NanoKvmVideoPreference.MJPEG)
        val run = synchronized(stateLock) {
            generation++
            generation
        }
        decoderReleased.whenComplete { _, _ ->
            if (isCurrent(run)) startMjpeg(run, normalized)
        }
    }

    fun stop() {
        stopInternal(notify = true)
    }

    /** Stops all transports and completes after the decoder releases its native Surface reference. */
    fun closeAndAwaitDecoderRelease(): CompletableFuture<Unit> {
        val decoderReleased = stopInternal(notify = true)
        watchdogExecutor.shutdownNow()
        return decoderReleased
    }

    override fun close() {
        closeAndAwaitDecoderRelease()
    }

    private fun startWebRtc(run: Long, surface: Surface, config: NanoKvmVideoConfig) {
        val runtime = webRtcRuntime
        if (runtime == null) {
            synchronized(stateLock) {
                if (generation != run) return
                webRtcHasRendered = false
                webRtcFailureClaimed = false
            }
            notifyStatus(run, NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.WEBRTC))
            onWebRtcFailure(
                run, surface, config,
                IllegalStateException("WebRTC is unavailable"),
                NanoKvmVideoFailureReason.Unavailable,
            )
            return
        }
        val created = NanoKvmWebRtcSource(
            client = client,
            baseUrl = baseUrl,
            token = token,
            target = NanoKvmWebRtcRuntime.SurfaceRenderTarget(surface),
            peerFactory = runtime.peerFactory,
            listener = object : NanoKvmWebRtcSourceListener {
                override fun onConnecting() {
                    notifyWebRtcStatus(
                        run,
                        NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.WEBRTC),
                    )
                }

                override fun onFrameRendered(timestampNs: Long) {
                    onWebRtcFrameRendered(run, surface, config, timestampNs)
                }

                override fun onVideoSizeChanged(width: Int, height: Int) {
                    dispatchForWebRtcRun(run) { listener.onVideoSizeChanged(width, height) }
                }

                override fun onClosed(code: Int, reason: String) {
                    if (isCurrent(run)) {
                        onWebRtcFailure(
                            run,
                            surface,
                            config,
                            IllegalStateException("WebRTC signaling closed ($code)"),
                            NanoKvmVideoFailureReason.Transport,
                        )
                    }
                }

                override fun onFailure(cause: Throwable, responseCode: Int?) {
                    onWebRtcFailure(run, surface, config, cause, transportFailureReason(responseCode))
                }
            },
        )
        synchronized(stateLock) {
            if (generation != run) {
                created.close()
                return
            }
            webRtcSource = created
            webRtcHasRendered = false
            webRtcFailureClaimed = false
            armWebRtcWatchdogLocked(
                run,
                surface,
                config,
                config.webRtcFirstFrameTimeoutMillis,
                "WebRTC did not render a first frame",
            )
        }
        created.start()
    }

    private fun onWebRtcFrameRendered(
        run: Long,
        surface: Surface,
        config: NanoKvmVideoConfig,
        timestampNs: Long,
    ) {
        val firstFrame = synchronized(stateLock) {
            if (generation != run || webRtcFailureClaimed) return
            val first = !webRtcHasRendered
            webRtcHasRendered = true
            armWebRtcWatchdogLocked(
                run,
                surface,
                config,
                config.webRtcStallTimeoutMillis,
                "WebRTC stopped rendering frames",
            )
            first
        }
        if (firstFrame) {
            notifyWebRtcStatus(
                run,
                NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.WEBRTC),
            )
        }
        dispatchForWebRtcRun(run) { listener.onWebRtcFrameRendered(timestampNs) }
    }

    private fun onWebRtcFailure(
        run: Long,
        surface: Surface,
        config: NanoKvmVideoConfig,
        cause: Throwable,
        reason: NanoKvmVideoFailureReason,
        expectedWatchdogEpoch: Long? = null,
    ) {
        val oldSource: NanoKvmWebRtcSource?
        synchronized(stateLock) {
            if (
                generation != run ||
                webRtcFailureClaimed ||
                (expectedWatchdogEpoch != null && expectedWatchdogEpoch != webRtcWatchdogEpoch)
            ) return
            webRtcFailureClaimed = true
            cancelWebRtcWatchdogLocked()
            oldSource = webRtcSource
            webRtcSource = null
        }
        oldSource?.close()
        if (expectedWatchdogEpoch != null) {
            dispatchForGeneration(run) { listener.onVideoStalled(NanoKvmVideoTransport.WEBRTC) }
        }
        if (reason == NanoKvmVideoFailureReason.HttpRejected(401)) {
            notifyStatus(run, NanoKvmVideoStatus.Error(NanoKvmVideoTransport.WEBRTC, cause, reason))
            return
        }
        notifyStatus(
            run,
            NanoKvmVideoStatus.FallingBack(
                from = NanoKvmVideoTransport.WEBRTC,
                to = NanoKvmVideoTransport.H264,
                cause = cause,
                reason = reason,
            ),
        )
        if (isCurrent(run)) startH264(run, surface, config)
    }

    private fun armWebRtcWatchdogLocked(
        run: Long,
        surface: Surface,
        config: NanoKvmVideoConfig,
        delayMillis: Long,
        message: String,
    ) {
        cancelWebRtcWatchdogLocked()
        val watchdogEpoch = webRtcWatchdogEpoch
        val reason = frameTimeoutReason(webRtcHasRendered)
        webRtcWatchdog = watchdogExecutor.schedule(
            {
                onWebRtcFailure(
                    run,
                    surface,
                    config,
                    IllegalStateException(message),
                    reason,
                    expectedWatchdogEpoch = watchdogEpoch,
                )
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelWebRtcWatchdogLocked() {
        webRtcWatchdogEpoch++
        webRtcWatchdog?.cancel(false)
        webRtcWatchdog = null
    }

    private fun startH264(run: Long, surface: Surface, config: NanoKvmVideoConfig) {
        val createdRenderer = MediaCodecH264Renderer(
            listener = object : H264RendererListener {
                override fun onStatusChanged(status: H264RendererStatus) {
                    if (!isCurrent(run)) return
                    if (status is H264RendererStatus.Error) {
                        onH264Failure(run, config, status.cause, NanoKvmVideoFailureReason.Decoder)
                    }
                }

                override fun onVideoSizeChanged(width: Int, height: Int) {
                    dispatchForH264Run(run) { listener.onVideoSizeChanged(width, height) }
                }

                override fun onFrameRendered(timestampUs: Long) {
                    onH264FrameRendered(run, config, timestampUs)
                }

                override fun onFramesDropped(count: Int, reason: H264FrameDropReason) {
                    dispatchForH264Run(run) { listener.onFramesDropped(count, reason) }
                }
            },
        )
        val createdSource = DirectH264WebSocketSource(
            client,
            baseUrl,
            token,
            object : DirectH264SourceListener {
                override fun onConnecting() {
                    notifyH264Status(run, NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.H264))
                }

                override fun onOpen() {
                    // A successful handshake is not proof of usable video. Streaming is reported
                    // only after MediaCodec renders the first access unit.
                }

                override fun onFrame(frame: H264AccessUnit) {
                    if (isH264Active(run)) createdRenderer.offer(frame)
                }

                override fun onMalformedFrame(cause: IllegalArgumentException) {
                    if (isH264Active(run)) createdRenderer.onMalformedFrame()
                }

                override fun onClosed(code: Int, reason: String) {
                    if (isCurrent(run)) {
                        onH264Failure(
                            run,
                            config,
                            IllegalStateException("H.264 WebSocket closed ($code)"),
                            NanoKvmVideoFailureReason.Transport,
                        )
                    }
                }

                override fun onFailure(cause: Throwable, responseCode: Int?) {
                    onH264Failure(run, config, cause, transportFailureReason(responseCode))
                }
            },
            maxAccessUnitBytes = config.decoder.maxInputSizeBytes,
        )
        synchronized(stateLock) {
            if (generation != run) {
                createdRenderer.close()
                createdSource.close()
                return
            }
            renderer = createdRenderer
            h264Source = createdSource
            h264HasRendered = false
            h264FailureClaimed = false
            armH264WatchdogLocked(
                run,
                config,
                config.h264FirstFrameTimeoutMillis,
                "H.264 stream did not render a first frame",
            )
        }
        createdRenderer.start(surface, config.decoder)
        createdSource.start()
    }

    private fun onH264FrameRendered(run: Long, config: NanoKvmVideoConfig, timestampUs: Long) {
        val firstFrame = synchronized(stateLock) {
            if (generation != run || h264FailureClaimed) return
            val first = !h264HasRendered
            h264HasRendered = true
            armH264WatchdogLocked(
                run,
                config,
                config.h264StallTimeoutMillis,
                "H.264 stream stopped rendering frames",
            )
            first
        }
        if (firstFrame) notifyH264Status(run, NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.H264))
        dispatchForH264Run(run) { listener.onH264FrameRendered(timestampUs) }
    }

    private fun onH264Failure(
        run: Long,
        config: NanoKvmVideoConfig,
        cause: Throwable,
        reason: NanoKvmVideoFailureReason,
        expectedWatchdogEpoch: Long? = null,
    ) {
        val oldSource: DirectH264WebSocketSource?
        val oldRenderer: MediaCodecH264Renderer?
        synchronized(stateLock) {
            if (
                generation != run ||
                h264FailureClaimed ||
                (expectedWatchdogEpoch != null && expectedWatchdogEpoch != h264WatchdogEpoch)
            ) return
            // Claim the transition before leaving the lock. WebSocket, codec, and watchdog
            // callbacks can race, but exactly one may tear down H.264 or start MJPEG.
            h264FailureClaimed = true
            cancelH264WatchdogLocked()
            oldSource = h264Source
            oldRenderer = renderer
            h264Source = null
            renderer = null
        }
        oldSource?.stop()
        val decoderReleased = oldRenderer?.stopAndAwaitRelease()
        if (expectedWatchdogEpoch != null) {
            dispatchForGeneration(run) { listener.onVideoStalled(NanoKvmVideoTransport.H264) }
        }

        val h264Index = config.preference.transportChain().indexOf(NanoKvmVideoTransport.H264)
        val nextTransport = config.preference.transportChain().getOrNull(h264Index + 1)
        if (nextTransport == NanoKvmVideoTransport.MJPEG &&
            reason != NanoKvmVideoFailureReason.HttpRejected(401)
        ) {
            notifyStatus(
                run,
                NanoKvmVideoStatus.FallingBack(
                    from = NanoKvmVideoTransport.H264,
                    to = NanoKvmVideoTransport.MJPEG,
                    cause = cause,
                    reason = reason,
                ),
            )
            if (decoderReleased == null) {
                startMjpeg(run, config)
            } else {
                decoderReleased.whenComplete { _, _ ->
                    if (isCurrent(run)) startMjpeg(run, config)
                }
            }
        } else {
            // A MediaCodec in its error state must not keep receiving frames. Reconnect is an
            // explicit fresh decoder/session attempt for the H.264-only preference.
            notifyStatus(run, NanoKvmVideoStatus.Error(NanoKvmVideoTransport.H264, cause, reason))
        }
    }

    private fun armH264WatchdogLocked(
        run: Long,
        config: NanoKvmVideoConfig,
        delayMillis: Long,
        message: String,
    ) {
        cancelH264WatchdogLocked()
        val watchdogEpoch = h264WatchdogEpoch
        val reason = frameTimeoutReason(h264HasRendered)
        h264Watchdog = watchdogExecutor.schedule(
            {
                onH264Failure(
                    run,
                    config,
                    IllegalStateException(message),
                    reason,
                    expectedWatchdogEpoch = watchdogEpoch,
                )
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelH264WatchdogLocked() {
        h264WatchdogEpoch++
        h264Watchdog?.cancel(false)
        h264Watchdog = null
    }

    private fun startMjpeg(
        run: Long,
        config: NanoKvmVideoConfig,
        retryOnFirstFrameTimeout: Boolean = config.preference != NanoKvmVideoPreference.MJPEG,
    ) {
        val created = MjpegAttempt(run, config, retryOnFirstFrameTimeout)
        synchronized(stateLock) {
            if (generation != run) return
            mjpegAttempt = created
            armMjpegWatchdogLocked(
                created,
                config.mjpegFirstFrameTimeoutMillis,
                "MJPEG stream did not render a first frame",
            )
        }
        created.reader.start()
        // Stop may have detached the attempt before its reader finished starting.
        if (!isMjpegActive(created)) created.close()
    }

    private fun onMjpegFrameRendered(attempt: MjpegAttempt) {
        val first = synchronized(stateLock) {
            if (mjpegAttempt !== attempt) return
            val value = !attempt.hasRendered
            attempt.hasRendered = true
            armMjpegWatchdogLocked(
                attempt,
                attempt.config.mjpegStallTimeoutMillis,
                "MJPEG stream stopped rendering frames",
            )
            value
        }
        if (first) notifyMjpegStatus(attempt, NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.MJPEG))
    }

    private fun onMjpegFailure(
        attempt: MjpegAttempt,
        cause: Throwable,
        reason: NanoKvmVideoFailureReason,
        expectedWatchdogEpoch: Long? = null,
    ) {
        synchronized(stateLock) {
            if (
                mjpegAttempt !== attempt ||
                (expectedWatchdogEpoch != null && expectedWatchdogEpoch != mjpegWatchdogEpoch)
            ) return
            cancelMjpegWatchdogLocked()
            mjpegAttempt = null
        }
        attempt.close()
        val run = attempt.run
        if (expectedWatchdogEpoch != null) {
            dispatchForGeneration(run) { listener.onVideoStalled(NanoKvmVideoTransport.MJPEG) }
        }
        if (attempt.retryOnFirstFrameTimeout && reason == NanoKvmVideoFailureReason.FirstFrameTimeout) {
            startMjpeg(run, attempt.config, retryOnFirstFrameTimeout = false)
        } else {
            notifyStatus(run, NanoKvmVideoStatus.Error(NanoKvmVideoTransport.MJPEG, cause, reason))
        }
    }

    private fun armMjpegWatchdogLocked(attempt: MjpegAttempt, delayMillis: Long, message: String) {
        cancelMjpegWatchdogLocked()
        val watchdogEpoch = mjpegWatchdogEpoch
        val reason = frameTimeoutReason(attempt.hasRendered)
        mjpegWatchdog = watchdogExecutor.schedule(
            {
                onMjpegFailure(
                    attempt,
                    IllegalStateException(message),
                    reason,
                    expectedWatchdogEpoch = watchdogEpoch,
                )
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelMjpegWatchdogLocked() {
        mjpegWatchdogEpoch++
        mjpegWatchdog?.cancel(false)
        mjpegWatchdog = null
    }

    private fun stopInternal(notify: Boolean): CompletableFuture<Unit> {
        val oldWebRtc: NanoKvmWebRtcSource?
        val oldH264: DirectH264WebSocketSource?
        val oldMjpeg: MjpegAttempt?
        val oldRenderer: MediaCodecH264Renderer?
        val stoppedRun = synchronized(stateLock) {
            generation++
            cancelWebRtcWatchdogLocked()
            cancelH264WatchdogLocked()
            cancelMjpegWatchdogLocked()
            oldWebRtc = webRtcSource
            oldH264 = h264Source
            oldMjpeg = mjpegAttempt
            oldRenderer = renderer
            webRtcSource = null
            h264Source = null
            mjpegAttempt = null
            renderer = null
            webRtcHasRendered = false
            webRtcFailureClaimed = false
            h264HasRendered = false
            h264FailureClaimed = false
            generation
        }
        oldWebRtc?.close()
        oldH264?.stop()
        oldMjpeg?.close()
        val decoderReleased = oldRenderer?.stopAndAwaitRelease()
            ?: CompletableFuture.completedFuture(Unit)
        if (notify) notifyStatus(stoppedRun, NanoKvmVideoStatus.Stopped)
        return decoderReleased
    }

    private fun isCurrent(run: Long): Boolean = synchronized(stateLock) { generation == run }

    private fun isWebRtcActive(run: Long): Boolean = synchronized(stateLock) {
        generation == run && !webRtcFailureClaimed && webRtcSource != null
    }

    private fun isH264Active(run: Long): Boolean = synchronized(stateLock) {
        generation == run && !h264FailureClaimed && renderer != null
    }

    private fun isMjpegActive(attempt: MjpegAttempt): Boolean = synchronized(stateLock) {
        mjpegAttempt === attempt
    }

    private fun notifyMjpegStatus(attempt: MjpegAttempt, status: NanoKvmVideoStatus) =
        dispatchForGeneration(attempt.run) {
            if (isMjpegActive(attempt)) listener.onStatusChanged(status)
        }

    private fun notifyStatus(run: Long, status: NanoKvmVideoStatus) =
        dispatchForGeneration(run) { listener.onStatusChanged(status) }

    private fun notifyH264Status(run: Long, status: NanoKvmVideoStatus) =
        dispatchForH264Run(run) { listener.onStatusChanged(status) }

    private fun notifyWebRtcStatus(run: Long, status: NanoKvmVideoStatus) =
        dispatchForWebRtcRun(run) { listener.onStatusChanged(status) }

    private fun dispatchForGeneration(run: Long, block: () -> Unit) {
        try {
            callbackExecutor.execute {
                if (isCurrent(run)) runCatching(block)
            }
        } catch (_: Throwable) {
            // The owner has shut its callback executor down.
        }
    }

    private fun dispatchForH264Run(run: Long, block: () -> Unit) {
        try {
            callbackExecutor.execute {
                if (isH264Active(run)) runCatching(block)
            }
        } catch (_: Throwable) {
            // The owner has shut its callback executor down.
        }
    }

    private fun dispatchForWebRtcRun(run: Long, block: () -> Unit) {
        try {
            callbackExecutor.execute {
                if (isWebRtcActive(run)) runCatching(block)
            }
        } catch (_: Throwable) {
            // The owner has shut its callback executor down.
        }
    }

    private inner class MjpegAttempt(
        val run: Long,
        val config: NanoKvmVideoConfig,
        val retryOnFirstFrameTimeout: Boolean,
    ) : MjpegStreamListener {
        // Accessed only under the session state lock.
        var hasRendered = false
        val reader = MjpegStreamReader(client, baseUrl, token, this, config.maxMjpegFrameBytes)
        private val frames = LatestFrameDispatcher(
            executor = callbackExecutor,
            deliver = ::deliver,
            discard = ::recycle,
        )

        override fun onConnecting() {
            notifyMjpegStatus(this, NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.MJPEG))
        }

        override fun onJpegFrame(jpeg: ByteArray) {
            if (!isMjpegActive(this)) return
            val bitmap = MjpegBitmapDecoder.decode(jpeg, config.mjpegBitmapOptions) ?: return
            if (isMjpegActive(this)) frames.offer(bitmap) else recycle(bitmap)
        }

        override fun onClosed() {
            onMjpegFailure(
                this,
                IllegalStateException("MJPEG stream closed"),
                NanoKvmVideoFailureReason.Transport,
            )
        }

        override fun onFailure(cause: Throwable, responseCode: Int?) {
            val rejection = responseCode?.takeUnless { it in 200..299 }
            onMjpegFailure(this, cause, transportFailureReason(rejection))
        }

        fun close() {
            reader.stop()
            frames.clear()
        }

        private fun deliver(bitmap: Bitmap) {
            var bitmapTransferred = false
            try {
                if (!isMjpegActive(this)) return
                bitmapTransferred = listener.onMjpegBitmapFrame(bitmap)
                // The display callback can synchronously stop or replace this attempt.
                if (bitmapTransferred) onMjpegFrameRendered(this)
            } finally {
                if (!bitmapTransferred) recycle(bitmap)
            }
        }

        private fun recycle(bitmap: Bitmap) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private fun transportFailureReason(responseCode: Int?): NanoKvmVideoFailureReason =
    if (responseCode == null) NanoKvmVideoFailureReason.Transport
    else NanoKvmVideoFailureReason.HttpRejected(responseCode)

private fun frameTimeoutReason(hasRendered: Boolean): NanoKvmVideoFailureReason =
    if (hasRendered) NanoKvmVideoFailureReason.Stalled
    else NanoKvmVideoFailureReason.FirstFrameTimeout
