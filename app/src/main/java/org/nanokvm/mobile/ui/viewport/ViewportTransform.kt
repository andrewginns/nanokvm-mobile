package org.nanokvm.mobile.ui.viewport

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val HID_ABSOLUTE_MAX = 32_767f

data class FloatPoint(val x: Float, val y: Float)
data class FloatSize(val width: Float, val height: Float)
data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(point: FloatPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

data class RemotePoint(val x: Float, val y: Float) {
    fun toHidAbsolute(remoteWidth: Int, remoteHeight: Int): HidAbsolutePoint {
        val maxX = max(1, remoteWidth - 1)
        val maxY = max(1, remoteHeight - 1)
        return HidAbsolutePoint(
            x = (x.coerceIn(0f, maxX.toFloat()) / maxX * HID_ABSOLUTE_MAX).roundToInt(),
            y = (y.coerceIn(0f, maxY.toFloat()) / maxY * HID_ABSOLUTE_MAX).roundToInt(),
        )
    }
}

data class HidAbsolutePoint(val x: Int, val y: Int)

/**
 * Immutable mapping between a letterboxed remote frame and its local gesture viewport.
 * [zoom] is relative to fit and remains in the 1x..4x range.
 */
data class ViewportTransform(
    val remoteWidth: Int,
    val remoteHeight: Int,
    val viewport: FloatSize,
    val zoom: Float = 1f,
    val pan: FloatPoint = FloatPoint(0f, 0f),
) {
    val fitScale: Float
        get() = min(
            viewport.width / max(1, remoteWidth),
            viewport.height / max(1, remoteHeight),
        ).takeIf { it.isFinite() && it > 0f } ?: 1f

    val contentScale: Float get() = fitScale * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    val contentRect: FloatRect
        get() {
            val width = remoteWidth * contentScale
            val height = remoteHeight * contentScale
            val left = (viewport.width - width) / 2f + pan.x
            val top = (viewport.height - height) / 2f + pan.y
            return FloatRect(left, top, left + width, top + height)
        }

    fun screenToRemote(point: FloatPoint): RemotePoint? {
        val rect = contentRect
        if (!rect.contains(point)) return null
        return RemotePoint(
            x = ((point.x - rect.left) / contentScale).coerceIn(0f, remoteWidth.toFloat()),
            y = ((point.y - rect.top) / contentScale).coerceIn(0f, remoteHeight.toFloat()),
        )
    }

    fun remoteToScreen(point: RemotePoint): FloatPoint {
        val rect = contentRect
        return FloatPoint(
            x = rect.left + point.x * contentScale,
            y = rect.top + point.y * contentScale,
        )
    }

    fun fit(): ViewportTransform = copy(zoom = 1f, pan = FloatPoint(0f, 0f))

    fun center(): ViewportTransform = copy(pan = FloatPoint(0f, 0f)).clampPan()

    /** Places the remote top edge within easy view while retaining the current zoom. */
    fun focusTop(): ViewportTransform {
        val clamped = clampPan()
        return clamped.copy(pan = FloatPoint(clamped.pan.x, clamped.verticalPanLimit()))
    }

    /** Places the remote bottom edge within easy view while retaining the current zoom. */
    fun focusBottom(): ViewportTransform {
        val clamped = clampPan()
        return clamped.copy(pan = FloatPoint(clamped.pan.x, -clamped.verticalPanLimit()))
    }

    fun withViewport(size: FloatSize): ViewportTransform = copy(viewport = size).clampPan()

    /** Resizes the local viewport without moving the remote pixel currently at its centre. */
    fun withViewportPreservingCenter(size: FloatSize): ViewportTransform {
        val oldCentre = FloatPoint(viewport.width / 2f, viewport.height / 2f)
        val remoteCentre = screenToRemote(oldCentre) ?: return withViewport(size)
        val resized = copy(viewport = size)
        val scale = resized.contentScale
        val centredLeft = (size.width - remoteWidth * scale) / 2f
        val centredTop = (size.height - remoteHeight * scale) / 2f
        return resized.copy(
            pan = FloatPoint(
                x = size.width / 2f - centredLeft - remoteCentre.x * scale,
                y = size.height / 2f - centredTop - remoteCentre.y * scale,
            ),
        ).clampPan()
    }

    /** Minimally moves a recent remote touch into the keyboard-safe centre band. */
    fun revealVertically(
        remote: RemotePoint,
        topFraction: Float = 0.42f,
        bottomFraction: Float = 0.58f,
    ): ViewportTransform {
        require(topFraction in 0f..1f && bottomFraction in topFraction..1f)
        val screen = remoteToScreen(remote)
        val targetY = screen.y.coerceIn(
            viewport.height * topFraction,
            viewport.height * bottomFraction,
        )
        return panBy(FloatPoint(0f, targetY - screen.y))
    }

    fun panBy(delta: FloatPoint): ViewportTransform =
        copy(pan = FloatPoint(pan.x + delta.x, pan.y + delta.y)).clampPan()

    /** Keeps the remote pixel below [focalPoint] stationary while zooming. */
    fun zoomBy(multiplier: Float, focalPoint: FloatPoint): ViewportTransform {
        val oldRect = contentRect
        val oldRemote = RemotePoint(
            x = (focalPoint.x - oldRect.left) / contentScale,
            y = (focalPoint.y - oldRect.top) / contentScale,
        )
        val newZoom = (zoom * multiplier).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = fitScale * newZoom
        val centeredLeft = (viewport.width - remoteWidth * newScale) / 2f
        val centeredTop = (viewport.height - remoteHeight * newScale) / 2f
        return copy(
            zoom = newZoom,
            pan = FloatPoint(
                focalPoint.x - centeredLeft - oldRemote.x * newScale,
                focalPoint.y - centeredTop - oldRemote.y * newScale,
            ),
        ).clampPan()
    }

    private fun clampPan(): ViewportTransform {
        val effectiveZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val width = remoteWidth * fitScale * effectiveZoom
        val height = remoteHeight * fitScale * effectiveZoom
        val maxX = max(0f, (width - viewport.width) / 2f)
        val maxY = max(
            0f,
            height / 2f - viewport.height * VERTICAL_EDGE_MARGIN_FRACTION,
        )
        return copy(
            zoom = effectiveZoom,
            pan = FloatPoint(
                x = pan.x.coerceIn(-maxX, maxX),
                y = pan.y.coerceIn(-maxY, maxY),
            ),
        )
    }

    private fun verticalPanLimit(): Float {
        val height = remoteHeight * fitScale * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return max(0f, height / 2f - viewport.height * VERTICAL_EDGE_MARGIN_FRACTION)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        private const val VERTICAL_EDGE_MARGIN_FRACTION = 0.08f
        fun fit(remoteWidth: Int, remoteHeight: Int, viewport: FloatSize) = ViewportTransform(
            remoteWidth = max(1, remoteWidth),
            remoteHeight = max(1, remoteHeight),
            viewport = viewport,
        )
    }
}
