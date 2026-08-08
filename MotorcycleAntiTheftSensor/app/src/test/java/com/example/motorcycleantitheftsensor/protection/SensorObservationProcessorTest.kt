package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorObservationProcessorTest {
    private val processor = SensorObservationProcessor(
        staleAfterMs = 5_000L,
        debounceSamples = mapOf(
            SensorKind.VIBRATION to 3,
            SensorKind.LIGHT to 2,
        ),
        thresholdDeltas = mapOf(
            SensorKind.VIBRATION to 2.0,
            SensorKind.LIGHT to 10.0,
        ),
    )

    @Test
    fun invalidAndStaleSamplesAreRejected() {
        assertEquals(
            ObservationDecision.Rejected("invalid sample"),
            processor.accept(observation(valid = false), nowElapsedMs = 10_000L, arming = false),
        )
        assertEquals(
            ObservationDecision.Rejected("stale sample"),
            processor.accept(observation(eventElapsedMs = 1_000L), nowElapsedMs = 6_001L, arming = false),
        )
    }

    @Test
    fun armingSamplesUpdateBaselineButDoNotEmitEvidence() {
        val decision = processor.accept(
            observation(normalizedValue = 9.8),
            nowElapsedMs = 1_000L,
            arming = true,
        )

        assertEquals(ObservationDecision.BaselineUpdated, decision)
        assertEquals(9.8, processor.baseline(SensorKind.VIBRATION)?.average ?: 0.0, 0.001)
        assertEquals(1, processor.baseline(SensorKind.VIBRATION)?.sampleCount)
    }

    @Test
    fun vibrationNeedsThreeConsecutiveThresholdExceedances() {
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))

        assertEquals(
            ObservationDecision.Debounced,
            processor.accept(observation(normalizedValue = 12.0), 1_000L, false),
        )
        assertEquals(
            ObservationDecision.Debounced,
            processor.accept(observation(normalizedValue = 12.1), 1_100L, false),
        )
        val accepted = processor.accept(
            observation(normalizedValue = 12.2, eventElapsedMs = 1_200L),
            1_200L,
            false,
        ) as ObservationDecision.Accepted

        assertEquals(2.4, accepted.observation.baselineDelta, 0.001)
    }

    @Test
    fun belowThresholdSampleResetsConsecutiveDebounce() {
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        processor.accept(observation(normalizedValue = 12.0), 1_000L, false)
        processor.accept(observation(normalizedValue = 10.0), 1_100L, false)

        assertEquals(
            ObservationDecision.Debounced,
            processor.accept(observation(normalizedValue = 12.2), 1_200L, false),
        )
    }

    @Test
    fun sensitivityMappingValidatesRangeAndUpdatesVibrationThreshold() {
        assertFalse(processor.setVibrationSensitivity(0))
        assertTrue(processor.setVibrationSensitivity(10))
        assertEquals(0.5, processor.thresholdDelta(SensorKind.VIBRATION), 0.001)
    }

    @Test
    fun discretePowerObservationIsAcceptedWithoutThresholdConfiguration() {
        val accepted = processor.accept(
            observation(
                kind = SensorKind.POWER_THERMAL,
                normalizedValue = 1.0,
                diagnostic = "charger_disconnected",
            ),
            nowElapsedMs = 1_000L,
            arming = false,
        )

        assertTrue(accepted is ObservationDecision.Accepted)
    }
}

private fun observation(
    kind: SensorKind = SensorKind.VIBRATION,
    eventElapsedMs: Long = 1_000L,
    normalizedValue: Double = 9.8,
    valid: Boolean = true,
    diagnostic: String? = "test sample",
): SensorObservation = SensorObservation(
    kind = kind,
    eventElapsedMs = eventElapsedMs,
    wallClockMs = 1_700_000_000_000L,
    normalizedValue = normalizedValue,
    baselineDelta = 0.0,
    valid = valid,
    diagnostic = diagnostic,
    source = IncidentSource.REAL,
)
