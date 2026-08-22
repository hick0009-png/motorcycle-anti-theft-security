package com.example.motorcycleantitheftsensor.sensor.audio

import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioThreatLabelMapperTest {

    private lateinit var mapper: AudioThreatLabelMapper

    @Before
    fun setUp() {
        mapper = AudioThreatLabelMapper()
    }

    @Test
    fun `maps impact labels correctly`() {
        val impactLabels = listOf(
            "Knock", "Tap", "Slam", "Thump, thud", "Thunk",
            "Bang", "Slap, smack", "Whack, thwack", "Hammer"
        )
        for (label in impactLabels) {
            val scores = listOf(AudioLabelScore(label, 0.82))
            val result = mapper.map(scores)
            assertEquals("Should map $label to IMPACT", 0.82, result[AudioThreatCategory.IMPACT] ?: 0.0, 0.001)
        }
    }

    @Test
    fun `maps breaking labels correctly`() {
        val breakingLabels = listOf("Crack", "Splinter", "Shatter", "Smash, crash", "Breaking")
        for (label in breakingLabels) {
            val scores = listOf(AudioLabelScore(label, 0.75))
            val result = mapper.map(scores)
            assertEquals("Should map $label to BREAKING", 0.75, result[AudioThreatCategory.BREAKING] ?: 0.0, 0.001)
        }
    }

    @Test
    fun `maps power tool labels correctly`() {
        val toolLabels = listOf(
            "Tools", "Power tool", "Drill", "Sawing",
            "Filing (rasp)", "Sanding", "Jackhammer", "Chainsaw"
        )
        for (label in toolLabels) {
            val scores = listOf(AudioLabelScore(label, 0.90))
            val result = mapper.map(scores)
            assertEquals("Should map $label to POWER_TOOL", 0.90, result[AudioThreatCategory.POWER_TOOL] ?: 0.0, 0.001)
        }
    }

    @Test
    fun `maps metal tamper labels correctly`() {
        val tamperLabels = listOf("Ratchet, pawl", "Gears", "Keys jangling", "Clang", "Scrape")
        for (label in tamperLabels) {
            val scores = listOf(AudioLabelScore(label, 0.71))
            val result = mapper.map(scores)
            assertEquals("Should map $label to METAL_TAMPER", 0.71, result[AudioThreatCategory.METAL_TAMPER] ?: 0.0, 0.001)
        }
    }

    @Test
    fun `maps engine start and running labels correctly`() {
        val startResult = mapper.map(listOf(AudioLabelScore("Engine starting", 0.88)))
        assertEquals(0.88, startResult[AudioThreatCategory.ENGINE_START] ?: 0.0, 0.001)

        val runningLabels = listOf(
            "Motorcycle", "Engine", "Light engine (high frequency)",
            "Medium engine (mid frequency)", "Heavy engine (low frequency)",
            "Engine knocking", "Idling", "Accelerating, revving, vroom"
        )
        for (label in runningLabels) {
            val scores = listOf(AudioLabelScore(label, 0.79))
            val result = mapper.map(scores)
            assertEquals("Should map $label to ENGINE_RUNNING", 0.79, result[AudioThreatCategory.ENGINE_RUNNING] ?: 0.0, 0.001)
        }
    }

    @Test
    fun `aggregates multiple labels in same category with max not sum`() {
        val scores = listOf(
            AudioLabelScore("Knock", 0.60),
            AudioLabelScore("Hammer", 0.85),
            AudioLabelScore("Tap", 0.40),
        )
        val result = mapper.map(scores)
        assertEquals(0.85, result[AudioThreatCategory.IMPACT] ?: 0.0, 0.001)
    }

    @Test
    fun `ignores benign and non-threat labels`() {
        val benignScores = listOf(
            AudioLabelScore("Speech", 0.99),
            AudioLabelScore("Traffic noise, roadway noise", 0.95),
            AudioLabelScore("Car passing by", 0.88),
            AudioLabelScore("Wind", 0.77),
            AudioLabelScore("Rain", 0.66),
            AudioLabelScore("Music", 0.90),
            AudioLabelScore("Noise", 0.99),
            AudioLabelScore("Silence", 0.99),
            AudioLabelScore("Dog", 0.85),
        )
        val result = mapper.map(benignScores)
        assertTrue("Benign labels must not produce any threat category", result.isEmpty())
    }

    @Test
    fun `is strictly case-sensitive`() {
        val result = mapper.map(listOf(AudioLabelScore("knock", 0.90), AudioLabelScore("HAMMER", 0.90)))
        assertTrue("Lowercase/uppercase variants must be ignored", result.isEmpty())
    }

    @Test
    fun `filters non-finite scores and clamps out-of-range values`() {
        val invalidScores = listOf(
            AudioLabelScore("Knock", Double.NaN),
            AudioLabelScore("Hammer", Double.POSITIVE_INFINITY),
            AudioLabelScore("Sawing", -0.5),
            AudioLabelScore("Drill", 1.5),
        )
        val result = mapper.map(invalidScores)

        // NaN and Infinity must be rejected
        assertNull(result[AudioThreatCategory.IMPACT])

        // Negative score clamped to 0.0, oversized score clamped to 1.0
        assertEquals(1.0, result[AudioThreatCategory.POWER_TOOL] ?: 0.0, 0.001)
    }
}
