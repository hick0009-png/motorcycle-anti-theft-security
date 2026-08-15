package com.example.motorcycleantitheftsensor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFixArbiterTest {

    private val arbiter = LocationFixArbiter(maxAccuracyMeters = 100f)

    @Test
    fun acceptsFirstValidFix() {
        val fix = fix(13.7563, 100.5018, 1000L, 10f)
        assertTrue(arbiter.accept(fix, 1000L))
        assertEquals(fix, arbiter.currentFix())
    }

    @Test
    fun rejectsInvalidCoordinatesOrAccuracyOrFuture() {
        assertFalse(arbiter.accept(fix(Double.NaN, 100.0, 1000L, 10f), 1000L))
        assertFalse(arbiter.accept(fix(91.0, 100.0, 1000L, 10f), 1000L))
        assertFalse(arbiter.accept(fix(13.0, 181.0, 1000L, 10f), 1000L))
        assertFalse(arbiter.accept(fix(13.0, 100.0, 1000L, -1f), 1000L))
        assertFalse(arbiter.accept(fix(13.0, 100.0, 1000L, 101f), 1000L))
        assertFalse(arbiter.accept(fix(13.0, 100.0, 2000L, 10f), 1000L)) // Future fix
        assertNull(arbiter.currentFix())
    }

    @Test
    fun rejectsOlderElapsedTimestamp() {
        val fix1 = fix(13.7563, 100.5018, 2000L, 10f)
        val fix2 = fix(13.7564, 100.5019, 1500L, 5f)

        assertTrue(arbiter.accept(fix1, 2000L))
        assertFalse(arbiter.accept(fix2, 2000L))
        assertEquals(fix1, arbiter.currentFix())
    }

    @Test
    fun withinSameSecondBucketPrefersBetterAccuracy() {
        val fix1 = fix(13.7563, 100.5018, 1000L, 20f)
        val fixBetter = fix(13.7564, 100.5019, 1400L, 5f)
        val fixWorse = fix(13.7565, 100.5020, 1800L, 15f)

        assertTrue(arbiter.accept(fix1, 2000L))
        assertTrue(arbiter.accept(fixBetter, 2000L))
        assertEquals(fixBetter, arbiter.currentFix())

        // fixWorse has worse accuracy than fixBetter in the same 1-sec bucket (delta = 400ms < 1000ms)
        assertFalse(arbiter.accept(fixWorse, 2000L))
        assertEquals(fixBetter, arbiter.currentFix())
    }

    @Test
    fun equalTimestampReplacesOnlyIfAccuracyIsStrictlyBetter() {
        val fix1 = fix(13.7563, 100.5018, 1000L, 20f)
        val fixEqualAcc = fix(13.7564, 100.5019, 1000L, 20f)
        val fixBetterAcc = fix(13.7565, 100.5020, 1000L, 10f)

        assertTrue(arbiter.accept(fix1, 1000L))
        assertFalse(arbiter.accept(fixEqualAcc, 1000L))
        assertTrue(arbiter.accept(fixBetterAcc, 1000L))
        assertEquals(fixBetterAcc, arbiter.currentFix())
    }

    @Test
    fun newerSecondBucketAlwaysAcceptedIfValid() {
        val fix1 = fix(13.7563, 100.5018, 1000L, 5f)
        val fix2 = fix(13.7564, 100.5019, 2100L, 15f)

        assertTrue(arbiter.accept(fix1, 3000L))
        assertTrue(arbiter.accept(fix2, 3000L))
        assertEquals(fix2, arbiter.currentFix())
    }

    @Test
    fun resetClearsState() {
        arbiter.accept(fix(13.7563, 100.5018, 1000L, 5f), 1000L)
        arbiter.reset()
        assertNull(arbiter.currentFix())
    }

    private fun fix(lat: Double, lon: Double, elapsed: Long, accuracy: Float) =
        TrackedLocationFix(lat, lon, elapsed, 123456L, accuracy)
}
