package org.nanokvm.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LatestFrameDispatcherTest {
    @Test
    fun queuedValuesCoalesceToNewest() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val dispatcher = LatestFrameDispatcher(executor, delivered::add, discarded::add)

        dispatcher.offer(1)
        dispatcher.offer(2)
        dispatcher.offer(3)
        executor.runNext()

        assertEquals(listOf(3), delivered)
        assertEquals(listOf(1, 2), discarded)
    }

    @Test
    fun arrivalsDuringDeliveryLeaveOnlyLatestPending() {
        val discarded = mutableListOf<Int>()
        lateinit var dispatcher: LatestFrameDispatcher<Int>
        val delivered = mutableListOf<Int>()
        dispatcher = LatestFrameDispatcher(
            Executor(Runnable::run),
            deliver = { value ->
                delivered += value
                if (value == 1) {
                    dispatcher.offer(2)
                    dispatcher.offer(3)
                }
            },
            discard = discarded::add,
        )

        dispatcher.offer(1)

        assertEquals(listOf(1, 3), delivered)
        assertEquals(listOf(2), discarded)
    }

    @Test
    fun clearDiscardsPendingAndDelayedTaskDeliversNothing() {
        val executor = QueuedExecutor()
        val delivered = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val dispatcher = LatestFrameDispatcher(executor, delivered::add, discarded::add)
        dispatcher.offer(7)

        dispatcher.clear()
        executor.runNext()

        assertEquals(emptyList<Int>(), delivered)
        assertEquals(listOf(7), discarded)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
