package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioThreatModelsTest {

    @Test
    fun `valid metadata creation succeeds`() {
        val metadata = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.85,
            loudnessDeltaDb = 14.5,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1200L,
            occurrenceCount = 2,
            onsetElapsedMs = 990L,
            onsetCoherent = true,
        )
        assertEquals(AudioThreatCategory.IMPACT, metadata.category)
        assertEquals(0.85, metadata.confidence, 0.001)
        assertEquals(14.5, metadata.loudnessDeltaDb, 0.001)
        assertEquals(1000L, metadata.firstDetectedElapsedMs)
        assertEquals(1200L, metadata.lastDetectedElapsedMs)
        assertEquals(2, metadata.occurrenceCount)
        assertEquals(990L, metadata.onsetElapsedMs)
        assertTrue(metadata.onsetCoherent)
    }

    @Test
    fun `metadata validates confidence in 0 to 1 range`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = -0.01,
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 1000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.IMPACT,
                confidence = 1.01,
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 1000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L,
            )
        }
    }

    @Test
    fun `metadata requires finite loudnessDeltaDb`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.BREAKING,
                confidence = 0.8,
                loudnessDeltaDb = Double.NaN,
                firstDetectedElapsedMs = 1000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.BREAKING,
                confidence = 0.8,
                loudnessDeltaDb = Double.POSITIVE_INFINITY,
                firstDetectedElapsedMs = 1000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L,
            )
        }
    }

    @Test
    fun `metadata requires positive occurrenceCount`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.POWER_TOOL,
                confidence = 0.8,
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 1000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 0,
                onsetElapsedMs = 1000L,
            )
        }
    }

    @Test
    fun `metadata requires first detected time to be less than or equal to last detected time`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioThreatMetadata(
                category = AudioThreatCategory.METAL_TAMPER,
                confidence = 0.8,
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 2000L,
                lastDetectedElapsedMs = 1000L,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L,
            )
        }
    }

    @Test
    fun `audio telemetry off returns clean zero and off state`() {
        val off = AudioTelemetry.off()
        assertEquals(AudioRuntimeState.OFF, off.state)
        assertNull(off.detailCode)
        assertFalse(off.modelReady)
        assertNull(off.lastSampleAtMs)
        assertNull(off.approximateLevelDbfs)
        assertNull(off.baselineMedianDbfs)
        assertNull(off.baselineP95Dbfs)
        assertNull(off.lastInferenceMs)
        assertNull(off.averageInferenceMs)
        assertEquals(0L, off.droppedFrames)
        assertEquals(0, off.restartCount)
        assertNull(off.currentCandidate)
    }

    @Test
    fun `constants have expected values`() {
        assertEquals(15_000L, AUDIO_CORRELATION_WINDOW_MS)
        assertEquals(8, AUDIO_MAX_CANDIDATES)
        assertEquals(16_000, AUDIO_SAMPLE_RATE_HZ)
        assertEquals(15_600, YAMNET_INPUT_SAMPLES)
    }

    @Test
    fun `sensor observation and incident evidence default audioThreat to null`() {
        val observation = SensorObservation(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = 100L,
            wallClockMs = 200L,
            normalizedValue = 1.0,
            baselineDelta = 0.5,
            valid = true,
        )
        assertNull(observation.audioThreat)

        val evidence = IncidentEvidence(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = 100L,
            wallClockMs = 200L,
            normalizedValue = 1.0,
            baselineDelta = 0.5,
            diagnostic = null,
        )
        assertNull(evidence.audioThreat)
    }
}
