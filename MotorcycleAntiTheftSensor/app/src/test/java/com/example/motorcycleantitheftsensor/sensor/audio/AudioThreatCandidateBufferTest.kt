package com.example.motorcycleantitheftsensor.sensor.audio

import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import com.example.motorcycleantitheftsensor.protection.AudioThreatMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AudioThreatCandidateBufferTest {

    private lateinit var buffer: AudioThreatCandidateBuffer
    private val sessionId = "session-123"

    @Before
    fun setUp() {
        buffer = AudioThreatCandidateBuffer()
        buffer.beginSession(sessionId)
    }

    @Test
    fun `impact qualifies on one shot with score greater than or equal to 0_70`() {
        val detection = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.72,
            loudnessDeltaDb = 15.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 990L,
            onsetCoherent = true,
        )
        val qualified = buffer.record(sessionId, detection)
        assertNotNull(qualified)
        assertEquals(AudioThreatCategory.IMPACT, qualified!!.category)
        assertEquals(0.72, qualified.confidence, 0.001)

        val list = buffer.candidates(sessionId, nowElapsedMs = 1000L)
        assertEquals(1, list.size)
        assertEquals(AudioThreatCategory.IMPACT, list[0].category)
    }

    @Test
    fun `impact is rejected if score below 0_70`() {
        val detection = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.69,
            loudnessDeltaDb = 10.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        val qualified = buffer.record(sessionId, detection)
        assertNull(qualified)
        assertTrue(buffer.candidates(sessionId, 1000L).isEmpty())
    }

    @Test
    fun `power tool requires two qualifying results within 2000ms with average score greater than or equal to 0_65`() {
        val window1 = AudioThreatMetadata(
            category = AudioThreatCategory.POWER_TOOL,
            confidence = 0.64,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        val result1 = buffer.record(sessionId, window1)
        assertNull("First window alone should not qualify power tool", result1)

        val window2 = AudioThreatMetadata(
            category = AudioThreatCategory.POWER_TOOL,
            confidence = 0.70,
            loudnessDeltaDb = 14.0,
            firstDetectedElapsedMs = 2500L,
            lastDetectedElapsedMs = 2500L,
            occurrenceCount = 1,
            onsetElapsedMs = 2500L,
        )
        val result2 = buffer.record(sessionId, window2)
        assertNotNull("Second window within 1500ms (avg 0.67) should qualify", result2)
        assertEquals(AudioThreatCategory.POWER_TOOL, result2!!.category)
        assertEquals(0.70, result2.confidence, 0.001)
        assertEquals(2, result2.occurrenceCount)
    }

    @Test
    fun `sustained category rejects second window if spaced more than 2000ms apart`() {
        val window1 = AudioThreatMetadata(
            category = AudioThreatCategory.METAL_TAMPER,
            confidence = 0.70,
            loudnessDeltaDb = 10.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        buffer.record(sessionId, window1)

        val window2 = AudioThreatMetadata(
            category = AudioThreatCategory.METAL_TAMPER,
            confidence = 0.70,
            loudnessDeltaDb = 10.0,
            firstDetectedElapsedMs = 3500L, // 2500ms later (>2000ms)
            lastDetectedElapsedMs = 3500L,
            occurrenceCount = 1,
            onsetElapsedMs = 3500L,
        )
        val result2 = buffer.record(sessionId, window2)
        assertNull("Second window after 2500ms should restart accumulation, not qualify yet", result2)
    }

    @Test
    fun `coalescing updates confidence, average delta dB, occurrence count, and expiry`() {
        val impact1 = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.75,
            loudnessDeltaDb = 10.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 990L,
        )
        buffer.record(sessionId, impact1)

        val impact2 = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.85,
            loudnessDeltaDb = 20.0,
            firstDetectedElapsedMs = 3000L,
            lastDetectedElapsedMs = 3000L,
            occurrenceCount = 1,
            onsetElapsedMs = 2990L,
        )
        val updated = buffer.record(sessionId, impact2)
        assertNotNull(updated)
        assertEquals(0.85, updated!!.confidence, 0.001) // Max confidence
        assertEquals(15.0, updated.loudnessDeltaDb, 0.001) // Average loudness
        assertEquals(1000L, updated.firstDetectedElapsedMs)
        assertEquals(3000L, updated.lastDetectedElapsedMs)
        assertEquals(2, updated.occurrenceCount)
    }

    @Test
    fun `candidates expire after 15_000 ms from last detected time`() {
        val impact = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.80,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        buffer.record(sessionId, impact)

        // At 16_000ms (15_000ms after last detection): still valid
        assertEquals(1, buffer.candidates(sessionId, nowElapsedMs = 16_000L).size)

        // At 16_001ms: expired
        assertEquals(0, buffer.candidates(sessionId, nowElapsedMs = 16_001L).size)
    }

    @Test
    fun `consume removes candidate on confirmation`() {
        val impact = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.80,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        buffer.record(sessionId, impact)

        val consumed = buffer.consume(AudioThreatCategory.IMPACT, sessionId)
        assertNotNull(consumed)
        assertEquals(AudioThreatCategory.IMPACT, consumed!!.category)
        assertTrue(buffer.candidates(sessionId, 1000L).isEmpty())
    }

    @Test
    fun `buffer caps candidates to at most 8 entries`() {
        for (i in 0 until 12) {
            val cat = AudioThreatCategory.values()[i % AudioThreatCategory.values().size]
            val meta = AudioThreatMetadata(
                category = cat,
                confidence = 0.70 + (i * 0.01),
                loudnessDeltaDb = 10.0,
                firstDetectedElapsedMs = 1000L + i * 100,
                lastDetectedElapsedMs = 1000L + i * 100,
                occurrenceCount = 1,
                onsetElapsedMs = 1000L + i * 100,
            )
            buffer.record(sessionId, meta)
        }
        assertTrue("Buffer must never exceed 8 candidates", buffer.candidates(sessionId, 5000L).size <= 8)
    }

    @Test
    fun `rejection on wrong sessionId and clear on new session or disarm`() {
        val impact = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.80,
            loudnessDeltaDb = 12.0,
            firstDetectedElapsedMs = 1000L,
            lastDetectedElapsedMs = 1000L,
            occurrenceCount = 1,
            onsetElapsedMs = 1000L,
        )
        buffer.record(sessionId, impact)

        // Wrong session
        assertNull(buffer.record("wrong-session", impact))
        assertTrue(buffer.candidates("wrong-session", 1000L).isEmpty())
        assertNull(buffer.consume(AudioThreatCategory.IMPACT, "wrong-session"))

        // Begin new session clears previous
        buffer.beginSession("session-456")
        assertTrue(buffer.candidates("session-456", 1000L).isEmpty())

        // Clear empties all
        buffer.clear()
        assertTrue(buffer.candidates("session-456", 1000L).isEmpty())
    }

    @Test
    fun `source contract test verifies candidate code has zero storage or network imports`() {
        val file = File("src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatCandidateBuffer.kt")
        assertTrue("Candidate buffer source file must exist", file.exists())
        val content = file.readText()

        val forbiddenTokens = listOf(
            "java.io",
            "android.content.SharedPreferences",
            "EncryptedSharedPreferences",
            "FileIncidentRepository",
            "Telegram",
            "android.util.Log",
            "println",
        )
        for (token in forbiddenTokens) {
            assertTrue("AudioThreatCandidateBuffer must not import or use $token", !content.contains(token))
        }
    }
}
