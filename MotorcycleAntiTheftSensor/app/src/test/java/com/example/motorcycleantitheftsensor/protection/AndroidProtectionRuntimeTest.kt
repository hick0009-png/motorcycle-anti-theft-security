package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProtectionRuntimeTest {
    @Test
    fun armingObservationRecordsHealthBeforeUpdatingBaselineWithoutOpeningIncident() {
        val events = mutableListOf<String>()
        lateinit var detectors: RecordingDetectorSet
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(SensorKind.VIBRATION to 1),
            thresholdDeltas = mapOf(SensorKind.VIBRATION to 1.0),
        )
        val runtime = AndroidProtectionRuntime(
            readinessProvider = { ReadinessReport(emptySet(), emptySet()) },
            detectorFactory = { callback ->
                RecordingDetectorSet(callback).also { detectors = it }
            },
            observationProcessor = processor,
            elapsedClock = ProtectionClock { 1_000L },
            stateProvider = { ProtectionState.ARMING },
            sensorSampleRecorder = { kind, _, _, _ -> events += "health:$kind" },
            incidentConsumer = { events += "incident:${it.primary.kind}" },
        )

        runtime.startDetectors()
        detectors.emit(vibrationObservation(value = 9.8))

        assertEquals(listOf("health:VIBRATION"), events)
        assertEquals(9.8, processor.baseline(SensorKind.VIBRATION)?.average ?: 0.0, 0.0001)
    }

    @Test
    fun sensitivityAppliesToDetectorAndObservationProcessor() {
        lateinit var detectors: RecordingDetectorSet
        val processor = SensorObservationProcessor(
            staleAfterMs = 5_000L,
            debounceSamples = mapOf(SensorKind.VIBRATION to 1),
            thresholdDeltas = mapOf(SensorKind.VIBRATION to 1.0),
        )
        val runtime = AndroidProtectionRuntime(
            readinessProvider = { ReadinessReport(emptySet(), emptySet()) },
            detectorFactory = { callback ->
                RecordingDetectorSet(callback).also { detectors = it }
            },
            observationProcessor = processor,
            elapsedClock = ProtectionClock { 1_000L },
            stateProvider = { ProtectionState.DISARMED_ONLINE },
            sensorSampleRecorder = { _, _, _, _ -> },
            incidentConsumer = { },
        )

        runtime.applySensitivity(10)

        assertEquals(10, detectors.sensitivity)
        assertEquals(0.5, processor.thresholdDelta(SensorKind.VIBRATION), 0.0001)
        assertTrue(runtime.currentSensorHealth().isEmpty())
    }

    @Test
    fun startingNewDetectorSessionClearsPreviousBaseline() {
        val processor = processor()
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        val runtime = runtime(processor = processor)

        runtime.startDetectors()

        assertEquals(null, processor.baseline(SensorKind.VIBRATION))
    }

    @Test
    fun invalidAndStaleObservationsDoNotRecordHealthySamples() {
        val healthEvents = mutableListOf<SensorKind>()
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = processor(),
            elapsedNowMs = 10_000L,
            sensorSampleRecorder = { kind, _, _, _ -> healthEvents += kind },
            detectorCapture = { detectors = it },
        )

        detectors.emit(vibrationObservation(value = 12.0, valid = false))
        detectors.emit(vibrationObservation(value = 12.0, eventElapsedMs = 1_000L))

        assertTrue(healthEvents.isEmpty())
    }

    @Test
    fun acceptedPrimaryObservationIsEnrichedWithCurrentLocationEvidence() {
        val incidents = mutableListOf<SensorObservation>()
        lateinit var detectors: RecordingDetectorSet
        val processor = processor()
        val runtime = runtime(
            processor = processor,
            state = ProtectionState.ARMED_HEALTHY,
            incidentConsumer = { batch ->
                incidents += batch.primary
                incidents += batch.supplementalEvidence
            },
            detectorCapture = { detectors = it },
        )
        runtime.startDetectors()
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        detectors.locationObservation = SensorObservation(
            kind = SensorKind.LOCATION,
            eventElapsedMs = 950L,
            wallClockMs = 5_050L,
            normalizedValue = 1.0,
            baselineDelta = 0.0,
            valid = true,
            diagnostic = "fix age_ms=50 accuracy_m=8.0",
        )

        detectors.emit(vibrationObservation(value = 12.0))

        assertEquals(listOf(SensorKind.VIBRATION, SensorKind.LOCATION), incidents.map { it.kind })
    }
}

private fun processor(): SensorObservationProcessor = SensorObservationProcessor(
    staleAfterMs = 5_000L,
    debounceSamples = mapOf(SensorKind.VIBRATION to 1),
    thresholdDeltas = mapOf(SensorKind.VIBRATION to 1.0),
)

private fun runtime(
    processor: SensorObservationProcessor,
    elapsedNowMs: Long = 1_000L,
    state: ProtectionState = ProtectionState.ARMING,
    sensorSampleRecorder: (SensorKind, Long, String?, Double) -> Unit = { _, _, _, _ -> },
    incidentConsumer: (IncidentObservationBatch) -> Unit = { },
    detectorCapture: (RecordingDetectorSet) -> Unit = { },
): AndroidProtectionRuntime = AndroidProtectionRuntime(
    readinessProvider = { ReadinessReport(emptySet(), emptySet()) },
    detectorFactory = { callback -> RecordingDetectorSet(callback).also(detectorCapture) },
    observationProcessor = processor,
    elapsedClock = ProtectionClock { elapsedNowMs },
    stateProvider = { state },
    sensorSampleRecorder = sensorSampleRecorder,
    incidentConsumer = incidentConsumer,
)

private class RecordingDetectorSet(
    private val callback: (SensorObservation) -> Unit,
) : AndroidDetectorSet {
    var sensitivity: Int? = null
    var locationObservation: SensorObservation? = null

    override fun start(): DetectorStartResult = DetectorStartResult(started = true)

    override fun stop() = Unit

    override fun applySensitivity(level: Int) {
        sensitivity = level
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = emptyMap()

    override fun currentLocationObservation(): SensorObservation? = locationObservation

    fun emit(observation: SensorObservation) {
        callback(observation)
    }
}

private fun vibrationObservation(
    value: Double,
    valid: Boolean = true,
    eventElapsedMs: Long = 900L,
): SensorObservation = SensorObservation(
    kind = SensorKind.VIBRATION,
    eventElapsedMs = eventElapsedMs,
    wallClockMs = 5_000L,
    normalizedValue = value,
    baselineDelta = 0.0,
    valid = valid,
    diagnostic = "accelerometer_magnitude",
)
