package com.example.motorcycleantitheftsensor.location

import org.junit.Assert.*
import org.junit.Test

class MovementDisplacementPolicyTest {

    @Test
    fun distanceCalculationIsCorrect() {
        // Roughly 111 meters north of equator
        val d = MovementDisplacementPolicy.calculateHaversineDistance(0.0, 0.0, 0.001, 0.0)
        assertTrue("Distance should be around 111m, was $d", d in 110.0..112.0)
    }

    @Test
    fun unusableFixesAreIgnored() {
        val policy = MovementDisplacementPolicy()
        val anchorFix = TrackedLocationFix(0.0, 0.0, 1000L, 1000L, 5f)
        policy.reset(ParkingAnchor(anchorFix, "session1"))

        // Future elapsed time
        val futureFix = TrackedLocationFix(0.001, 0.0, 5000L, 5000L, 5f)
        assertTrue(policy.evaluate(futureFix, 4000L) is MovementDecision.AwaitingUsableFix)

        // Stale fix
        val staleFix = TrackedLocationFix(0.001, 0.0, 1000L, 1000L, 5f)
        assertTrue(policy.evaluate(staleFix, 1000L + MovementDisplacementPolicy.MAX_FIX_AGE_MS + 1) is MovementDecision.AwaitingUsableFix)

        // Bad accuracy
        val badAccuracyFix = TrackedLocationFix(0.001, 0.0, 5000L, 5000L, 101f)
        assertTrue(policy.evaluate(badAccuracyFix, 5000L) is MovementDecision.AwaitingUsableFix)
    }

    @Test
    fun insideAnchorResetsCounter() {
        val policy = MovementDisplacementPolicy()
        val anchorFix = TrackedLocationFix(0.0, 0.0, 1000L, 1000L, 5f)
        policy.reset(ParkingAnchor(anchorFix, "session1"))

        // Outside fix
        val outsideFix = TrackedLocationFix(0.002, 0.0, 2000L, 2000L, 5f)
        val r1 = policy.evaluate(outsideFix, 2000L)
        assertTrue(r1 is MovementDecision.Candidate)

        // Inside fix
        val insideFix = TrackedLocationFix(0.0, 0.0, 3000L, 3000L, 5f)
        val r2 = policy.evaluate(insideFix, 3000L)
        assertTrue(r2 is MovementDecision.InsideAnchor)

        // Outside fix again - should be Candidate(1) not Confirmed
        val outsideFix2 = TrackedLocationFix(0.002, 0.0, 20000L, 20000L, 5f)
        val r3 = policy.evaluate(outsideFix2, 20000L)
        assertTrue(r3 is MovementDecision.Candidate)
        assertEquals(1, (r3 as MovementDecision.Candidate).consecutiveOutsideFixes)
    }

    @Test
    fun twoValidOutsideFixesConfirmDisplacement() {
        val policy = MovementDisplacementPolicy()
        val anchorFix = TrackedLocationFix(0.0, 0.0, 1000L, 1000L, 5f)
        policy.reset(ParkingAnchor(anchorFix, "session1"))

        // First outside fix
        val outsideFix1 = TrackedLocationFix(0.002, 0.0, 2000L, 2000L, 5f)
        val r1 = policy.evaluate(outsideFix1, 2000L)
        assertTrue(r1 is MovementDecision.Candidate)

        // Second outside fix < 15s later -> Still candidate
        val outsideFix2 = TrackedLocationFix(0.002, 0.0, 10000L, 10000L, 5f)
        val r2 = policy.evaluate(outsideFix2, 10000L)
        assertTrue(r2 is MovementDecision.Candidate)

        // Third outside fix >= 15s later from first -> Confirmed
        val outsideFix3 = TrackedLocationFix(0.002, 0.0, 17001L, 17001L, 5f)
        val r3 = policy.evaluate(outsideFix3, 17001L)
        assertTrue(r3 is MovementDecision.Confirmed)
    }

    // === Task 1 RED tests: confirm the coordinator cannot break displacement ===

