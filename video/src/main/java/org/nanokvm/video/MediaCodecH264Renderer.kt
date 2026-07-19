package org.nanokvm.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class H264DecoderConfig(
    val expectedWidth: Int = 1920,
    val expectedHeight: Int = 1080,
    val maxInputSizeBytes: Int = NanoKvmH264FrameParser.DEFAULT_MAX_ACCESS_UNIT_BYTES,
) {
    init {
        require(expectedWidth > 0 && expectedHeight > 0) { "Expected video size must be positive" }
        require(maxInputSizeBytes > 0) { "Maximum decoder input size must be positive" }
    }
}

sealed interface H264RendererStatus {
    data object Idle : H264RendererStatus
    data object Starting : H264RendererStatus
    data object WaitingForKeyFrame : H264RendererStatus
    data class Rendering(val width: Int, val height: Int) : H264RendererStatus
    data object Stopped : H264RendererStatus
    data class Error(val cause: Throwable, val recoverable: Boolean) : H264RendererStatus
}

interface H264RendererListener {
    fun onStatusChanged(status: H264RendererStatus) = Unit
    fun onVideoSizeChanged(width: Int, height: Int) = Unit
    fun onFrameRendered(timestampUs: Long) = Unit
    fun onFramesDropped(count: Int, reason: H264FrameDropReason) = Unit
}

class DecoderInputTooSmallException(requiredBytes: Int, availableBytes: Int) :
    IllegalStateException("H.264 access unit needs $requiredBytes bytes, decoder input has $availableBytes")

/**
 * Asynchronous MediaCodec AVC renderer. Codec state, input indexes, queue, and HandlerThread are
 * owned by one generation-specific [DecoderRun], so an old asynchronous teardown cannot release
 * or drain a newly started decoder. The supplied Surface is caller-owned.
 *
 * [stop] is intentionally non-blocking, including on the Android main thread. It first detaches
 * the complete decoder generation, then posts release to that generation's codec thread. Android
 * MediaCodec retains its own native Surface reference, so the caller may release its Surface after
 * [stop] returns without racing a later generation.
 */
