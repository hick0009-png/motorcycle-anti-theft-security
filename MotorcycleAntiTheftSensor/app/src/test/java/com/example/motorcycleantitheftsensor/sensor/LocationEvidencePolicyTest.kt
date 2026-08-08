package com.example.motorcycleantitheftsensor.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationEvidencePolicyTest {
    private val policy = LocationEvidencePolicy(
        maxAgeMs = 30_000L,
        maxAccuracyMeters = 100f,
    )

    @Test
    fun freshAccurateFixIsAcceptedWithMeasuredAge() {
        val result = policy.evaluate(
            fix = LocationFix(
                latitude = 13.7563,
                longitude = 100.5018,
                elapsedRealtimeMs = 90_000L,
                accuracyMeters = 25f,
            ),
            nowElapsedMs = 100_000L,
        )

        assertEquals(
            LocationEvidence.Available(ageMs = 10_000L, accuracyMeters = 25f),
            result,
        )
    }

    @Test
    fun staleFixIsRejectedWithHonestReason() {
        val result = policy.evaluate(
            fix = LocationFix(13.7563, 100.5018, 60_000L, 25f),
            nowElapsedMs = 100_001L,
        )

        assertEquals(LocationEvidence.Unavailable("stale fix"), result)
    }

    @Test
    fun inaccurateFixIsRejectedWithHonestReason() {
        val result = policy.evaluate(
            fix = LocationFix(13.7563, 100.5018, 99_000L, 100.1f),
            nowElapsedMs = 100_000L,
        )

        assertEquals(LocationEvidence.Unavailable("inaccurate fix"), result)
    }

    @Test
    fun absentFixIsUnavailable() {
        val result = policy.evaluate(fix = null, nowElapsedMs = 100_000L)

        assertTrue(result is LocationEvidence.Unavailable)
        assertEquals("no recent fix", (result as LocationEvidence.Unavailable).reason)
    }
}
