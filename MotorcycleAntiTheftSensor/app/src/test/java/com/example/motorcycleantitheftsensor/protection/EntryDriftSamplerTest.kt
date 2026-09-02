package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The measurement behind the overnight drift recording.
 *
 * Its whole value is that the number can be trusted, so these pin the two ways it could
 * quietly lie: a baseline that moves, and thinning that swallows a spike.
 */
class EntryDriftSamplerTest {

    /** Rotation of [degrees] about the z axis, which is yaw for a phone lying flat. */
    private fun yaw(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(w = cos(half), x = 0.0, y = 0.0, z = sin(half))
    }

    @Test
    fun theFirstSampleBecomesTheOriginAndReportsZero() {
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)

        val row = sampler.onSample(5_000L, yaw(42.0))

        assertNotNull(row)
        requireNotNull(row)
        assertEquals(0L, row.elapsedMs)
        assertEquals(0.0, row.totalDeg, 1e-9)
        assertEquals(0.0, row.twistDeg, 1e-9)
        assertTrue(sampler.started)
    }

    @Test
    fun elapsedTimeIsMeasuredFromTheFirstSampleNotFromTheClock() {
        // The recording starts when the first reading arrives, which is not when the
        // service was told to start; a row stamped with raw uptime would be unreadable.
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        sampler.onSample(900_000L, yaw(0.0))

        val row = requireNotNull(sampler.onSample(903_000L, yaw(1.0)))

        assertEquals(3_000L, row.elapsedMs)
    }

    @Test
    fun theBaselineNeverMovesHoweverLongTheRecordingRuns() {
        // The door watch freezes its baseline for the whole armed session. A sampler that
        // rebaselined would report a flat line and quietly answer the wrong question.
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        sampler.onSample(0L, yaw(0.0))

        val first = requireNotNull(sampler.onSample(1_000L, yaw(3.0)))
        val second = requireNotNull(sampler.onSample(2_000L, yaw(6.0)))
        val third = requireNotNull(sampler.onSample(3_000L, yaw(9.0)))

        assertEquals(3.0, first.twistDeg, 1e-6)
        assertEquals(6.0, second.twistDeg, 1e-6)
        assertEquals(9.0, third.twistDeg, 1e-6)
    }

    @Test
    fun samplesBetweenRowsAreThinnedOut() {
        val sampler = EntryDriftSampler(rowIntervalMs = 10_000L)
        sampler.onSample(0L, yaw(0.0))

        assertNull(sampler.onSample(1_000L, yaw(1.0)))
        assertNull(sampler.onSample(9_999L, yaw(2.0)))
        assertNotNull(sampler.onSample(10_000L, yaw(3.0)))
    }

    @Test
    fun aSpikeBetweenRowsStillReachesThePeak() {
        // Thinning is for file size, not for the verdict. A jump that happened and went
        // away again is exactly what a false alert at 03:00 would look like.
        val sampler = EntryDriftSampler(rowIntervalMs = 10_000L)
        sampler.onSample(0L, yaw(0.0))
        sampler.onSample(2_000L, yaw(20.0))
        sampler.onSample(4_000L, yaw(0.5))
        sampler.onSample(10_000L, yaw(1.0))

        assertEquals(20.0, sampler.maxTwistDeg, 1e-6)
        assertEquals(20.0, sampler.maxTotalDeg, 1e-6)
    }

    @Test
    fun rotationAwayFromTheMeasuredAxisCountsAsResidualNotAsDoorAngle() {
        // A phone tipped off the table is not a door opening, and the two must not be
        // reported as the same number: the detection policy gates them separately.
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        sampler.onSample(0L, EntryQuaternion(1.0, 0.0, 0.0, 0.0))

        val half = Math.toRadians(30.0) / 2.0
        val pitched = EntryQuaternion(w = cos(half), x = sin(half), y = 0.0, z = 0.0)
        val row = requireNotNull(sampler.onSample(1_000L, pitched))

        assertEquals(0.0, row.twistDeg, 1e-6)
        assertEquals(30.0, row.swingDeg, 1e-6)
        assertEquals(30.0, row.totalDeg, 1e-6)
    }

    @Test
    fun rowsFormatAsCsvWithAStableColumnOrder() {
        val row = EntryDriftRow(elapsedMs = 12_345L, totalDeg = 1.5, twistDeg = 0.25, swingDeg = 2.0)

        assertEquals("elapsedMs,totalDeg,twistDeg,swingDeg", EntryDriftSampler.CSV_HEADER)
        assertEquals("12345,1.5000,0.2500,2.0000", EntryDriftSampler.formatRow(row))
    }

    @Test
    fun theRowCountMatchesWhatWasActuallyWritten() {
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        val written = listOf(0L, 500L, 1_000L, 1_400L, 2_000L, 3_000L)
            .count { sampler.onSample(it, yaw(it / 1_000.0)) != null }

        assertEquals(written, sampler.rowCount)
    }
}
