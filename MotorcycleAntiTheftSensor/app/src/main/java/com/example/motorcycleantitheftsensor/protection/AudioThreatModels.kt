package com.example.motorcycleantitheftsensor.protection

enum class AudioRuntimeState {
    OFF,
    STARTING,
    CALIBRATING,
    LISTENING,
    CLASSIFYING,
    DEGRADED,
    FAILED,
}

enum class AudioGateState {
    DISABLED,
    QUIET,
    OPEN,
}

enum class AudioThreatCategory {
    IMPACT,
    BREAKING,
    POWER_TOOL,
    METAL_TAMPER,
    ENGINE_START,
    ENGINE_RUNNING,
}

data class AudioThreatMetadata(
    val category: AudioThreatCategory,
    val confidence: Double,
    val loudnessDeltaDb: Double,
    val firstDetectedElapsedMs: Long,
    val lastDetectedElapsedMs: Long,
    val occurrenceCount: Int,
    val onsetElapsedMs: Long,
    val onsetCoherent: Boolean = false,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0.0..1.0, but was $confidence" }
        require(loudnessDeltaDb.isFinite()) { "loudnessDeltaDb must be finite, but was $loudnessDeltaDb" }
        require(occurrenceCount > 0) { "occurrenceCount must be positive, but was $occurrenceCount" }
        require(firstDetectedElapsedMs <= lastDetectedElapsedMs) {
            "firstDetectedElapsedMs ($firstDetectedElapsedMs) must be <= lastDetectedElapsedMs ($lastDetectedElapsedMs)"
        }
    }
}

data class AudioTelemetry(
    val state: AudioRuntimeState,
    val detailCode: String?,
    val modelReady: Boolean,
    val lastSampleAtMs: Long?,
    val approximateLevelDbfs: Double?,
    val baselineMedianDbfs: Double?,
    val baselineP95Dbfs: Double?,
    val gateState: AudioGateState = AudioGateState.DISABLED,
    val lastInferenceMs: Long?,
    val averageInferenceMs: Long?,
    val droppedFrames: Long,
    val restartCount: Int,
    val currentCandidate: AudioThreatMetadata?,
) {
    companion object {
        fun off(): AudioTelemetry = AudioTelemetry(
            state = AudioRuntimeState.OFF,
            detailCode = null,
            modelReady = false,
            lastSampleAtMs = null,
            approximateLevelDbfs = null,
            baselineMedianDbfs = null,
            baselineP95Dbfs = null,
            gateState = AudioGateState.DISABLED,
            lastInferenceMs = null,
            averageInferenceMs = null,
            droppedFrames = 0L,
            restartCount = 0,
            currentCandidate = null,
        )
    }
}

const val AUDIO_CORRELATION_WINDOW_MS = 15_000L
const val AUDIO_MAX_CANDIDATES = 8
const val AUDIO_SAMPLE_RATE_HZ = 16_000
const val YAMNET_INPUT_SAMPLES = 15_600
