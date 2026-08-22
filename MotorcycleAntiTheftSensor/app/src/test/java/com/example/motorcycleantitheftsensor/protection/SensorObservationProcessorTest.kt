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
    fun calibratedSourceDoesNotUseAnotherSourceAsItsVibrationBaselineAfterArming() {
        processor.accept(
            observation(
                source = SensorSource.ACCELEROMETER,
                normalizedValue = 9.8,
                baselineDelta = 0.01,
            ),
            nowElapsedMs = 1_000L,
            arming = true,
        )

        repeat(3) { index ->
            assertEquals(
                ObservationDecision.Debounced,
                processor.accept(
                    observation(
                        source = SensorSource.GYROSCOPE,
                        normalizedValue = 0.02,
                        baselineDelta = 0.01,
                        eventElapsedMs = 1_100L + index,
                    ),
                    nowElapsedMs = 1_100L + index,
                    arming = false,
                ),
            )
        }
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

    @Test
    fun thermalDiagnosticUsesAbsoluteSafeLimitInsteadOfBaselineDelta() {
        val thermalProcessor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(SensorKind.POWER_THERMAL to 1),
            thresholdDeltas = mapOf(SensorKind.POWER_THERMAL to 5.0),
            absoluteMinimumsByDiagnostic = mapOf("temperature_celsius" to 45.0),
        )
        thermalProcessor.seedBaseline(SensorKind.POWER_THERMAL, SensorBaseline(44.0, 3))

        assertEquals(
            ObservationDecision.Debounced,
            thermalProcessor.accept(
                observation(
                    kind = SensorKind.POWER_THERMAL,
                    normalizedValue = 44.9,
                    diagnostic = "temperature_celsius",
                ),
                nowElapsedMs = 1_000L,
                arming = false,
            ),
        )
        assertTrue(
            thermalProcessor.accept(
                observation(
                    kind = SensorKind.POWER_THERMAL,
                    normalizedValue = 45.0,
                    diagnostic = "temperature_celsius",
                ),
                nowElapsedMs = 1_000L,
                arming = false,
            ) is ObservationDecision.Accepted,
        )
    }

    @Test
    fun typedAudioThreatBypassesLegacyBaselineAndIsAcceptedDirectly() {
        val audioThreat = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.85,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        val typedObservation = observation(
            kind = SensorKind.MICROPHONE,
            eventElapsedMs = 1000L,
            normalizedValue = 0.85,
        ).copy(audioThreat = audioThreat)

        val decision = processor.accept(typedObservation, nowElapsedMs = 1000L, arming = false)
        assertTrue(decision is ObservationDecision.Accepted)
        assertEquals(audioThreat, (decision as ObservationDecision.Accepted).observation.audioThreat)
    }

    @Test
    fun untypedMicrophoneObservationIsRejected() {
        val untypedObservation = observation(
            kind = SensorKind.MICROPHONE,
            eventElapsedMs = 1000L,
            normalizedValue = 0.85,
        )
        val decision = processor.accept(untypedObservation, nowElapsedMs = 1000L, arming = false)
        assertTrue(decision is ObservationDecision.Rejected)
    }
}

private fun observation(
    kind: SensorKind = SensorKind.VIBRATION,
    source: SensorSource? = null,
    eventElapsedMs: Long = 1_000L,
    normalizedValue: Double = 9.8,
    baselineDelta: Double = 0.0,
    valid: Boolean = true,
    diagnostic: String? = "test sample",
): SensorObservation = SensorObservation(
    kind = kind,
    source = source,
    eventElapsedMs = eventElapsedMs,
    wallClockMs = 1_700_000_000_000L,
    normalizedValue = normalizedValue,
    baselineDelta = baselineDelta,
    valid = valid,
    diagnostic = diagnostic,
)