class MediaCodecH264Renderer(
    private val listener: H264RendererListener,
    private val callbackExecutor: Executor = Executor(Runnable::run),
    private val queueCapacity: Int = 3,
) : AutoCloseable {
    private val stateLock = Any()
    private var generation = 0L
    private var activeRun: DecoderRun? = null
    private var releaseBarrier: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    init {
        require(queueCapacity in 1..3) { "H.264 queue capacity must be between 1 and 3" }
    }

    fun start(surface: Surface, config: H264DecoderConfig = H264DecoderConfig()) {
        require(surface.isValid) { "Decoder Surface is not valid" }
        stop()

        val decoderThread = HandlerThread("NanoKVM-H264-decoder").apply { start() }
        val run = synchronized(stateLock) {
            generation++
            DecoderRun(
                id = generation,
                thread = decoderThread,
                handler = Handler(decoderThread.looper),
                queue = H264FrameQueue(queueCapacity),
            ).also { activeRun = it }
        }
        dispatchForRun(run) { listener.onStatusChanged(H264RendererStatus.Starting) }
        val priorRelease = synchronized(stateLock) { releaseBarrier }
        priorRelease.whenComplete { _, _ ->
            if (!run.handler.post { configure(run, surface, config) }) {
                failRun(run, IllegalStateException("Decoder thread rejected configuration"))
            }
        }
    }

    fun reconfigure(surface: Surface, config: H264DecoderConfig = H264DecoderConfig()) =
        start(surface, config)

    fun offer(frame: H264AccessUnit): H264QueueOfferResult {
        val run = synchronized(stateLock) { activeRun }
            ?: return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.RENDERER_STOPPED,
            )
        if (run.failed.get()) {
            return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.RENDERER_STOPPED,
            )
        }
        val result = try {
            run.bootstrap.offer(frame) ?: run.queue.offer(frame)
        } catch (error: IllegalArgumentException) {
            failRun(run, error)
            return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.MALFORMED_TRANSPORT_FRAME,
            )
        }
        if (result.droppedFrames > 0 && result.dropReason != null) {
            dispatchForRun(run) { listener.onFramesDropped(result.droppedFrames, result.dropReason) }
        }
        run.handler.post { drainInputs(run) }
        return result
    }

    fun snapshot(): H264QueueSnapshot = synchronized(stateLock) { activeRun }
        ?.queue
        ?.snapshot()
        ?: H264QueueSnapshot(
            size = 0,
            capacity = queueCapacity,
            awaitingKeyFrame = true,
            acceptedFrames = 0,
            droppedFrames = 0,
        )

    fun stop() {
        stopAndAwaitRelease()
    }

    /** Detaches immediately and completes after this generation releases its MediaCodec. */
    fun stopAndAwaitRelease(): CompletableFuture<Unit> {
        val (oldRun, stoppedGeneration, releaseCompletion) = synchronized(stateLock) {
            generation++
            val detached = activeRun.also { activeRun = null }
            val completion = if (detached != null) {
                CompletableFuture<Unit>().also { ownRelease ->
                    releaseBarrier = releaseBarrier.thenCombine(ownRelease) { _, _ -> }
                }
            } else {
                null
            }
            Triple(detached, generation, completion)
        }
        oldRun?.queue?.clearAndAwaitKeyFrame()
        oldRun?.bootstrap?.clear()
        if (oldRun != null && releaseCompletion != null) {
            releaseAsynchronously(oldRun, releaseCompletion)
        }
        dispatchForGeneration(stoppedGeneration) {
            listener.onStatusChanged(H264RendererStatus.Stopped)
        }
        return releaseCompletion ?: CompletableFuture.completedFuture(Unit)
    }

    override fun close() {
        stop()
    }

    private fun configure(run: DecoderRun, surface: Surface, config: H264DecoderConfig) {
        if (!isActive(run)) return
        try {
            val created = MediaCodec.createDecoderByType(MIME_TYPE)
            run.codec = created
            created.setCallback(DecoderCallback(run), run.handler)
            val format = MediaFormat.createVideoFormat(
                MIME_TYPE,
                config.expectedWidth,
                config.expectedHeight,
            ).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, config.maxInputSizeBytes)
            }
            created.configure(format, surface, null, 0)
            created.setOnFrameRenderedListener(
                MediaCodec.OnFrameRenderedListener { codec, presentationTimeUs, _ ->
                    if (!isActive(run) || run.codec !== codec) return@OnFrameRenderedListener
                    dispatchForRun(run) { listener.onFrameRendered(presentationTimeUs) }
                },
                run.handler,
            )
            created.start()
            dispatchForRun(run) { listener.onStatusChanged(H264RendererStatus.WaitingForKeyFrame) }
        } catch (error: Throwable) {
            releaseCodec(run)
            failRun(run, error)
        }
    }

    private fun drainInputs(run: DecoderRun) {
        if (!isActive(run) || run.failed.get()) return
        val activeCodec = run.codec ?: return
        while (run.availableInputIndexes.isNotEmpty() && isActive(run)) {
            val input = run.bootstrap.poll() ?: run.queue.poll()?.let { frame ->
                H264DecoderInput(
                    data = frame.data,
                    timestampUs = frame.timestampUs,
                    flags = if (frame.isKeyFrame) H264DecoderInput.KEY_FRAME else H264DecoderInput.NONE,
                )
            } ?: return
            val index = run.availableInputIndexes.removeFirst()
            val buffer = try {
                activeCodec.getInputBuffer(index)
            } catch (error: Throwable) {
                failRun(run, error)
                return
            }
            if (buffer == null || buffer.capacity() < input.data.size) {
                runCatching { activeCodec.queueInputBuffer(index, 0, 0, input.timestampUs, 0) }
                val discarded = run.queue.clearAndAwaitKeyFrame()
                dispatchForRun(run) {
                    listener.onFramesDropped(
                        discarded + 1,
                        H264FrameDropReason.DECODER_INPUT_TOO_SMALL,
                    )
                }
                failRun(
                    run,
                    DecoderInputTooSmallException(input.data.size, buffer?.capacity() ?: 0),
                    recoverable = true,
                )
                return
            }
            try {
                copyInto(buffer, input.data)
                activeCodec.queueInputBuffer(
                    index,
                    0,
                    input.data.size,
                    input.timestampUs,
                    when (input.flags) {
                        H264DecoderInput.CODEC_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        H264DecoderInput.KEY_FRAME -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else -> 0
                    },
                )
                if (input.flags == H264DecoderInput.KEY_FRAME) {
                    run.queue.onExternalKeyFrameQueued()
                }
            } catch (error: Throwable) {
                failRun(run, error)
                return
            }
        }
    }

    private fun copyInto(buffer: ByteBuffer, bytes: ByteArray) {
        buffer.clear()
        buffer.put(bytes)
    }

    private fun failRun(run: DecoderRun, error: Throwable, recoverable: Boolean = false) {
        if (!run.failed.compareAndSet(false, true)) return
        dispatchForRun(run) {
            listener.onStatusChanged(H264RendererStatus.Error(error, recoverable = recoverable))
        }
    }

    private fun isActive(run: DecoderRun): Boolean = synchronized(stateLock) {
        activeRun === run && generation == run.id
    }

    private fun isGenerationCurrent(expected: Long): Boolean = synchronized(stateLock) {
        generation == expected
    }

    private fun releaseAsynchronously(run: DecoderRun, completion: CompletableFuture<Unit>) {
        val release = {
            try {
                releaseCodec(run)
            } finally {
                run.thread.quitSafely()
                completion.complete(Unit)
            }
        }
        if (Looper.myLooper() == run.thread.looper) {
            release()
            return
        }

        if (!run.handler.post(release)) {
            // A dead HandlerThread cannot be concurrently using its codec anymore.
            release()
        }
    }

    private fun releaseCodec(run: DecoderRun) {
        run.availableInputIndexes.clear()
        val current = run.codec
        run.codec = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    private fun dispatchForRun(run: DecoderRun, block: () -> Unit) {
        try {
            callbackExecutor.execute {
                if (isActive(run)) runCatching(block)
            }
        } catch (_: Throwable) {
            // The owner has shut its executor down; no callback may be delivered.
        }
    }

    private fun dispatchForGeneration(expected: Long, block: () -> Unit) {
        try {
            callbackExecutor.execute {
                if (isGenerationCurrent(expected)) runCatching(block)
            }
        } catch (_: Throwable) {
            // The owner has shut its executor down; no callback may be delivered.
        }
    }

    private inner class DecoderCallback(
        private val run: DecoderRun,
    ) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isActive(run) || run.codec !== codec) return
            run.availableInputIndexes.addLast(index)
            drainInputs(run)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            if (!isActive(run) || run.codec !== codec) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            try {
                // OnFrameRenderedListener is the acknowledgement that this buffer reached the
                // Surface. Merely releasing an output buffer is not enough to call it visible.
                codec.releaseOutputBuffer(index, true)
            } catch (error: Throwable) {
                failRun(run, error)
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!isActive(run) || run.codec !== codec) return
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            dispatchForRun(run) {
                listener.onVideoSizeChanged(width, height)
                listener.onStatusChanged(H264RendererStatus.Rendering(width, height))
            }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            if (!isActive(run) || run.codec !== codec) return
            run.queue.clearAndAwaitKeyFrame()
            failRun(run, error, recoverable = error.isRecoverable)
        }
    }

    private class DecoderRun(
        val id: Long,
        val thread: HandlerThread,
        val handler: Handler,
        val queue: H264FrameQueue,
        val bootstrap: H264DecoderBootstrap = H264DecoderBootstrap(),
        var codec: MediaCodec? = null,
        val availableInputIndexes: ArrayDeque<Int> = ArrayDeque(),
        val failed: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        const val MIME_TYPE = "video/avc"
    }
}
