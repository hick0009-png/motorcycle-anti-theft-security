package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidProtectionRuntimeTest {
    @Test
    fun powerSessionDisconnectWaitsForArbiterAndSendsOneLocalizedTelegramMessage() = runTest {
        val telegramMessages = mutableListOf<String>()
        val engine = IncidentEngine(IncidentIdGenerator { "power-disconnect" })
        val deliveryPolicy = IncidentUpdateDeliveryPolicy()
        val delivery = IncidentDeliveryCoordinator(
            repository = RuntimeRecordingIncidentRepository(),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { message -> telegramMessages += message; true },
            sms = IncidentTransport { false },
        )
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = powerProcessor(),
            state = ProtectionState.ARMED_HEALTHY,
            incidentConsumer = { batch ->
                val update = engine.accept(batch.primary, ProtectionState.ARMED_HEALTHY)
                if (deliveryPolicy.action(update, nowElapsedMs = batch.primary.eventElapsedMs) == DeliveryAction.SEND) {
                    launch {
                        delivery.deliver(update, DeliveryConfiguration(smsConfigured = false))
                    }
                }
            },
            detectorCapture = { detectors = it },
        )
        runtime.beginPowerSession(
            sessionId = "power-session",
            model = testPowerWitnessModel(),
            settings = PowerProfileSettings(lossConfirmationMs = 10_000L),
        )

        detectors.emit(powerObservation(value = 1.0, diagnostic = "charger_disconnected"))
        advanceUntilIdle()
        assertTrue("Raw cable telemetry must not send immediately in POWER", telegramMessages.isEmpty())

        detectors.emit(powerObservation(value = 0.0, diagnostic = "power_charging_health"))
        detectors.emit(powerObservation(value = 0.0, diagnostic = "power_charging_health"))
        advanceUntilIdle()

        assertEquals(listOf("การชาร์จโทรศัพท์หยุด ตรวจสอบสายชาร์จ ที่ชาร์จ หรือพอร์ตชาร์จของโทรศัพท์"), telegramMessages)
        assertUserMessageHidesInternalPowerTokens(telegramMessages.single())
    }

    @Test
    fun nonPowerSessionDisconnectStillSendsOneLocalizedTelegramMessage() = runTest {
        val telegramMessages = mutableListOf<String>()
        val engine = IncidentEngine(IncidentIdGenerator { "vehicle-or-entry-disconnect" })
        val delivery = IncidentDeliveryCoordinator(
            repository = RuntimeRecordingIncidentRepository(),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { message -> telegramMessages += message; true },
            sms = IncidentTransport { false },
        )
        lateinit var detectors: RecordingDetectorSet
        runtime(
            processor = powerProcessor(),
            state = ProtectionState.ARMED_HEALTHY,
            incidentConsumer = { batch ->
                val update = engine.accept(batch.primary, ProtectionState.ARMED_HEALTHY)
                if (update is IncidentUpdate.Opened) {
                    launch {
                        delivery.deliver(update, DeliveryConfiguration(smsConfigured = false))
                    }
                }
            },
            detectorCapture = { detectors = it },
        )

        detectors.emit(powerObservation(value = 1.0, diagnostic = "charger_disconnected"))
        advanceUntilIdle()

        assertEquals(listOf("ตรวจพบว่าสายชาร์จถูกถอดออก"), telegramMessages)
        assertUserMessageHidesInternalPowerTokens(telegramMessages.single())
    }

    @Test
    fun powerStatusMonitorRetainsTheLatestWitnessLuxAfterArmedDetectorsStop() {
        val health = powerLightHealthAfterDetectorStop(
            hasLightSensor = true,
            powerStatusMonitoringActive = true,
            lastWitnessLux = 3.0,
        )

        assertEquals(SensorHealthState.HEALTHY, health.state)
        assertTrue(health.lightDetail?.isRegistered == true)
        assertEquals(3.0, health.lightDetail?.lastLux)
    }

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

    @Test
    fun successfulStartAndFreshMicrophoneSampleReportsHealthy() {
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = processor(),
            detectorCapture = { detectors = it },
        )

        assertTrue(runtime.startDetectors().started)
        detectors.emit(microphoneObservation())

        assertEquals(
            SensorHealthState.HEALTHY,
            runtime.currentSensorHealth()[SensorKind.MICROPHONE]?.state,
        )
    }

    @Test
    fun unavailableMicrophoneRemainsDegradedAfterDetectorStart() {
        val runtime = AndroidProtectionRuntime(
            readinessProvider = { ReadinessReport(emptySet(), emptySet()) },
            detectorFactory = { callback ->
                RecordingDetectorSet(
                    callback = callback,
                    initialHealth = mapOf(
                        SensorKind.MICROPHONE to SensorHealth(SensorHealthState.UNAVAILABLE),
                    ),
                )
            },
            observationProcessor = processor(),
            elapsedClock = ProtectionClock { 1_000L },
            stateProvider = { ProtectionState.ARMING },
            sensorSampleRecorder = { _, _, _, _ -> },
            incidentConsumer = { },
        )

        assertTrue(runtime.startDetectors().started)

        assertEquals(
            SensorHealthState.UNAVAILABLE,
            runtime.currentSensorHealth()[SensorKind.MICROPHONE]?.state,
        )
    }

    @Test
    fun batteryPercentageUpdatesStatusWithoutOpeningIncident() {
        val recorded = mutableListOf<String?>()
        val incidents = mutableListOf<IncidentObservationBatch>()
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = powerProcessor(),
            state = ProtectionState.ARMED_HEALTHY,
            sensorSampleRecorder = { _, _, diagnostic, _ -> recorded += diagnostic },
            incidentConsumer = incidents::add,
            detectorCapture = { detectors = it },
        )

        detectors.emit(powerObservation(74.0, "battery_level_percent"))

        assertEquals(listOf("battery_level_percent"), recorded)
        assertTrue(incidents.isEmpty())
    }

    @Test
    fun acceptedPrimaryObservationIncludesTypedIncidentLocationWhenAvailable() {
        val batches = mutableListOf<IncidentObservationBatch>()
        lateinit var detectors: RecordingDetectorSet
        val processor = processor()
        val runtime = runtime(
            processor = processor,
            state = ProtectionState.ARMED_HEALTHY,
            incidentConsumer = batches::add,
            detectorCapture = { detectors = it },
        )
        runtime.startDetectors()
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        detectors.incidentLocation = IncidentLocation(13.7563, 100.5018, 15f, 5_000L)

        detectors.emit(vibrationObservation(value = 12.0))

        assertEquals(1, batches.size)
        assertEquals(IncidentLocation(13.7563, 100.5018, 15f, 5_000L), batches.first().location)
    }

    @Test
    fun acceptedVibrationFreezesAudioAdaptation() {
        lateinit var detectors: RecordingDetectorSet
        val processor = processor()
        processor.seedBaseline(SensorKind.VIBRATION, SensorBaseline(9.8, 3))
        val runtime = runtime(
            processor = processor,
            elapsedNowMs = 2_000L,
            state = ProtectionState.ARMED_HEALTHY,
            detectorCapture = { detectors = it },
        )

        detectors.emit(vibrationObservation(value = 12.0, eventElapsedMs = 1234L))

        assertEquals(1234L, detectors.frozenAtElapsedMs)
    }

    @Test
    fun chargerDisconnectFreezesAudioAdaptation() {
        lateinit var detectors: RecordingDetectorSet
        val processor = powerProcessor()
        val runtime = runtime(
            processor = processor,
            state = ProtectionState.ARMED_HEALTHY,
            detectorCapture = { detectors = it },
        )

        detectors.emit(powerObservation(value = 1.0, diagnostic = "charger_disconnect"))

        assertEquals(900L, detectors.frozenAtElapsedMs)
    }

    @Test
    fun audioStartFailureDegradesOnlyMicrophoneAndKeepsOtherDetectorsRunning() {
        val health = mapOf(
            SensorKind.VIBRATION to SensorHealth(SensorHealthState.HEALTHY),
            SensorKind.MICROPHONE to SensorHealth(SensorHealthState.FAILED, detail = "mic init failed"),
        )
        val detectors = RecordingDetectorSet(callback = {}, initialHealth = health)
        val runtime = runtime(
            processor = processor(),
            state = ProtectionState.ARMED_DEGRADED,
            detectorCapture = { },
        )

        val reportedHealth = detectors.currentSensorHealth()
        assertEquals(SensorHealthState.HEALTHY, reportedHealth[SensorKind.VIBRATION]?.state)
        assertEquals(SensorHealthState.FAILED, reportedHealth[SensorKind.MICROPHONE]?.state)
    }

    @Test
    fun audioRecoveryRestoresMicrophoneHealth() {
        val detectors = RecordingDetectorSet(
            callback = {},
            initialHealth = mapOf(SensorKind.MICROPHONE to SensorHealth(SensorHealthState.FAILED)),
        )
        assertEquals(SensorHealthState.FAILED, detectors.currentSensorHealth()[SensorKind.MICROPHONE]?.state)

        detectors.emit(microphoneObservation())
        assertEquals(SensorHealthState.HEALTHY, detectors.currentSensorHealth()[SensorKind.MICROPHONE]?.state)
    }

    @Test
    fun stopClearsCandidatesAndCancelsRetry() {
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = processor(),
            detectorCapture = { detectors = it },
        )

        runtime.startDetectors("session-test")
        runtime.stopDetectors()

        assertEquals(1, detectors.stopCalls)
    }

    @Test
    fun newArmPassesOneNewNonblankSessionId() {
        lateinit var detectors: RecordingDetectorSet
        val runtime = runtime(
            processor = processor(),
            detectorCapture = { detectors = it },
        )

        runtime.startDetectors("session-abc-123")

        assertEquals("session-abc-123", detectors.startedSessionId)
    }

    @Test
    fun elapsedPowerConfirmationDeadlineIsNotRescheduledAtZeroDelay() {
        assertEquals(1L, powerConfirmationDelayMs(deadlineMs = 21_000L, nowMs = 20_999L))
        assertNull(powerConfirmationDelayMs(deadlineMs = 21_000L, nowMs = 21_000L))
        assertNull(powerConfirmationDelayMs(deadlineMs = 21_000L, nowMs = 21_001L))
    }

    @Test
    fun cachedPowerWitnessIsFreshOnlyInTheGenerationThatDeliveredIt() {
        assertTrue(
            powerWitnessIsFreshForGeneration(
                witnessLux = 50.0,
                witnessGeneration = 7L,
                currentGeneration = 7L,
            ),
        )
        assertFalse(
            powerWitnessIsFreshForGeneration(
                witnessLux = 50.0,
                witnessGeneration = 7L,
                currentGeneration = 8L,
            ),
        )
        assertFalse(
            powerWitnessIsFreshForGeneration(
                witnessLux = null,
                witnessGeneration = 8L,
                currentGeneration = 8L,
            ),
        )
    }

    @Test
    fun endingPowerLightListenerContinuityDropsCachedWitness() {
        val continuity = PowerWitnessContinuityCache()
        continuity.record(lux = 3.0, generation = 7L)
        assertTrue(
            powerWitnessIsFreshForGeneration(
                witnessLux = continuity.latest()?.lux,
                witnessGeneration = continuity.latest()?.generation,
                currentGeneration = 7L,
            ),
        )

        continuity.endListenerContinuity()

        assertNull(continuity.latest())
        assertFalse(
            powerWitnessIsFreshForGeneration(
                witnessLux = continuity.latest()?.lux,
                witnessGeneration = continuity.latest()?.generation,
                currentGeneration = 7L,
            ),
        )
    }
}