    @Test
    fun twoOutsideFixesSeparatedByFifteenSecondsConfirmWithoutReset() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        assertTrue(policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L) is MovementDecision.Candidate)
        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun unusableFixBetweenCandidatesDoesNotEraseCandidate() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)
        policy.evaluate(fix(Double.NaN, 0.0, 10_000L, 5f), 10_000L)

        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun latitudeOutOfRangeIsUnusable() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        // Outside fix candidate
        policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)

        // Latitude out of range should not erase candidate
        val outOfRange = TrackedLocationFix(91.0, 0.0, 10_000L, 10_000L, 5f)
        val result = policy.evaluate(outOfRange, 10_000L)
        assertTrue("Out-of-range lat should not confirm", result !is MovementDecision.Confirmed)

        // Valid fix should still confirm
        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun longitudeOutOfRangeIsUnusable() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)

        val outOfRange = TrackedLocationFix(0.0, 181.0, 10_000L, 10_000L, 5f)
        val result = policy.evaluate(outOfRange, 10_000L)
        assertTrue("Out-of-range lon should not confirm", result !is MovementDecision.Confirmed)

        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun nanAccuracyIsUnusable() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)

        val nanAccuracy = TrackedLocationFix(0.002, 0.0, 10_000L, 10_000L, Float.NaN)
        val result = policy.evaluate(nanAccuracy, 10_000L)
        assertTrue("NaN accuracy should not confirm", result !is MovementDecision.Confirmed)

        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun negativeAccuracyIsUnusable() {
        val policy = MovementDisplacementPolicy()
        policy.reset(ParkingAnchor(fix(0.0, 0.0, 1_000L, 5f), "armed-1"))

        policy.evaluate(fix(0.002, 0.0, 2_000L, 5f), 2_000L)

        val negAccuracy = TrackedLocationFix(0.002, 0.0, 10_000L, 10_000L, -1f)
        val result = policy.evaluate(negAccuracy, 10_000L)
        assertTrue("Negative accuracy should not confirm", result !is MovementDecision.Confirmed)

        assertTrue(policy.evaluate(fix(0.002, 0.0, 17_000L, 5f), 17_000L) is MovementDecision.Confirmed)
    }

    @Test
    fun futureFixCannotBecomeUsable() {
        val policy = MovementDisplacementPolicy()
        assertFalse(policy.isUsableFix(fix(0.0, 0.0, 11_000L, 5f), 10_000L))
    }

    @Test
    fun staleFixCannotBecomeUsable() {
        val policy = MovementDisplacementPolicy()
        assertFalse(policy.isUsableFix(fix(0.0, 0.0, 1_000L, 5f), 31_001L))
    }

    @Test
    fun fixAtExactMaxAgeIsUsable() {
        val policy = MovementDisplacementPolicy()
        assertTrue(policy.isUsableFix(fix(0.0, 0.0, 1_000L, 5f), 31_000L))
    }

    @Test
    fun nanCoordinatesAreNotUsable() {
        val policy = MovementDisplacementPolicy()
        assertFalse(policy.isUsableFix(fix(Double.NaN, 0.0, 10_000L, 5f), 10_000L))
        assertFalse(policy.isUsableFix(fix(0.0, Double.NaN, 10_000L, 5f), 10_000L))
    }

    @Test
    fun outOfRangeCoordinatesAreNotUsable() {
        val policy = MovementDisplacementPolicy()
        assertFalse(policy.isUsableFix(fix(91.0, 0.0, 10_000L, 5f), 10_000L))
        assertFalse(policy.isUsableFix(fix(-91.0, 0.0, 10_000L, 5f), 10_000L))
        assertFalse(policy.isUsableFix(fix(0.0, 181.0, 10_000L, 5f), 10_000L))
        assertFalse(policy.isUsableFix(fix(0.0, -181.0, 10_000L, 5f), 10_000L))
    }

    @Test
    fun invalidAccuracyIsNotUsable() {
        val policy = MovementDisplacementPolicy()
        assertFalse(policy.isUsableFix(fix(0.0, 0.0, 10_000L, Float.NaN), 10_000L))
        assertFalse(policy.isUsableFix(fix(0.0, 0.0, 10_000L, -1f), 10_000L))
        assertFalse(policy.isUsableFix(fix(0.0, 0.0, 10_000L, 101f), 10_000L))
        assertTrue(policy.isUsableFix(fix(0.0, 0.0, 10_000L, 100f), 10_000L))
        assertTrue(policy.isUsableFix(fix(0.0, 0.0, 10_000L, 0f), 10_000L))
    }

    @Test
    fun storedFixMayBeOlderThanIncomingFreshnessWindow() {
        val policy = MovementDisplacementPolicy()
        val fix = TrackedLocationFix(
            latitude = 13.7563,
            longitude = 100.5018,
            elapsedRealtimeMs = 1_000L,
            wallClockMs = 1_000L,
            accuracyMeters = 20f,
        )

        assertTrue(policy.isValidStoredFix(fix))
        assertFalse(policy.isUsableFix(fix, nowElapsedMs = 31_001L))
    }

    @Test
    fun storedFixStillRejectsInvalidCoordinatesAndAccuracy() {
        val policy = MovementDisplacementPolicy()
        val invalidCoordinate = fix(lat = Double.NaN, lon = 100.5018, elapsed = 1_000L, accuracy = 20f)
        val invalidAccuracy = fix(lat = 13.7563, lon = 100.5018, elapsed = 1_000L, accuracy = 101f)

        assertFalse(policy.isValidStoredFix(invalidCoordinate))
        assertFalse(policy.isValidStoredFix(invalidAccuracy))
    }

    private fun fix(lat: Double, lon: Double, elapsed: Long, accuracy: Float) =
        TrackedLocationFix(lat, lon, elapsed, elapsed, accuracy)
}
