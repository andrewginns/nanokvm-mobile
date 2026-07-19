package org.nanokvm.video

import java.util.ArrayDeque

enum class H264FrameDropReason {
    AWAITING_KEY_FRAME,
    STALE_BACKLOG,
    REPLACED_BY_KEY_FRAME,
    MALFORMED_TRANSPORT_FRAME,
    DECODER_INPUT_TOO_SMALL,
    RENDERER_STOPPED,
}

data class H264QueueOfferResult(
    val accepted: Boolean,
    val droppedFrames: Int = 0,
    val dropReason: H264FrameDropReason? = null,
)

data class H264QueueSnapshot(
    val size: Int,
    val capacity: Int,
    val awaitingKeyFrame: Boolean,
    val acceptedFrames: Long,
    val droppedFrames: Long,
)

/**
 * Small, thread-safe newest-frame queue for a low-latency decoder.
 *
 * Delta frames are ignored until a key frame arrives. A newer key frame replaces all queued
 * work. If the queue falls behind, the entire queued GOP and the newly arrived delta are
 * discarded: retaining a later P-frame after dropping one of its references would feed a
 * knowingly broken dependency chain to MediaCodec.
 */
class H264FrameQueue(
    val capacity: Int = 3,
) {
    private val frames = ArrayDeque<H264AccessUnit>(capacity)
    private var awaitingKeyFrame = true
    private var acceptedFrames = 0L
    private var droppedFrames = 0L

    init {
        require(capacity in 1..3) { "H.264 queue capacity must be between 1 and 3" }
    }

    @Synchronized
    fun offer(frame: H264AccessUnit): H264QueueOfferResult {
        if (awaitingKeyFrame && !frame.isKeyFrame) {
            droppedFrames++
            return H264QueueOfferResult(
                accepted = false,
                droppedFrames = 1,
                dropReason = H264FrameDropReason.AWAITING_KEY_FRAME,
            )
        }

        if (frame.isKeyFrame) {
            val replaced = frames.size
            frames.clear()
            frames.addLast(frame)
            awaitingKeyFrame = false
            acceptedFrames++
            droppedFrames += replaced.toLong()
            return H264QueueOfferResult(
                accepted = true,
                droppedFrames = replaced,
                dropReason = if (replaced > 0) H264FrameDropReason.REPLACED_BY_KEY_FRAME else null,
            )
        }

        if (frames.size == capacity) {
            val staleDropped = frames.size + 1 // Queued GOP plus this dependent delta.
            frames.clear()
            awaitingKeyFrame = true
            droppedFrames += staleDropped.toLong()
            return H264QueueOfferResult(
                accepted = false,
                droppedFrames = staleDropped,
                dropReason = H264FrameDropReason.STALE_BACKLOG,
            )
        }

        frames.addLast(frame)
        acceptedFrames++
        return H264QueueOfferResult(
            accepted = true,
        )
    }

    @Synchronized
    fun poll(): H264AccessUnit? = frames.pollFirst()

    /**
     * Lets the live queue accept dependent frames after a key frame held outside this bounded
     * queue has been submitted successfully to the decoder.
     */
    @Synchronized
    internal fun onExternalKeyFrameQueued() {
        awaitingKeyFrame = false
    }

    @Synchronized
    fun clearAndAwaitKeyFrame(): Int {
        val discarded = frames.size
        frames.clear()
        awaitingKeyFrame = true
        droppedFrames += discarded.toLong()
        return discarded
    }

    @Synchronized
    fun snapshot(): H264QueueSnapshot = H264QueueSnapshot(
        size = frames.size,
        capacity = capacity,
        awaitingKeyFrame = awaitingKeyFrame,
        acceptedFrames = acceptedFrames,
        droppedFrames = droppedFrames,
    )
}
