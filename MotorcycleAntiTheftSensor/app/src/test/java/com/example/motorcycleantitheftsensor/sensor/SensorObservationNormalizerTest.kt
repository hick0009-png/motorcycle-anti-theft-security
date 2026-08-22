package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.protection.SensorUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorObservationNormalizerTest {

    private val normalizer = SensorObservationNormalizer()

    @Test
    fun accelerometerNormalizesMagnitudeAndBaselineDelta() {
        val sample = RawSensorSample(
            source = SensorSource.ACCELEROMETER,
            timestampNs = 1_000_000L,
            values = floatArrayOf(0f, 0f, 12.0f),
            accuracy = 3
        )
        val readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0, 0.0, 9.8), noiseEnvelope = 0.1)

        val obs = normalizer.normalize(
            sample = sample,
            readiness = readiness,
            role = SensorRole.PRIMARY,
            generationId = 1L,
            wallClockMs = 1000L,
            elapsedMs = 1000L,
        )

        assertTrue(obs.valid)
        assertEquals(SensorSource.ACCELEROMETER, obs.source)
        assertEquals(SensorUnit.METERS_PER_SECOND_SQUARED, obs.unit)
        assertEquals(12.0, obs.normalizedValue, 0.01)
        assertEquals(2.2, obs.baselineDelta, 0.01)
    }

    @Test
    fun nonFiniteValuesResultInInvalidObservation() {
        val sample = RawSensorSample(
            source = SensorSource.ACCELEROMETER,
            timestampNs = 1_000_000L,
            values = floatArrayOf(Float.NaN, 0f, 0f),
            accuracy = 0
        )
        val readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0, 0.0, 0.0), noiseEnvelope = 0.1)

        val obs = normalizer.normalize(
            sample = sample,
            readiness = readiness,
            role = SensorRole.PRIMARY,
            generationId = 1L,
        )

        assertFalse(obs.valid)
    }

    @Test
    fun gyroscopePreservesIdentityAndRadiansPerSecondUnit() {
        val sample = RawSensorSample(
            source = SensorSource.GYROSCOPE,
            timestampNs = 1_000_000L,
            values = floatArrayOf(0.5f, 1.0f, 0.0f),
            accuracy = 3
        )
        val obs = normalizer.normalize(
            sample = sample,
            readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0, 0.0, 0.0), noiseEnvelope = 0.01),
            role = SensorRole.SUPPORTING,
            generationId = 1L,
        )
        assertTrue(obs.valid)
        assertEquals(SensorSource.GYROSCOPE, obs.source)
        assertEquals(SensorUnit.RADIANS_PER_SECOND, obs.unit)
        assertEquals(1.118, obs.normalizedValue, 0.01)
    }

    @Test
    fun gyroscopeSubtractsTheCalibratedBiasBeforeReportingMovementDelta() {
        val sample = RawSensorSample(
            source = SensorSource.GYROSCOPE,
            timestampNs = 1_000_000L,
            values = floatArrayOf(0.5f, 1.0f, 0.0f),
            accuracy = 3,
        )

        val observation = normalizer.normalize(
            sample = sample,
            readiness = SensorReadiness.Ready(
                baseline = doubleArrayOf(0.5, 1.0, 0.0),
                noiseEnvelope = 0.01,
            ),
            role = SensorRole.SUPPORTING,
            generationId = 1L,
        )

        assertEquals(0.0, observation.baselineDelta, 0.001)
    }

    @Test
    fun rotationVectorPreservesIdentityAndDegreesUnit() {
        val sample = RawSensorSample(
            source = SensorSource.ROTATION_VECTOR,
            timestampNs = 1_000_000L,
            values = floatArrayOf(0.0f, 0.707f, 0.707f),
            accuracy = 3
        )
        val obs = normalizer.normalize(
            sample = sample,
            readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0, 0.0, 1.0), noiseEnvelope = 0.01),
            role = SensorRole.SUPPORTING,
            generationId = 1L,
        )
        assertTrue(obs.valid)
        assertEquals(SensorSource.ROTATION_VECTOR, obs.source)
        assertEquals(SensorUnit.DEGREES, obs.unit)
    }

    @Test
    fun magneticFieldPreservesIdentityAndMicroteslaUnit() {
        val sample = RawSensorSample(
            source = SensorSource.MAGNETIC_FIELD,
            timestampNs = 1_000_000L,
            values = floatArrayOf(30.0f, 40.0f, 0.0f),
            accuracy = 3
        )
        val obs = normalizer.normalize(
            sample = sample,
            readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0, 0.0, 0.0), noiseEnvelope = 0.1),
            role = SensorRole.SUPPORTING,
            generationId = 1L,
        )
        assertTrue(obs.valid)
        assertEquals(SensorSource.MAGNETIC_FIELD, obs.source)
        assertEquals(SensorUnit.MICROTESLA, obs.unit)
        assertEquals(50.0, obs.normalizedValue, 0.01)
    }

    @Test
    fun proximityPreservesIdentityAndNormalizedStateUnit() {
        val sample = RawSensorSample(
            source = SensorSource.PROXIMITY,
            timestampNs = 1_000_000L,
            values = floatArrayOf(5.0f),
            accuracy = 3
        )
        val obs = normalizer.normalize(
            sample = sample,
            readiness = SensorReadiness.Ready(baseline = doubleArrayOf(0.0), noiseEnvelope = 0.0),
            role = SensorRole.SUPPORTING,
            generationId = 1L,
        )
        assertTrue(obs.valid)
        assertEquals(SensorSource.PROXIMITY, obs.source)
        assertEquals(SensorUnit.NORMALIZED_STATE, obs.unit)
        assertEquals(5.0, obs.normalizedValue, 0.01)
    }
}
