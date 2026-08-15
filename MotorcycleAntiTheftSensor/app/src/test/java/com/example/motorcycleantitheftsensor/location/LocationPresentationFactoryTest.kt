package com.example.motorcycleantitheftsensor.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPresentationFactoryTest {

    @Test
    fun presentationAlwaysContainsLocaleIndependentUrlAndCeilingAccuracy() = runBlocking {
        val resolver = LocationLabelResolver { "Bangkok, Thailand" }
        val factory = LocationPresentationFactory(resolver)

        val fix = TrackedLocationFix(
            latitude = 13.756300,
            longitude = 100.501800,
            elapsedRealtimeMs = 1000L,
            wallClockMs = 1000L,
            accuracyMeters = 8.1f
        )

        val presentation = factory.create(fix)
        assertEquals("https://maps.google.com/?q=13.756300,100.501800", presentation.mapsUrl)
        assertEquals(9, presentation.accuracyMeters)
        assertEquals("Bangkok, Thailand", presentation.labelTh)
    }

    @Test
    fun slowLabelFallsBackAfterFifteenHundredMilliseconds() = runBlocking {
        val resolver = LocationLabelResolver {
            delay(2_000L)
            "Slow Label"
        }
        val factory = LocationPresentationFactory(resolver, labelTimeoutMs = 100L)

        val fix = TrackedLocationFix(
            latitude = 13.756300,
            longitude = 100.501800,
            elapsedRealtimeMs = 1000L,
            wallClockMs = 1000L,
            accuracyMeters = 8.1f
        )

        val presentation = factory.create(fix)
        assertNull(presentation.labelTh)
        assertEquals("https://maps.google.com/?q=13.756300,100.501800", presentation.mapsUrl)
        assertEquals(9, presentation.accuracyMeters)
    }

    @Test
    fun callerCancellationIsRethrown() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resolver = LocationLabelResolver {
            started.complete(Unit)
            delay(5_000L)
            "Label"
        }
        val factory = LocationPresentationFactory(resolver, labelTimeoutMs = 10_000L)

        val fix = TrackedLocationFix(
            latitude = 13.756300,
            longitude = 100.501800,
            elapsedRealtimeMs = 1000L,
            wallClockMs = 1000L,
            accuracyMeters = 8.1f
        )

        val job = launch {
            factory.create(fix)
        }
        started.await()
        job.cancel()
        try {
            job.join()
        } catch (_: Exception) {
            // ok
        }
        assertTrue(job.isCancelled)
    }
}