private fun assertUserMessageHidesInternalPowerTokens(message: String) {
    assertFalse(message.contains("POWER"))
    assertFalse(message.contains("TAMPER"))
    assertFalse(message.contains("charger_disconnected"))
    assertFalse(message.contains("power_"))
}

private fun testPowerWitnessModel() = PowerWitnessModel(
    darkMinLux = 0.0,
    darkMaxLux = 1.0,
    litMinLux = 10.0,
    litMaxLux = 12.0,
    guardBandLux = 9.0,
    algorithmVersion = 1,
    sensorIdentity = "test-sensor",
    hoodSignature = "test-hood",
)

private class RuntimeRecordingIncidentRepository : IncidentRepository {
    private val incidents = linkedMapOf<String, SecurityIncident>()

    override fun upsert(incident: SecurityIncident) {
        incidents[incident.id] = incident
    }

    override fun findById(id: String): SecurityIncident? = incidents[id]

    override fun listNewestFirst(): List<SecurityIncident> = incidents.values.toList().asReversed()

    override fun clearHistory() {
        incidents.clear()
    }
}

private fun processor(): SensorObservationProcessor = SensorObservationProcessor(
    staleAfterMs = 5_000L,
    debounceSamples = mapOf(SensorKind.VIBRATION to 1),
    thresholdDeltas = mapOf(SensorKind.VIBRATION to 1.0),
)

