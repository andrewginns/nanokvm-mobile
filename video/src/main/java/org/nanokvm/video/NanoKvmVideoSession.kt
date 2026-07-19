package org.nanokvm.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
    val decodeMjpegBitmaps: Boolean = true,
    val deliverMjpegJpegBytes: Boolean = false,
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

sealed interface NanoKvmVideoStatus {
    data class Connecting(val transport: NanoKvmVideoTransport) : NanoKvmVideoStatus
    data class Streaming(val transport: NanoKvmVideoTransport) : NanoKvmVideoStatus
    data class FallingBack(
        val from: NanoKvmVideoTransport,
        val to: NanoKvmVideoTransport,
        val cause: Throwable,
    ) : NanoKvmVideoStatus
    data object Stopped : NanoKvmVideoStatus
    data class Error(val transport: NanoKvmVideoTransport, val cause: Throwable) : NanoKvmVideoStatus
}

interface NanoKvmVideoListener {
    fun onStatusChanged(status: NanoKvmVideoStatus) = Unit
    fun onVideoSizeChanged(width: Int, height: Int) = Unit
    fun onWebRtcFrameRendered(timestampNs: Long) = Unit
    fun onH264FrameRendered(timestampUs: Long) = Unit
    /** Raw JPEG callback; enabled explicitly with [NanoKvmVideoConfig.deliverMjpegJpegBytes]. */
    fun onMjpegJpegFrame(jpeg: ByteArray) = Unit
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
    private var mjpegSource: MjpegStreamReader? = null
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
    private var mjpegHasRendered = false
    private var mjpegFailureClaimed = false
    private val watchdogExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "NanoKVM-video-watchdog").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }
    private val mjpegFrameDispatcher = LatestFrameDispatcher(
        executor = callbackExecutor,
        deliver = ::deliverMjpegFrame,
        discard = ::discardMjpegFrame,
    )

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

    /** Binds session lifetime and decoder Surface ownership to a TextureView. */
    fun bind(textureView: TextureView, config: NanoKvmVideoConfig = NanoKvmVideoConfig()): AutoCloseable {
        require(config.preference != NanoKvmVideoPreference.MJPEG) {
            "TextureView binding is only needed for H.264/AUTO"
        }
        val binding = TextureViewBinding(textureView, config)
        binding.attach()
        return binding
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
            onWebRtcFailure(run, surface, config, IllegalStateException("WebRTC is unavailable"))
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
                        )
                    }
                }

                override fun onFailure(cause: Throwable, responseCode: Int?) {
                    if (isCurrent(run)) onWebRtcFailure(run, surface, config, cause)
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
        notifyStatus(
            run,
            NanoKvmVideoStatus.FallingBack(
                from = NanoKvmVideoTransport.WEBRTC,
                to = NanoKvmVideoTransport.H264,
                cause = cause,
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
        webRtcWatchdog = watchdogExecutor.schedule(
            {
                onWebRtcFailure(
                    run,
                    surface,
                    config,
                    IllegalStateException(message),
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
                        onH264Failure(run, config, status.cause)
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
                    // A corrupt packet is isolated; key-frame gating protects decoder recovery.
                    dispatchForH264Run(run) {
                        listener.onFramesDropped(1, H264FrameDropReason.MALFORMED_TRANSPORT_FRAME)
                    }
                }

                override fun onClosed(code: Int, reason: String) {
                    if (isCurrent(run)) {
                        onH264Failure(
                            run,
                            config,
                            IllegalStateException("H.264 WebSocket closed ($code)"),
                        )
                    }
                }

                override fun onFailure(cause: Throwable, responseCode: Int?) {
                    if (isCurrent(run)) onH264Failure(run, config, cause)
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
        if (nextTransport == NanoKvmVideoTransport.MJPEG) {
            notifyStatus(
                run,
                NanoKvmVideoStatus.FallingBack(
                    from = NanoKvmVideoTransport.H264,
                    to = NanoKvmVideoTransport.MJPEG,
                    cause = cause,
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
            notifyStatus(run, NanoKvmVideoStatus.Error(NanoKvmVideoTransport.H264, cause))
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
        h264Watchdog = watchdogExecutor.schedule(
            {
                onH264Failure(
                    run,
                    config,
                    IllegalStateException(message),
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

    private fun startMjpeg(run: Long, config: NanoKvmVideoConfig) {
        val created = MjpegStreamReader(
            client,
            baseUrl,
            token,
            object : MjpegStreamListener {
                override fun onConnecting() {
                    notifyStatus(run, NanoKvmVideoStatus.Connecting(NanoKvmVideoTransport.MJPEG))
                }

                override fun onOpen(boundary: String) {
                    // HTTP headers prove only that the multipart response opened. Streaming is
                    // reported after a JPEG is decoded and its bitmap is posted successfully.
                }

                override fun onJpegFrame(jpeg: ByteArray) {
                    enqueueMjpegFrame(run, config, jpeg)
                }

                override fun onClosed() {
                    onMjpegFailure(run, IllegalStateException("MJPEG stream closed"))
                }

                override fun onFailure(cause: Throwable, responseCode: Int?) {
                    onMjpegFailure(run, cause)
                }
            },
            maxFrameBytes = config.maxMjpegFrameBytes,
        )
        synchronized(stateLock) {
            if (generation != run) {
                created.close()
                return
            }
            mjpegSource = created
            mjpegHasRendered = false
            mjpegFailureClaimed = false
            armMjpegWatchdogLocked(
                run,
                config.mjpegFirstFrameTimeoutMillis,
                "MJPEG stream did not render a first frame",
            )
        }
        created.start()
    }

    private fun onMjpegFrameRendered(run: Long, stallTimeoutMillis: Long) {
        val first = synchronized(stateLock) {
            if (generation != run || mjpegFailureClaimed) return
            val value = !mjpegHasRendered
            mjpegHasRendered = true
            armMjpegWatchdogLocked(
                run,
                stallTimeoutMillis,
                "MJPEG stream stopped rendering frames",
            )
            value
        }
        if (first) notifyStatus(run, NanoKvmVideoStatus.Streaming(NanoKvmVideoTransport.MJPEG))
    }

    private fun onMjpegFailure(
        run: Long,
        cause: Throwable,
        expectedWatchdogEpoch: Long? = null,
    ) {
        val oldSource: MjpegStreamReader?
        synchronized(stateLock) {
            if (
                generation != run ||
                mjpegFailureClaimed ||
                (expectedWatchdogEpoch != null && expectedWatchdogEpoch != mjpegWatchdogEpoch)
            ) return
            mjpegFailureClaimed = true
            cancelMjpegWatchdogLocked()
            oldSource = mjpegSource
            mjpegSource = null
        }
        oldSource?.stop()
        if (expectedWatchdogEpoch != null) {
            dispatchForGeneration(run) { listener.onVideoStalled(NanoKvmVideoTransport.MJPEG) }
        }
        notifyStatus(run, NanoKvmVideoStatus.Error(NanoKvmVideoTransport.MJPEG, cause))
    }

    private fun armMjpegWatchdogLocked(run: Long, delayMillis: Long, message: String) {
        cancelMjpegWatchdogLocked()
        val watchdogEpoch = mjpegWatchdogEpoch
        mjpegWatchdog = watchdogExecutor.schedule(
            {
                onMjpegFailure(
                    run,
                    IllegalStateException(message),
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
        val oldMjpeg: MjpegStreamReader?
        val oldRenderer: MediaCodecH264Renderer?
        val stoppedRun = synchronized(stateLock) {
            generation++
            cancelWebRtcWatchdogLocked()
            cancelH264WatchdogLocked()
            cancelMjpegWatchdogLocked()
            oldWebRtc = webRtcSource
            oldH264 = h264Source
            oldMjpeg = mjpegSource
            oldRenderer = renderer
            webRtcSource = null
            h264Source = null
            mjpegSource = null
            renderer = null
            webRtcHasRendered = false
            webRtcFailureClaimed = false
            h264HasRendered = false
            h264FailureClaimed = false
            mjpegHasRendered = false
            mjpegFailureClaimed = false
            generation
        }
        mjpegFrameDispatcher.clear()
        oldWebRtc?.close()
        oldH264?.stop()
        oldMjpeg?.stop()
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

    private fun enqueueMjpegFrame(run: Long, config: NanoKvmVideoConfig, jpeg: ByteArray) {
        if (!isCurrent(run)) return
        val bitmap = if (config.decodeMjpegBitmaps) {
            MjpegBitmapDecoder.decode(jpeg, config.mjpegBitmapOptions)
        } else {
            null
        }
        if (!isCurrent(run)) {
            bitmap?.recycle()
            return
        }
        val rawJpeg = if (config.deliverMjpegJpegBytes) jpeg else null
        if (rawJpeg == null && bitmap == null) return
        mjpegFrameDispatcher.offer(
            MjpegDelivery(run, rawJpeg, bitmap, config.mjpegStallTimeoutMillis),
        )
    }

    private fun deliverMjpegFrame(frame: MjpegDelivery) {
        var bitmapTransferred = false
        var rawDelivered = false
        var bitmapRendered: Boolean? = null
        try {
            if (!isCurrent(frame.run)) return
            frame.jpeg?.let {
                listener.onMjpegJpegFrame(it)
                rawDelivered = true
            }
            // A raw callback may synchronously stop or replace this session.
            if (!isCurrent(frame.run)) return
            frame.bitmap?.let { bitmap ->
                val rendered = listener.onMjpegBitmapFrame(bitmap)
                bitmapRendered = rendered
                bitmapTransferred = rendered
            }
            // When bitmap display is enabled, raw-byte delivery must not mask a failed Surface
            // post. A raw-only consumer still gets delivery-based liveness semantics.
            val delivered = bitmapRendered ?: rawDelivered
            if (delivered && isCurrent(frame.run)) {
                onMjpegFrameRendered(frame.run, frame.stallTimeoutMillis)
            }
        } finally {
            if (!bitmapTransferred) recycle(frame.bitmap)
        }
    }

    private fun discardMjpegFrame(frame: MjpegDelivery) {
        recycle(frame.bitmap)
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private inner class TextureViewBinding(
        private val textureView: TextureView,
        private val config: NanoKvmVideoConfig,
    ) : TextureView.SurfaceTextureListener, AutoCloseable {
        private var surface: Surface? = null
        private var attached = false

        fun attach() {
            if (attached) return
            attached = true
            textureView.surfaceTextureListener = this
            if (textureView.isAvailable) {
                textureView.surfaceTexture?.let { onSurfaceTextureAvailable(it, textureView.width, textureView.height) }
            }
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (!attached) return
            surface?.release()
            Surface(texture).also {
                surface = it
                this@NanoKvmVideoSession.start(it, config)
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            this@NanoKvmVideoSession.stop()
            surface?.release()
            surface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

        override fun close() {
            if (!attached) return
            attached = false
            if (textureView.surfaceTextureListener === this) textureView.surfaceTextureListener = null
            this@NanoKvmVideoSession.stop()
            surface?.release()
            surface = null
        }
    }

    private data class MjpegDelivery(
        val run: Long,
        val jpeg: ByteArray?,
        val bitmap: Bitmap?,
        val stallTimeoutMillis: Long,
    )
}
