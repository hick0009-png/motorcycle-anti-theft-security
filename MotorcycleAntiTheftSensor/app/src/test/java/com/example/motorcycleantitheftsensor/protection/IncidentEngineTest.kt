package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class IncidentEngineTest {

    private lateinit var engine: IncidentEngine

    @Before
    fun setUp() {
        engine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "incident-test-1" },
            correlationWindowMs = 15_000L,
        )
    }

    private fun accepted(
        kind: SensorKind,
        elapsedMs: Long,
        normalizedValue: Double = 1.0,
        diagnostic: String? = null,
        audioThreat: AudioThreatMetadata? = null,
        // These proofs were written when a missing role counted as a host. Saying so
        // explicitly keeps every one of them meaning what it meant.
        role: SensorRole? = SensorRole.PRIMARY,
    ): SensorObservation = SensorObservation(
        kind = kind,
        role = role,
        eventElapsedMs = elapsedMs,
        wallClockMs = 1_700_000_000_000L + elapsedMs,
        normalizedValue = normalizedValue,
        baselineDelta = normalizedValue,
        valid = true,
        diagnostic = diagnostic,
        audioThreat = audioThreat,
    )

    private fun audioMetadata(
        category: AudioThreatCategory,
        confidence: Double = 0.85,
        elapsedMs: Long = 1000L,
        count: Int = 1,
    ): AudioThreatMetadata = AudioThreatMetadata(
        category = category,
        confidence = confidence,
        loudnessDeltaDb = 12.0,
        firstDetectedElapsedMs = elapsedMs,
        lastDetectedElapsedMs = elapsedMs,
        occurrenceCount = count,
        onsetElapsedMs = elapsedMs,
    )

    @Test
    fun audioAloneDoesNotOpenIncident() {
        val audioObs = accepted(
            kind = SensorKind.MICROPHONE,
            elapsedMs = 1000L,
            audioThreat = audioMetadata(AudioThreatCategory.IMPACT),
        )
        val update = engine.accept(audioObs, ProtectionState.ARMED_HEALTHY)
        assertEquals(IncidentUpdate.Ignored, update)
    }

    @Test
    fun audioAndLightOnlyWithoutVibrationDoesNotOpenIncident() {
        engine.accept(accepted(SensorKind.LIGHT, 1000L, 100.0), ProtectionState.ARMED_HEALTHY)
        val update = engine.accept(
            accepted(SensorKind.MICROPHONE, 2000L, audioThreat = audioMetadata(AudioThreatCategory.POWER_TOOL)),
            ProtectionState.ARMED_HEALTHY,
        )
        assertEquals(IncidentUpdate.Ignored, update)
    }

    @Test
    fun ordinaryLocationSampleAndAudioDoesNotOpenIncident() {
        val audioObs = accepted(
            kind = SensorKind.MICROPHONE,
            elapsedMs = 1000L,
            audioThreat = audioMetadata(AudioThreatCategory.POWER_TOOL),
        )
        engine.accept(audioObs, ProtectionState.ARMED_HEALTHY)

        val locObs = accepted(SensorKind.LOCATION, elapsedMs = 2000L)
        val update = engine.accept(locObs, ProtectionState.ARMED_HEALTHY)
        assertEquals(IncidentUpdate.Ignored, update)
    }

    @Test
    fun engineRunningWithoutOwnVibrationOrConfirmedMovementIsIgnored() {
        val audioObs = accepted(
            kind = SensorKind.MICROPHONE,
            elapsedMs = 1000L,
            audioThreat = audioMetadata(AudioThreatCategory.ENGINE_RUNNING),
        )
        val update = engine.accept(audioObs, ProtectionState.ARMED_HEALTHY)
        assertEquals(IncidentUpdate.Ignored, update)
    }

    @Test
    fun impactPlusVibrationWithin15SecondsIsWarning() {
        val audioObs = accepted(
            kind = SensorKind.MICROPHONE,
            elapsedMs = 1000L,
            audioThreat = audioMetadata(AudioThreatCategory.IMPACT),
        )
        engine.accept(audioObs, ProtectionState.ARMED_HEALTHY)

        val vibObs = accepted(SensorKind.VIBRATION, elapsedMs = 2000L, normalizedValue = 2.0)
        val update = engine.accept(vibObs, ProtectionState.ARMED_HEALTHY)

        assertTrue(update is IncidentUpdate.Opened)
        val incident = (update as IncidentUpdate.Opened).incident
        assertEquals(IncidentSeverity.WARNING, incident.severity)
        assertEquals(IncidentType.VIBRATION, incident.type)
        assertEquals(2, incident.evidence.size)
    }

    @Test
    fun repeatedImpactPlusContinuingVibrationIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT, count = 1)),
            ProtectionState.ARMED_HEALTHY,
        )
        val opened = engine.accept(
            accepted(SensorKind.VIBRATION, 1200L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        ) as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.WARNING, opened.incident.severity)

        val escalated = engine.accept(
            accepted(SensorKind.MICROPHONE, 3000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT, count = 2)),
            ProtectionState.ALERT_ACTIVE,
        )
        assertTrue(escalated is IncidentUpdate.Updated || escalated is IncidentUpdate.Escalated)
    }

    @Test
    fun breakingPlusVibrationIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.BREAKING)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 1200L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(IncidentSeverity.CRITICAL, (update as IncidentUpdate.Opened).incident.severity)
    }

    @Test
    fun powerToolPlusVibrationIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.POWER_TOOL)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(IncidentSeverity.CRITICAL, (update as IncidentUpdate.Opened).incident.severity)
    }

    @Test
    fun powerToolPlusLightPlusVibrationIsCriticalTamper() {
        engine.accept(accepted(SensorKind.LIGHT, 1000L, 100.0), ProtectionState.ARMED_HEALTHY)
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1500L, audioThreat = audioMetadata(AudioThreatCategory.POWER_TOOL)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        val opened = update as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, opened.incident.severity)
        assertEquals(IncidentType.TAMPER, opened.incident.type)
    }

    @Test
    fun metalTamperPlusVibrationIsWarning() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.METAL_TAMPER)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(IncidentSeverity.WARNING, (update as IncidentUpdate.Opened).incident.severity)
    }

    @Test
    fun engineStartPlusVibrationIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.ENGINE_START)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 1500L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(IncidentSeverity.CRITICAL, (update as IncidentUpdate.Opened).incident.severity)
    }

    @Test
    fun engineStartOrRunningPlusConfirmedMovementIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.ENGINE_RUNNING)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.onConfirmedMovement(
            accepted(SensorKind.LOCATION, 2000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        val opened = update as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, opened.incident.severity)
    }

    @Test
    fun anyCandidatePlusChargerDisconnectIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.METAL_TAMPER)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.POWER_THERMAL, 2000L, 1.0, diagnostic = "charger_disconnected"),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        val opened = update as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, opened.incident.severity)
        assertEquals(IncidentType.POWER, opened.incident.type)
    }

    @Test
    fun anyCandidatePlusConfirmedMovementIsCritical() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.onConfirmedMovement(
            accepted(SensorKind.LOCATION, 2000L),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        val opened = update as IncidentUpdate.Opened
        assertEquals(IncidentSeverity.CRITICAL, opened.incident.severity)
    }

    @Test
    fun exactly15000msIsConfirmed() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 16_000L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(2, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun rejectionAfter15001msWindow() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 16_001L, normalizedValue = 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun audioDuringUnrelatedActiveIncidentIsNotAppendedOrPersisted() {
        // Open a thermal incident (no vibration/charger/location)
        val thermalUpdate = engine.accept(
            accepted(SensorKind.POWER_THERMAL, 1000L, 50.0, diagnostic = "temperature_celsius"),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(thermalUpdate is IncidentUpdate.Opened)

        // Audio arrives without matching vibration/charger/movement
        val audioUpdate = engine.accept(
            accepted(SensorKind.MICROPHONE, 2000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ALERT_ACTIVE,
        )
        assertEquals(IncidentUpdate.Ignored, audioUpdate)
    }

    @Test
    fun activeIncidentIsEnrichedOnlyWhenRequiredCorroboratorMatches() {
        // Open vibration incident
        val vibUpdate = engine.accept(
            accepted(SensorKind.VIBRATION, 1000L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(vibUpdate is IncidentUpdate.Opened)

        // Audio arrives within 15s of vibration
        val audioUpdate = engine.accept(
            accepted(SensorKind.MICROPHONE, 2000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ALERT_ACTIVE,
        )
        assertTrue(audioUpdate is IncidentUpdate.Updated)
        val incident = (audioUpdate as IncidentUpdate.Updated).incident
        assertEquals(2, incident.evidence.size)
    }

    @Test
    fun closeWithoutActiveIncidentStillClearsAllPrecursors() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        val closed = engine.close(1500L, "test reset")
        assertNull(closed)

        // Next vibration should not match the cleared audio
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun interruptedWithoutActiveIncidentStillClearsAllPrecursors() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        val closed = engine.interrupted(1500L)
        assertNull(closed)

        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun audioPipelineResetClearsAudioPrecursors() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.ARMED_HEALTHY,
        )
        engine.clearPendingAudio()

        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 2000L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun precursorCollectionNeverExceedsEight() {
        for (i in 1..20) {
            engine.accept(
                accepted(SensorKind.MICROPHONE, 1000L + i, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
                ProtectionState.ARMED_HEALTHY,
            )
        }
        // Vibration correlates only with last audio
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 1500L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(2, (update as IncidentUpdate.Opened).incident.evidence.size)
    }

    @Test
    fun nonActiveProtectionStatesRejectAndClearAudio() {
        engine.accept(
            accepted(SensorKind.MICROPHONE, 1000L, audioThreat = audioMetadata(AudioThreatCategory.IMPACT)),
            ProtectionState.DISARMED_ONLINE,
        )
        val update = engine.accept(
            accepted(SensorKind.VIBRATION, 1500L, 2.0),
            ProtectionState.ARMED_HEALTHY,
        )
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
    }
}
