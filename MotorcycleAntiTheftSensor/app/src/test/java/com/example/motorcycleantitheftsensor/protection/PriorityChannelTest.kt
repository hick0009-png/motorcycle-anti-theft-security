package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class PriorityChannelTest {

    private data class TestPayload(
        val name: String,
        val priority: Int,
    )

    @Test
    fun bufferOverflowDropsLowestPriorityItemsWhileRetainingHighPriority() = runBlocking {
        val channel = PriorityChannel<TestPayload>(
            capacity = 5,
            priorityOf = { it.priority },
        )

        // Fill capacity with low priority items (priority = 1)
        for (i in 1..5) {
            assertTrue(channel.trySend(TestPayload("routine_motion_$i", 1)))
        }
        assertEquals(5, channel.size)

        // Send high priority items (priority = 10)
        assertTrue(channel.trySend(TestPayload("audio_evidence", 10)))
        assertTrue(channel.trySend(TestPayload("location_evidence", 10)))
        assertEquals(5, channel.size)

        // Attempt to send another low priority item when full
        assertFalse(channel.trySend(TestPayload("routine_motion_6", 1)))

        // Drain channel and verify retained items
        val received = mutableListOf<TestPayload>()
        repeat(5) {
            received.add(channel.receive())
        }

        // Top 2 items must be the high priority items
        assertEquals(10, received[0].priority)
        assertEquals(10, received[1].priority)
        val names = received.map { it.name }
        assertTrue(names.contains("audio_evidence"))
        assertTrue(names.contains("location_evidence"))
        assertFalse(names.contains("routine_motion_6"))
    }

    @Test
    fun equalPriorityItemsPreserveStrictFifoArrivalOrder() = runBlocking {
        val channel = PriorityChannel<TestPayload>(
            capacity = 10,
            priorityOf = { it.priority },
        )

        // Send 5 items with equal priority 5
        val items = listOf("first", "second", "third", "fourth", "fifth")
        items.forEach { name ->
            assertTrue(channel.trySend(TestPayload(name, 5)))
        }

        // Receive all 5 and check strict FIFO order
        val receivedNames = (1..5).map { channel.receive().name }
        assertEquals(items, receivedNames)
    }

    @Test
    fun equalPriorityInterleavedWithMixedPrioritiesPreservesFifoPerPriority() = runBlocking {
        val channel = PriorityChannel<TestPayload>(
            capacity = 10,
            priorityOf = { it.priority },
        )

        channel.trySend(TestPayload("P1_A", 1))
        channel.trySend(TestPayload("P5_A", 5))
        channel.trySend(TestPayload("P5_B", 5))
        channel.trySend(TestPayload("P1_B", 1))

        assertEquals("P5_A", channel.receive().name)
        assertEquals("P5_B", channel.receive().name)
        assertEquals("P1_A", channel.receive().name)
        assertEquals("P1_B", channel.receive().name)
    }

    @Test
    fun threadSafetyUnderHighRateConcurrentProducersAndConsumer() = runBlocking {
        val capacity = 100
        val channel = PriorityChannel<TestPayload>(
            capacity = capacity,
            priorityOf = { it.priority },
        )

        val producerCount = 8
        val itemsPerProducer = 200
        val totalSent = producerCount * itemsPerProducer

        val receivedList = ConcurrentLinkedQueue<TestPayload>()
        val rejectedCount = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            // Concurrent producers
            val producers = (1..producerCount).map { pId ->
                launch(Dispatchers.Default) {
                    for (i in 1..itemsPerProducer) {
                        val item = TestPayload("P${pId}_$i", priority = 1)
                        if (!channel.trySend(item)) {
                            rejectedCount.incrementAndGet()
                        }
                    }
                }
            }

            // Consumer job
            val consumerJob = launch(Dispatchers.Default) {
                while (receivedList.size + rejectedCount.get() < totalSent) {
                    if (channel.size > 0) {
                        receivedList.add(channel.receive())
                    } else {
                        kotlinx.coroutines.delay(1)
                    }
                }
            }

            producers.forEach { it.join() }
            consumerJob.join()
        }

        assertTrue(channel.size <= capacity)
        assertEquals(totalSent, receivedList.size + rejectedCount.get())
    }

    @Test
    fun highThroughputBurstSoakTest() = runBlocking {
        val capacity = 200
        val channel = PriorityChannel<TestPayload>(
            capacity = capacity,
            priorityOf = { it.priority },
        )

        val totalItems = 10_000
        val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val rejectedCount = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            val consumer = launch(Dispatchers.Default) {
                while (receivedCount.get() + rejectedCount.get() < totalItems) {
                    if (channel.size > 0) {
                        channel.receive()
                        receivedCount.incrementAndGet()
                    } else {
                        kotlinx.coroutines.delay(1)
                    }
                }
            }

            val producers = (1..5).map { pId ->
                launch(Dispatchers.Default) {
                    for (i in 1..(totalItems / 5)) {
                        if (!channel.trySend(TestPayload("Item_${pId}_$i", priority = 1))) {
                            rejectedCount.incrementAndGet()
                        }
                    }
                }
            }

            producers.forEach { it.join() }
            consumer.join()
        }

        assertEquals(totalItems, receivedCount.get() + rejectedCount.get())
        assertEquals(0, channel.size)
    }
}
