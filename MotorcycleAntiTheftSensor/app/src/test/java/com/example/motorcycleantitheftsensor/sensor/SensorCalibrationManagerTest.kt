package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationManagerTest {

    @Test
    fun calibrationProgressAdvancesAndProducesReadyBaseline() {
        val manager = SensorCalibrationManager(calibrationDurationMs = 1000L)
        val genId = 42L
        val startTime = 10000L

        manager.startCalibration(genId, setOf(SensorSource.ACCELEROMETER), startTime)

        // Halfway
        val midReadiness = manager.getReadiness(SensorSource.ACCELEROMETER, genId, startTime + 500L)
        assertTrue(midReadiness is SensorReadiness.Calibrating)
        assertEquals(0.5f, (midReadiness as SensorReadiness.Calibrating).progress, 0.05f)

        // Record samples
        manager.recordSample(genId, RawSensorSample(SensorSource.ACCELEROMETER, 100L, floatArrayOf(0f, 0f, 9.8f), 3))
        manager.recordSample(genId, RawSensorSample(SensorSource.ACCELEROMETER, 200L, floatArrayOf(0f, 0f, 10.0f), 3))

        // Finished
        val finalReadiness = manager.getReadiness(SensorSource.ACCELEROMETER, genId, startTime + 1000L)
        assertTrue(finalReadiness is SensorReadiness.Ready)
        val ready = finalReadiness as SensorReadiness.Ready
        assertEquals(3, ready.baseline.size)
        assertEquals(9.9, ready.baseline[2], 0.01)
    }

    @Test
    fun staleGenerationIsRejected() {
        val manager = SensorCalibrationManager(calibrationDurationMs = 1000L)
        manager.startCalibration(1L, setOf(SensorSource.ACCELEROMETER), 1000L)

        val readiness = manager.getReadiness(SensorSource.ACCELEROMETER, 2L, 2000L)
        assertTrue(readiness is SensorReadiness.Failed)
    }

    @Test
    fun baselineIsCachedAndNotRecomputedOnSubsequentCalls() {
        val manager = SensorCalibrationManager(calibrationDurationMs = 1000L)
        val genId = 100L
        val startTime = 5000L

        manager.startCalibration(genId, setOf(SensorSource.ACCELEROMETER), startTime)
        manager.recordSample(genId, RawSensorSample(SensorSource.ACCELEROMETER, 100L, floatArrayOf(0f, 0f, 9.8f), 3))

        val readiness1 = manager.getReadiness(SensorSource.ACCELEROMETER, genId, startTime + 1000L)
        val readiness2 = manager.getReadiness(SensorSource.ACCELEROMETER, genId, startTime + 2000L)

        assertTrue(readiness1 is SensorReadiness.Ready)
        assertSame("Readiness instance should be cached", readiness1, readiness2)
    }
}
