package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import com.example.motorcycleantitheftsensor.sensor.audio.AudioThreatCandidateBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioMovementFusionIntegrationTest {

    private lateinit var candidateBuffer: AudioThreatCandidateBuffer
    private lateinit var incidentEngine: IncidentEngine

    @Before
    fun setup() {
        candidateBuffer = AudioThreatCandidateBuffer()
        incidentEngine = IncidentEngine(
            idGenerator = IncidentIdGenerator { "fusion-incident-1" },
            correlationWindowMs = 15_000L,
        )
    }

    @Test
    fun unconfirmedCandidateDoesNotReachPursuitOrEngine() {
        val armedSessionId = "session-1"
        candidateBuffer.beginSession(armedSessionId)

        val metadata = AudioThreatMetadata(
            category = AudioThreatCategory.ENGINE_RUNNING,
            confidence = 0.9,
            loudnessDeltaDb = 15.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 2000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
            onsetCoherent = false,
        )
        // Record 2 detections for multi-frame category to qualify
        candidateBuffer.record(armedSessionId, metadata)
        val recorded = candidateBuffer.record(armedSessionId, metadata.copy(lastDetectedElapsedMs = 2100L))
        assertNotNull(recorded)

        // Engine accepts audio observation alone
        val obs = SensorObservation(
            kind = SensorKind.MICROPHONE,
            role = SensorRole.PRIMARY,
            eventElapsedMs = 2100L,
            wallClockMs = 102100L,
            normalizedValue = 0.9,
            baselineDelta = 15.0,
            valid = true,
            diagnostic = "candidate",
            audioThreat = recorded,
        )
        val update = incidentEngine.accept(obs, ProtectionState.ARMED_HEALTHY)
        assertEquals(IncidentUpdate.Ignored, update)

        // Candidate buffer still holds candidate
        assertEquals(1, candidateBuffer.candidates(armedSessionId, 2100L).size)
    }

    @Test
    fun confirmedMovementCorrelatesWithPendingAudioCandidate() = runTest {
        val armedSessionId = "session-1"
        candidateBuffer.beginSession(armedSessionId)

        val metadata = AudioThreatMetadata(
            category = AudioThreatCategory.ENGINE_RUNNING,
            confidence = 0.9,
            loudnessDeltaDb = 15.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 2000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
            onsetCoherent = false,
        )
        candidateBuffer.record(armedSessionId, metadata)
        val recorded = candidateBuffer.record(armedSessionId, metadata.copy(lastDetectedElapsedMs = 2100L))
        assertNotNull(recorded)

        // Mic observation accepted into engine precursor
        val micObs = SensorObservation(
            kind = SensorKind.MICROPHONE,
            role = SensorRole.PRIMARY,
            eventElapsedMs = 2100L,
            wallClockMs = 102100L,
            normalizedValue = 0.9,
            baselineDelta = 15.0,
            valid = true,
            diagnostic = "candidate",
            audioThreat = recorded,
        )
        incidentEngine.accept(micObs, ProtectionState.ARMED_HEALTHY)

        // Confirmed movement fix arrives within 15s
        val fix = TrackedLocationFix(
            latitude = 13.7563,
            longitude = 100.5018,
            elapsedRealtimeMs = 5000L,
            wallClockMs = 105000L,
            accuracyMeters = 5.0f,
        )
        val locObs = SensorObservation(
            kind = SensorKind.LOCATION,
            role = SensorRole.PRIMARY,
            eventElapsedMs = fix.elapsedRealtimeMs,
            wallClockMs = fix.wallClockMs,
            normalizedValue = 1.0,
            baselineDelta = 0.0,
            valid = true,
            diagnostic = "confirmed_movement",
        )
        val update = incidentEngine.onConfirmedMovement(
            observation = locObs,
            protectionState = ProtectionState.ARMED_HEALTHY,
            location = IncidentLocation(fix.latitude, fix.longitude, fix.accuracyMeters, fix.wallClockMs),
        )

        assertTrue(update is IncidentUpdate.Opened)
        val incident = (update as IncidentUpdate.Opened).incident
        assertEquals(IncidentSeverity.CRITICAL, incident.severity)
        assertEquals(IncidentType.AUDIO, incident.type)
        assertEquals(2, incident.evidence.size)

        // Candidate consumed
        candidateBuffer.consume(metadata.category, armedSessionId)
        assertTrue(candidateBuffer.candidates(armedSessionId, 5000L).isEmpty())
    }

    @Test
    fun staleOrMismatchedSessionCandidateBufferIgnores() {
        val session1 = "session-1"
        val session2 = "session-2"
        candidateBuffer.beginSession(session1)

        val metadata = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.85,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 2000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
            onsetCoherent = false,
        )
        candidateBuffer.record(session1, metadata)

        // Try consuming with wrong session ID
        val wrongConsumed = candidateBuffer.consume(metadata.category, session2)
        assertNull(wrongConsumed)
        assertEquals(1, candidateBuffer.candidates(session1, 2000L).size)

        // Consume with correct session ID
        val correctConsumed = candidateBuffer.consume(metadata.category, session1)
        assertNotNull(correctConsumed)
        assertTrue(candidateBuffer.candidates(session1, 2000L).isEmpty())
    }

    @Test
    fun audioPipelineResetClearsEngineAudioPrecursors() {
        val obs = SensorObservation(
            kind = SensorKind.MICROPHONE,
            role = SensorRole.PRIMARY,
            eventElapsedMs = 2000L,
            wallClockMs = 102000L,
            normalizedValue = 0.9,
            baselineDelta = 15.0,
            valid = true,
            diagnostic = "candidate",
            audioThreat = AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = 0.8,
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 2000L,
                lastDetectedElapsedMs = 2000L,
                occurrenceCount = 1,
                onsetElapsedMs = 2000L,
                onsetCoherent = false,
            ),
        )
        incidentEngine.accept(obs, ProtectionState.ARMED_HEALTHY)

        // Pipeline reset clears engine audio precursors
        incidentEngine.clearPendingAudio()

        // Subsequent vibration does not match the cleared audio
        val vibObs = SensorObservation(
            kind = SensorKind.VIBRATION,
            role = SensorRole.PRIMARY,
            eventElapsedMs = 3000L,
            wallClockMs = 103000L,
            normalizedValue = 2.0,
            baselineDelta = 1.0,
            valid = true,
            diagnostic = "vib",
        )
        val update = incidentEngine.accept(vibObs, ProtectionState.ARMED_HEALTHY)
        assertTrue(update is IncidentUpdate.Opened)
        assertEquals(1, (update as IncidentUpdate.Opened).incident.evidence.size)
        assertEquals(IncidentType.VIBRATION, (update as IncidentUpdate.Opened).incident.type)
    }
}
