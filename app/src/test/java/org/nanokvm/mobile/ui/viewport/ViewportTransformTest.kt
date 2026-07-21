package org.nanokvm.mobile.ui.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTransformTest {
    @Test
    fun `fit preserves aspect ratio and centers letterbox`() {
        val transform = ViewportTransform.fit(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewport = FloatSize(1000f, 1000f),
        )

        assertEquals(0f, transform.contentRect.left, 0.01f)
        assertEquals(218.75f, transform.contentRect.top, 0.01f)
        assertEquals(1000f, transform.contentRect.right, 0.01f)
        assertEquals(781.25f, transform.contentRect.bottom, 0.01f)
    }

    @Test
    fun `letterbox does not produce remote input`() {
        val transform = ViewportTransform.fit(1920, 1080, FloatSize(1000f, 1000f))

        assertNull(transform.screenToRemote(FloatPoint(500f, 100f)))
        assertNull(transform.screenToRemote(FloatPoint(500f, 900f)))
        assertNotNull(transform.screenToRemote(FloatPoint(500f, 500f)))
    }

    @Test
    fun `center maps to center of remote HID range`() {
        val transform = ViewportTransform.fit(1920, 1080, FloatSize(1000f, 1000f))
        val remote = requireNotNull(transform.screenToRemote(FloatPoint(500f, 500f)))
        val hid = remote.toHidAbsolute(1920, 1080)

        assertEquals(960f, remote.x, 0.01f)
        assertEquals(540f, remote.y, 0.01f)
        assertTrue(hid.x in 16_383..16_401)
        assertTrue(hid.y in 16_383..16_406)
    }

    @Test
    fun `pinch keeps focal remote pixel stationary`() {
        val start = ViewportTransform.fit(1920, 1080, FloatSize(1200f, 800f))
        val focal = FloatPoint(600f, 400f)
        val remoteBefore = requireNotNull(start.screenToRemote(focal))

        val zoomed = start.zoomBy(2f, focal)
        val screenAfter = zoomed.remoteToScreen(remoteBefore)

        assertEquals(2f, zoomed.zoom, 0.001f)
        assertEquals(ViewportScaleMode.Custom, zoomed.scaleMode)
        assertEquals(focal.x, screenAfter.x, 0.01f)
        assertEquals(focal.y, screenAfter.y, 0.01f)
    }

    @Test
    fun `zoom and pan remain bounded`() {
        val start = ViewportTransform.fit(1920, 1080, FloatSize(1000f, 600f))
        val zoomed = start.zoomBy(99f, FloatPoint(500f, 300f))
        val panned = zoomed.panBy(FloatPoint(100_000f, -100_000f))

        assertEquals(panned.maximumCustomScale, panned.contentScale, 0f)
        assertTrue(panned.zoom >= ViewportTransform.MAX_FIT_RELATIVE_ZOOM)
        assertTrue(panned.pan.x < panned.contentRect.width / 2f)
        assertTrue(panned.pan.y > -panned.contentRect.height / 2f)
        assertTrue(panned.contentRect.right >= 1000f)
        assertTrue(panned.contentRect.top <= 0f)
    }

    @Test
    fun `vertical over-pan at fit is bounded by the top and bottom edge anchors`() {
        val viewport = FloatSize(1000f, 562.5f)
        val start = ViewportTransform.fit(1920, 1080, viewport)

        val topLimit = start.panBy(FloatPoint(100_000f, 100_000f))
        val bottomLimit = start.panBy(FloatPoint(-100_000f, -100_000f))

        assertEquals(viewport.height * 0.42f, topLimit.contentRect.top, 0.01f)
        assertEquals(viewport.height * 0.58f, bottomLimit.contentRect.bottom, 0.01f)
        assertEquals(0f, topLimit.pan.x, 0f)
        assertEquals(0f, bottomLimit.pan.x, 0f)
        val beyondTopLimit = topLimit.panBy(FloatPoint(0f, 1f))
        val beyondBottomLimit = bottomLimit.panBy(FloatPoint(0f, -1f))
        assertEquals(topLimit.pan.x, beyondTopLimit.pan.x, 0f)
        assertEquals(topLimit.pan.y, beyondTopLimit.pan.y, 0f)
        assertEquals(bottomLimit.pan.x, beyondBottomLimit.pan.x, 0f)
        assertEquals(bottomLimit.pan.y, beyondBottomLimit.pan.y, 0f)
        assertNull(topLimit.screenToRemote(FloatPoint(viewport.width / 2f, 0f)))
        assertNull(bottomLimit.screenToRemote(FloatPoint(viewport.width / 2f, viewport.height)))
    }

    @Test
    fun `fit clears zoom and pan`() {
        val start = ViewportTransform.fit(1920, 1080, FloatSize(1000f, 600f))
            .zoomBy(3f, FloatPoint(500f, 300f))
            .panBy(FloatPoint(100f, -80f))

        val fitted = start.fit()

        assertEquals(1f, fitted.zoom, 0f)
        assertEquals(ViewportScaleMode.Fit, fitted.scaleMode)
        assertEquals(FloatPoint(0f, 0f), fitted.pan)
    }

    @Test
    fun `actual size is exactly one local pixel per remote pixel when remote is larger`() {
        val transform = ViewportTransform.fit(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewport = FloatSize(1000f, 700f),
        ).actualSize()

        assertEquals(ViewportScaleMode.ActualSize, transform.scaleMode)
        assertEquals(1f, transform.contentScale, 0f)
        assertEquals(1920f, transform.contentRect.width, 0f)
        assertEquals(1080f, transform.contentRect.height, 0f)
        assertEquals(FloatPoint(0f, 0f), transform.pan)
    }

    @Test
    fun `actual size is centered and exactly one to one when remote is smaller`() {
        val transform = ViewportTransform.fit(
            remoteWidth = 640,
            remoteHeight = 480,
            viewport = FloatSize(1200f, 800f),
        ).actualSize()

        assertEquals(1f, transform.contentScale, 0f)
        assertEquals(280f, transform.contentRect.left, 0f)
        assertEquals(160f, transform.contentRect.top, 0f)
        assertEquals(920f, transform.contentRect.right, 0f)
        assertEquals(640f, transform.contentRect.bottom, 0f)
    }

    @Test
    fun `actual size remains one to one across viewport resize`() {
        val start = ViewportTransform.fit(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewport = FloatSize(1000f, 700f),
        ).actualSize().panBy(FloatPoint(180f, -120f))
        val remoteCentre = requireNotNull(start.screenToRemote(FloatPoint(500f, 350f)))

        val resized = start.withViewportPreservingCenter(FloatSize(720f, 420f))
        val centreAfterResize = requireNotNull(
            resized.screenToRemote(FloatPoint(360f, 210f)),
        )

        assertEquals(ViewportScaleMode.ActualSize, resized.scaleMode)
        assertEquals(1f, resized.contentScale, 0f)
        assertEquals(remoteCentre.x, centreAfterResize.x, 0.01f)
        assertEquals(remoteCentre.y, centreAfterResize.y, 0.01f)
    }

    @Test
    fun `custom scale is absolute and survives a resize`() {
        val viewport = FloatSize(1000f, 700f)
        val start = ViewportTransform.fit(1920, 1080, viewport)
            .zoomBy(2f, FloatPoint(viewport.width / 2f, viewport.height / 2f))
        val initialAbsoluteScale = start.contentScale

        val resized = start.withViewportPreservingCenter(FloatSize(720f, 420f))

        assertEquals(ViewportScaleMode.Custom, resized.scaleMode)
        assertEquals(initialAbsoluteScale, resized.contentScale, 0f)
        assertTrue(start.zoom != resized.zoom)
    }

    @Test
    fun `custom scale survives resize even when it falls outside the new gesture bounds`() {
        val tinyViewport = FloatSize(320f, 180f)
        val hugeViewport = FloatSize(4_000f, 2_500f)
        val lowScale = ViewportTransform.fit(1920, 1080, tinyViewport)
            .zoomBy(1.25f, FloatPoint(160f, 90f))
        val highScale = ViewportTransform.fit(1920, 1080, hugeViewport)
            .zoomBy(2f, FloatPoint(2_000f, 1_250f))

        val lowScaleOnHugeViewport = lowScale.withViewportPreservingCenter(hugeViewport)
        val highScaleOnTinyViewport = highScale.withViewportPreservingCenter(tinyViewport)

        assertTrue(lowScale.contentScale < lowScaleOnHugeViewport.minimumCustomScale)
        assertEquals(lowScale.contentScale, lowScaleOnHugeViewport.contentScale, 0f)
        assertTrue(highScale.contentScale > highScaleOnTinyViewport.maximumCustomScale)
        assertEquals(highScale.contentScale, highScaleOnTinyViewport.contentScale, 0f)
        assertEquals(ViewportScaleMode.Custom, lowScaleOnHugeViewport.scaleMode)
        assertEquals(ViewportScaleMode.Custom, highScaleOnTinyViewport.scaleMode)
    }

    @Test
    fun `pinching from actual size enters custom without moving the focal remote pixel`() {
        val start = ViewportTransform.fit(
            remoteWidth = 1920,
            remoteHeight = 1080,
            viewport = FloatSize(1000f, 700f),
        ).actualSize()
        val focal = FloatPoint(350f, 280f)
        val remoteBefore = requireNotNull(start.screenToRemote(focal))

        val zoomed = start.zoomBy(1.25f, focal)

        assertEquals(ViewportScaleMode.Custom, zoomed.scaleMode)
        assertEquals(1.25f, zoomed.contentScale, 0.001f)
        assertEquals(focal.x, zoomed.remoteToScreen(remoteBefore).x, 0.01f)
        assertEquals(focal.y, zoomed.remoteToScreen(remoteBefore).y, 0.01f)
    }

    @Test
    fun `top and bottom focus can move a remote edge toward viewport centre`() {
        val viewport = FloatSize(1000f, 700f)
        val start = ViewportTransform.fit(1920, 1080, viewport)
            .zoomBy(2f, FloatPoint(viewport.width / 2f, viewport.height / 2f))
            .panBy(FloatPoint(120f, 0f))

        val topFocused = start.focusTop()
        val bottomFocused = start.focusBottom()

        assertEquals(viewport.height * 0.42f, topFocused.contentRect.top, 0.01f)
        assertEquals(viewport.height * 0.58f, bottomFocused.contentRect.bottom, 0.01f)
        assertEquals(start.pan.x, topFocused.pan.x, 0f)
        assertEquals(start.pan.x, bottomFocused.pan.x, 0f)
        assertEquals(FloatPoint(0f, 0f), topFocused.center().pan)
    }

    @Test
    fun `viewport resize preserves the remote centre through a shrink and grow round trip`() {
        val start = ViewportTransform.fit(1920, 1080, FloatSize(1000f, 700f))
            .zoomBy(2f, FloatPoint(500f, 350f))
            .panBy(FloatPoint(120f, -90f))
        val remoteCentre = requireNotNull(start.screenToRemote(FloatPoint(500f, 350f)))

        val resized = start.withViewportPreservingCenter(FloatSize(720f, 420f))
        val centreAfterResize = requireNotNull(
            resized.screenToRemote(FloatPoint(360f, 210f)),
        )
        assertEquals(remoteCentre.x, centreAfterResize.x, 0.01f)
        assertEquals(remoteCentre.y, centreAfterResize.y, 0.01f)
        assertEquals(ViewportScaleMode.Custom, resized.scaleMode)
        assertEquals(start.contentScale, resized.contentScale, 0f)

        val restored = resized.withViewportPreservingCenter(start.viewport)
        val centreAfterRestore = requireNotNull(
            restored.screenToRemote(FloatPoint(500f, 350f)),
        )
        assertEquals(remoteCentre.x, centreAfterRestore.x, 0.01f)
        assertEquals(remoteCentre.y, centreAfterRestore.y, 0.01f)
        assertEquals(ViewportScaleMode.Custom, restored.scaleMode)
        assertEquals(start.contentScale, restored.contentScale, 0f)
        assertEquals(start.pan.x, restored.pan.x, 0.01f)
        assertEquals(start.pan.y, restored.pan.y, 0.01f)
    }

    @Test
    fun `reveal vertically moves top and bottom touches into the keyboard safe band`() {
        val viewport = FloatSize(1000f, 562.5f)
        val start = ViewportTransform.fit(1920, 1080, viewport)
            .zoomBy(2f, FloatPoint(viewport.width / 2f, viewport.height / 2f))

        val topTouch = RemotePoint(960f, 0f)
        val topRevealed = start.revealVertically(topTouch)
        val topY = topRevealed.remoteToScreen(topTouch).y
        assertEquals(viewport.height * 0.42f, topY, 0.01f)
        assertEquals(start.pan.x, topRevealed.pan.x, 0f)

        val bottomTouch = RemotePoint(960f, 1079f)
        val bottomRevealed = start.revealVertically(bottomTouch)
        val bottomY = bottomRevealed.remoteToScreen(bottomTouch).y
        assertTrue(bottomY >= viewport.height * 0.42f)
        assertTrue(bottomY <= viewport.height * 0.58f + 0.01f)
        assertEquals(start.pan.x, bottomRevealed.pan.x, 0f)
    }

    @Test
    fun `reveal vertically leaves a point already inside the safe band stationary`() {
        val viewport = FloatSize(1000f, 562.5f)
        val start = ViewportTransform.fit(1920, 1080, viewport)
            .zoomBy(2f, FloatPoint(viewport.width / 2f, viewport.height / 2f))
            .panBy(FloatPoint(140f, -80f))
        val pointInBand = requireNotNull(
            start.screenToRemote(FloatPoint(viewport.width / 2f, viewport.height / 2f)),
        )

        assertEquals(start, start.revealVertically(pointInBand))
    }

    @Test
    fun `reveal vertically honors a valid custom safe band and rejects invalid fractions`() {
        val viewport = FloatSize(1000f, 562.5f)
        val start = ViewportTransform.fit(1920, 1080, viewport)
            .zoomBy(2f, FloatPoint(viewport.width / 2f, viewport.height / 2f))
        val topTouch = RemotePoint(960f, 0f)
        val bottomEdge = RemotePoint(960f, 1080f)

        val topY = start.revealVertically(topTouch, topFraction = 0.40f, bottomFraction = 0.60f)
            .remoteToScreen(topTouch).y
        val bottomY = start.revealVertically(bottomEdge, topFraction = 0.40f, bottomFraction = 0.60f)
            .remoteToScreen(bottomEdge).y
        assertEquals(viewport.height * 0.40f, topY, 0.01f)
        assertEquals(viewport.height * 0.60f, bottomY, 0.01f)

        assertThrows(IllegalArgumentException::class.java) {
            start.revealVertically(topTouch, topFraction = -0.01f, bottomFraction = 0.58f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            start.revealVertically(topTouch, topFraction = 0.70f, bottomFraction = 0.60f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            start.revealVertically(topTouch, topFraction = 0.42f, bottomFraction = 1.01f)
        }
    }
}
