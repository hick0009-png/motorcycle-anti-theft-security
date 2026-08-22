package com.example.motorcycleantitheftsensor.sensor.audio

import com.example.motorcycleantitheftsensor.protection.AUDIO_CORRELATION_WINDOW_MS
import com.example.motorcycleantitheftsensor.protection.AUDIO_MAX_CANDIDATES
import com.example.motorcycleantitheftsensor.protection.AudioThreatCategory
import com.example.motorcycleantitheftsensor.protection.AudioThreatMetadata
import kotlin.math.max
import kotlin.math.min

class AudioThreatCandidateBuffer(
    private val maxCandidates: Int = AUDIO_MAX_CANDIDATES,
    private val expiryMs: Long = AUDIO_CORRELATION_WINDOW_MS,
) {
    private var activeSessionId: String? = null
    private val candidateStore = mutableMapOf<AudioThreatCategory, AudioThreatMetadata>()
    private val pendingDetections = mutableMapOf<AudioThreatCategory, AudioThreatMetadata>()

    @Synchronized
    fun beginSession(armedSessionId: String) {
        activeSessionId = armedSessionId
        candidateStore.clear()
        pendingDetections.clear()
    }

    @Synchronized
    fun record(sessionId: String, detection: AudioThreatMetadata): AudioThreatMetadata? {
        if (sessionId.isBlank() || sessionId != activeSessionId) {
            return null
        }

        val qualified = when (detection.category) {
            AudioThreatCategory.IMPACT,
            AudioThreatCategory.BREAKING -> {
                if (detection.confidence >= 0.70) {
                    detection
                } else {
                    null
                }
            }

            AudioThreatCategory.POWER_TOOL,
            AudioThreatCategory.METAL_TAMPER,
            AudioThreatCategory.ENGINE_START,
            AudioThreatCategory.ENGINE_RUNNING -> {
                val pending = pendingDetections[detection.category]
                val timeDelta = if (pending != null) detection.lastDetectedElapsedMs - pending.lastDetectedElapsedMs else -1L
                if (pending == null || timeDelta < 0L || timeDelta > 2_000L) {
                    pendingDetections[detection.category] = detection
                    null
                } else {
                    val avgConfidence = (pending.confidence + detection.confidence) / 2.0
                    if (avgConfidence >= 0.65) {
                        pendingDetections.remove(detection.category)
                        AudioThreatMetadata(
                            category = detection.category,
                            confidence = max(pending.confidence, detection.confidence),
                            loudnessDeltaDb = (pending.loudnessDeltaDb + detection.loudnessDeltaDb) / 2.0,
                            firstDetectedElapsedMs = min(pending.firstDetectedElapsedMs, detection.firstDetectedElapsedMs),
                            lastDetectedElapsedMs = max(pending.lastDetectedElapsedMs, detection.lastDetectedElapsedMs),
                            occurrenceCount = pending.occurrenceCount + detection.occurrenceCount,
                            onsetElapsedMs = min(pending.onsetElapsedMs, detection.onsetElapsedMs),
                            onsetCoherent = pending.onsetCoherent || detection.onsetCoherent,
                        )
                    } else {
                        pendingDetections[detection.category] = detection
                        null
                    }
                }
            }
        } ?: return null

        purgeExpired(qualified.lastDetectedElapsedMs)

        val existing = candidateStore[qualified.category]
        val merged = if (existing != null) {
            val totalCount = existing.occurrenceCount + qualified.occurrenceCount
            val avgDelta = (existing.loudnessDeltaDb * existing.occurrenceCount +
                qualified.loudnessDeltaDb * qualified.occurrenceCount) / totalCount
            AudioThreatMetadata(
                category = qualified.category,
                confidence = max(existing.confidence, qualified.confidence),
                loudnessDeltaDb = avgDelta,
                firstDetectedElapsedMs = min(existing.firstDetectedElapsedMs, qualified.firstDetectedElapsedMs),
                lastDetectedElapsedMs = max(existing.lastDetectedElapsedMs, qualified.lastDetectedElapsedMs),
                occurrenceCount = totalCount,
                onsetElapsedMs = min(existing.onsetElapsedMs, qualified.onsetElapsedMs),
                onsetCoherent = existing.onsetCoherent || qualified.onsetCoherent,
            )
        } else {
            if (candidateStore.size >= maxCandidates) {
                val oldestKey = candidateStore.minByOrNull { it.value.lastDetectedElapsedMs }?.key
                if (oldestKey != null) {
                    candidateStore.remove(oldestKey)
                }
            }
            qualified
        }

        candidateStore[merged.category] = merged
        return merged
    }

    @Synchronized
    fun candidates(sessionId: String, nowElapsedMs: Long): List<AudioThreatMetadata> {
        if (sessionId.isBlank() || sessionId != activeSessionId) {
            return emptyList()
        }
        purgeExpired(nowElapsedMs)
        return candidateStore.values.toList()
    }

    @Synchronized
    fun consume(category: AudioThreatCategory, sessionId: String): AudioThreatMetadata? {
        if (sessionId.isBlank() || sessionId != activeSessionId) {
            return null
        }
        return candidateStore.remove(category)
    }

    @Synchronized
    fun clear() {
        candidateStore.clear()
        pendingDetections.clear()
        activeSessionId = null
    }

    private fun purgeExpired(nowElapsedMs: Long) {
        val iterator = candidateStore.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowElapsedMs > entry.value.lastDetectedElapsedMs + expiryMs) {
                iterator.remove()
            }
        }
        val pendingIterator = pendingDetections.entries.iterator()
        while (pendingIterator.hasNext()) {
            val entry = pendingIterator.next()
            if (nowElapsedMs > entry.value.lastDetectedElapsedMs + 2_000L) {
                pendingIterator.remove()
            }
        }
    }
}
