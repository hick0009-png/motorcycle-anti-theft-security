package com.example.motorcycleantitheftsensor.sensor.audio

import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import kotlin.math.max

data class AudioLabelScore(val label: String, val score: Double)

interface AudioThreatClassifier : AutoCloseable {
    fun classify(samples: FloatArray): List<AudioLabelScore>
    override fun close() {}
}

class AudioThreatLabelMapper {

    private val labelToCategory = mapOf(
        // IMPACT
        "Knock" to AudioThreatCategory.IMPACT,
        "Tap" to AudioThreatCategory.IMPACT,
        "Slam" to AudioThreatCategory.IMPACT,
        "Thump, thud" to AudioThreatCategory.IMPACT,
        "Thunk" to AudioThreatCategory.IMPACT,
        "Bang" to AudioThreatCategory.IMPACT,
        "Slap, smack" to AudioThreatCategory.IMPACT,
        "Whack, thwack" to AudioThreatCategory.IMPACT,
        "Hammer" to AudioThreatCategory.IMPACT,

        // BREAKING
        "Crack" to AudioThreatCategory.BREAKING,
        "Splinter" to AudioThreatCategory.BREAKING,
        "Shatter" to AudioThreatCategory.BREAKING,
        "Smash, crash" to AudioThreatCategory.BREAKING,
        "Breaking" to AudioThreatCategory.BREAKING,

        // POWER_TOOL
        "Tools" to AudioThreatCategory.POWER_TOOL,
        "Power tool" to AudioThreatCategory.POWER_TOOL,
        "Drill" to AudioThreatCategory.POWER_TOOL,
        "Sawing" to AudioThreatCategory.POWER_TOOL,
        "Filing (rasp)" to AudioThreatCategory.POWER_TOOL,
        "Sanding" to AudioThreatCategory.POWER_TOOL,
        "Jackhammer" to AudioThreatCategory.POWER_TOOL,
        "Chainsaw" to AudioThreatCategory.POWER_TOOL,

        // METAL_TAMPER
        "Ratchet, pawl" to AudioThreatCategory.METAL_TAMPER,
        "Gears" to AudioThreatCategory.METAL_TAMPER,
        "Keys jangling" to AudioThreatCategory.METAL_TAMPER,
        "Clang" to AudioThreatCategory.METAL_TAMPER,
        "Scrape" to AudioThreatCategory.METAL_TAMPER,

        // ENGINE_START
        "Engine starting" to AudioThreatCategory.ENGINE_START,

        // ENGINE_RUNNING
        "Motorcycle" to AudioThreatCategory.ENGINE_RUNNING,
        "Engine" to AudioThreatCategory.ENGINE_RUNNING,
        "Light engine (high frequency)" to AudioThreatCategory.ENGINE_RUNNING,
        "Medium engine (mid frequency)" to AudioThreatCategory.ENGINE_RUNNING,
        "Heavy engine (low frequency)" to AudioThreatCategory.ENGINE_RUNNING,
        "Engine knocking" to AudioThreatCategory.ENGINE_RUNNING,
        "Idling" to AudioThreatCategory.ENGINE_RUNNING,
        "Accelerating, revving, vroom" to AudioThreatCategory.ENGINE_RUNNING,
    )

    fun map(scores: List<AudioLabelScore>): Map<AudioThreatCategory, Double> {
        val result = mutableMapOf<AudioThreatCategory, Double>()
        for (item in scores) {
            if (!item.score.isFinite()) continue
            val category = labelToCategory[item.label] ?: continue
            val clampedScore = item.score.coerceIn(0.0, 1.0)
            val existing = result[category] ?: 0.0
            result[category] = max(existing, clampedScore)
        }
        return result
    }
}
