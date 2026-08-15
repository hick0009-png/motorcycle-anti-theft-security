package com.example.motorcycleantitheftsensor.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConflatedLocationFixIngressTest {

    @Test
    fun burstOfFixesDeliversFirstInFlightPlusLatestQueuedFix() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val consumed = mutableListOf<TrackedLocationFix>()
        val firstInFlightPaused = CompletableDeferred<Unit>()
        val allowFirstToFinish = CompletableDeferred<Unit>()

        val ingress = ConflatedLocationFixIngress(testScope) { fix ->
            if (consumed.isEmpty()) {
                firstInFlightPaused.complete(Unit)
                allowFirstToFinish.await()
            }
            consumed += fix
        }

        // Offer first fix
        assertTrue(ingress.offer(fix(1.0, 1.0, 1000L, 5f)))
        testScope.advanceUntilIdle()
        assertTrue(firstInFlightPaused.isCompleted)

        // Burst 1000 fixes while first is in flight
        for (i in 2..1000) {
            val offered = ingress.offer(fix(1.0 + i * 0.0001, 1.0 + i * 0.0001, 1000L + i * 10L, 5f))
            assertTrue(offered)
        }

        // Release first fix
        allowFirstToFinish.complete(Unit)
        testScope.advanceUntilIdle()

        ingress.awaitClosed()
        testScope.advanceUntilIdle()

        assertEquals(2, consumed.size)
        assertEquals(1000L, consumed[0].elapsedRealtimeMs)
        assertEquals(1000L + 1000 * 10L, consumed[1].elapsedRealtimeMs)
    }

    @Test
    fun rejectsOlderOrEqualTimestamp() = runTest {
        val consumed = mutableListOf<TrackedLocationFix>()
        val ingress = ConflatedLocationFixIngress(this) { consumed += it }

        assertTrue(ingress.offer(fix(1.0, 1.0, 2000L, 5f)))
        assertFalse(ingress.offer(fix(1.0, 1.0, 1500L, 5f))) // Older
        assertFalse(ingress.offer(fix(1.0, 1.0, 2000L, 5f))) // Equal
        assertTrue(ingress.offer(fix(1.0, 1.0, 2001L, 5f))) // Newer

        ingress.awaitClosed()
    }

    @Test
    fun cancelPendingImmediatelyDiscardsQueuedFix() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)
        val consumed = mutableListOf<TrackedLocationFix>()
        val firstInFlightPaused = CompletableDeferred<Unit>()
        val allowFirstToFinish = CompletableDeferred<Unit>()

        val ingress = ConflatedLocationFixIngress(testScope) { fix ->
            consumed += fix
            if (consumed.size == 1) {
                firstInFlightPaused.complete(Unit)
                allowFirstToFinish.await()
            }
        }

        assertTrue(ingress.offer(fix(1.0, 1.0, 1000L, 5f)))
        testScope.advanceUntilIdle()
        assertTrue(firstInFlightPaused.isCompleted)

        // Queue a second fix
        assertTrue(ingress.offer(fix(1.01, 1.01, 2000L, 5f)))

        // Cancel pending before first completes
        ingress.cancelPending()
        allowFirstToFinish.complete(Unit)
        testScope.advanceUntilIdle()

        // New offers should be rejected
        assertFalse(ingress.offer(fix(1.02, 1.02, 3000L, 5f)))
        ingress.awaitClosed()

        // Second fix was cancelled and not consumed
        assertEquals(1, consumed.size)
        assertEquals(1000L, consumed[0].elapsedRealtimeMs)
    }

    private fun fix(lat: Double, lon: Double, elapsed: Long, accuracy: Float) =
        TrackedLocationFix(lat, lon, elapsed, 123456L, accuracy)
}