private fun powerProcessor(): SensorObservationProcessor = SensorObservationProcessor(
    staleAfterMs = 5_000L,
    debounceSamples = emptyMap(),
    thresholdDeltas = emptyMap(),
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
    initialHealth: Map<SensorKind, SensorHealth> = emptyMap(),
) : AndroidDetectorSet {
    var sensitivity: Int? = null
    var locationObservation: SensorObservation? = null
    var incidentLocation: IncidentLocation? = null
    var frozenAtElapsedMs: Long? = null
    var startedSessionId: String? = null
    var stopCalls: Int = 0
    private val health = initialHealth.toMutableMap()

    override fun start(): DetectorStartResult = DetectorStartResult(started = true)

    override fun start(armedSessionId: String): DetectorStartResult {
        startedSessionId = armedSessionId
        return start()
    }

    override fun stop() {
        stopCalls++
    }

    override fun freezeAudioAdaptation(nowElapsedMs: Long) {
        frozenAtElapsedMs = nowElapsedMs
    }

    override fun applySensitivity(level: Int) {
        sensitivity = level
    }

    override fun currentSensorHealth(): Map<SensorKind, SensorHealth> = health.toMap()

    override fun currentLocationObservation(): SensorObservation? = locationObservation

    override fun currentIncidentLocation(): IncidentLocation? = incidentLocation

    fun emit(observation: SensorObservation) {
        health[observation.kind] = SensorHealth(
            state = if (observation.valid) SensorHealthState.HEALTHY else SensorHealthState.FAILED,
            lastSampleAtMs = observation.wallClockMs,
            detail = observation.diagnostic,
        )
        callback(observation)
    }
}

private fun microphoneObservation(): SensorObservation = SensorObservation(
    kind = SensorKind.MICROPHONE,
    eventElapsedMs = 900L,
    wallClockMs = 5_000L,
    normalizedValue = 0.4,
    baselineDelta = 0.0,
    valid = true,
    diagnostic = "audio_peak_normalized",
)

private fun powerObservation(value: Double, diagnostic: String): SensorObservation = SensorObservation(
    kind = SensorKind.POWER_THERMAL,
    eventElapsedMs = 900L,
    wallClockMs = 5_000L,
    normalizedValue = value,
    baselineDelta = 0.0,
    valid = true,
    diagnostic = diagnostic,
)

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
