package org.nanokvm.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteScrollAccumulatorTest {
    @Test
    fun `gesture locks to dominant axis and keeps fractional movement`() {
        val accumulator = RemoteScrollAccumulator(baseStepPixels = 20f, sensitivity = 1f)

        assertEquals(RemoteScrollSteps(), accumulator.add(deltaX = -11f, deltaY = -4f))
        assertEquals(
            RemoteScrollSteps(horizontal = 1),
            accumulator.add(deltaX = -10f, deltaY = -2f),
        )
        assertEquals(RemoteScrollSteps(), accumulator.add(deltaX = 0f, deltaY = -100f))
    }

    @Test
    fun `small startup jitter does not lock the wrong axis`() {
        val accumulator = RemoteScrollAccumulator(baseStepPixels = 10f, sensitivity = 1f)

        assertEquals(RemoteScrollSteps(), accumulator.add(deltaX = 1f, deltaY = 1f))
        assertEquals(RemoteScrollSteps(), accumulator.add(deltaX = 0f, deltaY = -10f))
        assertEquals(
            RemoteScrollSteps(vertical = 1),
            accumulator.add(deltaX = 0f, deltaY = -2f),
        )
    }

    @Test
    fun `minimum sensitivity still emits for a realistic in-pad swipe`() {
        val accumulator = RemoteScrollAccumulator(baseStepPixels = 20f, sensitivity = 0.5f)

        assertEquals(
            RemoteScrollSteps(vertical = 1),
            accumulator.add(deltaX = 0f, deltaY = -41f),
        )
    }

    @Test
    fun `higher sensitivity emits more steps for the same swipe`() {
        val normal = RemoteScrollAccumulator(baseStepPixels = 30f, sensitivity = 1f)
        val fast = RemoteScrollAccumulator(baseStepPixels = 30f, sensitivity = 3f)

        assertEquals(RemoteScrollSteps(vertical = 1), normal.add(0f, -30f))
        assertEquals(RemoteScrollSteps(vertical = 3), fast.add(0f, -30f))
    }

    @Test
    fun `reset permits a new gesture to choose the other axis`() {
        val accumulator = RemoteScrollAccumulator(baseStepPixels = 10f, sensitivity = 1f)

        assertEquals(RemoteScrollSteps(vertical = -1), accumulator.add(0f, 10f))
        accumulator.reset()
        assertEquals(RemoteScrollSteps(horizontal = -1), accumulator.add(10f, 0f))
    }
}
