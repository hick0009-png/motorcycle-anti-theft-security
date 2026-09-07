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
    fun aSpikeBetweenRowsIsStillSeenByTheMeasurement() {
        // Thinning is for file size, not for the verdict. A jump that happened and went away
        // again is exactly what a false alert at 03:00 would look like, and no row was due
        // while it was happening — but the measurement reads every sample, not every row.
        val sampler = EntryDriftSampler(rowIntervalMs = 10_000L)
        sampler.onSample(0L, yaw(0.0))
        sampler.onSample(2_000L, yaw(20.0))
        sampler.onSample(4_000L, yaw(0.5))
        sampler.onSample(10_000L, yaw(1.0))

        // The peak survives the thinning: it is measured from every sample and promoted into
        // the stretch when the interval is judged, which is the number the card reports.
        assertEquals(20.0, sampler.cleanMaxTwistDeg, 1e-6)
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

    /**
     * Feeds a still phone drifting at [degPerHour], one sample a second, and returns the
     * clock it stopped at. Drift is a slow walk; nothing here should ever look disturbed.
     */
    private fun feedStill(
        sampler: EntryDriftSampler,
        fromMs: Long,
        toMs: Long,
        degPerHour: Double,
        offsetDeg: Double = 0.0,
    ): Long {
        var t = fromMs
        while (t <= toMs) {
            sampler.onSample(t, yaw(offsetDeg + degPerHour * (t / 3_600_000.0)))
            t += 1_000L
        }
        return toMs
    }

    @Test
    fun anUntouchedNightIsOneStretchAndTheWholeOfItCounts() {
        val sampler = EntryDriftSampler()

        feedStill(sampler, 0L, 4L * 3_600_000L, degPerHour = 0.03)

        assertEquals(0, sampler.disturbanceCount)
        // Within one checkpoint of the full four hours.
        assertTrue(sampler.cleanMeasuredMs > 4L * 3_600_000L - 20_000L)
        assertEquals(0.12, sampler.cleanMaxTwistDeg, 0.01)
        assertEquals(0.03, sampler.measurement("rotation-vector", 0L).degPerHour, 0.005)
    }

    @Test
    fun thePhoneBeingPickedUpEndsTheStretchInsteadOfBecomingItsDrift() {
        // The case this was built for: eight good hours and one movement at dawn. Reported
        // straight, that movement is a rate that condemns hardware which is in fact fine.
        val sampler = EntryDriftSampler()
        feedStill(sampler, 0L, 8L * 3_600_000L, degPerHour = 0.03)

        // Lifted, and put back down somewhere else.
        feedStill(sampler, 8L * 3_600_000L + 1_000L, 8L * 3_600_000L + 600_000L, 0.0, offsetDeg = 90.0)

        assertEquals(1, sampler.disturbanceCount)
        assertTrue("the eight hours survive", sampler.cleanMeasuredMs > 8L * 3_600_000L - 20_000L)
        assertTrue("the movement is not drift", sampler.cleanMaxTwistDeg < 1.0)
        assertEquals(0.03, sampler.measurement("rotation-vector", 0L).degPerHour, 0.005)
    }

    @Test
    fun theLongestUndisturbedStretchIsTheOneReported() {
        val sampler = EntryDriftSampler()
        // A restless first hour, then a night nobody touched.
        feedStill(sampler, 0L, 3_600_000L, degPerHour = 0.03)
        feedStill(sampler, 3_601_000L, 3_700_000L, degPerHour = 0.05, offsetDeg = 40.0)
        feedStill(sampler, 3_701_000L, 3_701_000L + 6L * 3_600_000L, degPerHour = 0.05, offsetDeg = 40.0)

        assertTrue(sampler.cleanMeasuredMs > 6L * 3_600_000L - 20_000L)
        assertEquals(0.05, sampler.measurement("rotation-vector", 0L).degPerHour, 0.005)
    }

    @Test
    fun aMeasurementReportsTheHoursItActuallyMeasuredNotTheHoursItRanFor() {
        // The store keeps the longer measurement, so an inflated duration is not merely
        // untidy: it shadows every honest measurement taken afterwards.
        val sampler = EntryDriftSampler()
        feedStill(sampler, 0L, 2L * 3_600_000L, degPerHour = 0.03)
        feedStill(sampler, 2L * 3_600_000L + 1_000L, 6L * 3_600_000L, 0.0, offsetDeg = 120.0)

        val measurement = sampler.measurement("rotation-vector", 0L)

        assertTrue(measurement.measuredMs < sampler.measuredMs)
        assertTrue(measurement.measuredMs > 2L * 3_600_000L - 20_000L)
    }

    @Test
    fun ordinaryDriftNeverCountsAsAMovementHoweverLongItRuns() {
        // A phone drifting fast enough to be refused outright is still drifting, not moving:
        // the ceiling has to sit far above the worst rate that is still drift.
        val sampler = EntryDriftSampler()

        feedStill(sampler, 0L, 3L * 3_600_000L, degPerHour = 7.5)

        assertEquals(0, sampler.disturbanceCount)
        assertEquals(7.5, sampler.measurement("rotation-vector", 0L).degPerHour, 0.1)
    }

    @Test
    fun theRowCountMatchesWhatWasActuallyWritten() {
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        val written = listOf(0L, 500L, 1_000L, 1_400L, 2_000L, 3_000L)
            .count { sampler.onSample(it, yaw(it / 1_000.0)) != null }

        assertEquals(written, sampler.rowCount)
    }
}
