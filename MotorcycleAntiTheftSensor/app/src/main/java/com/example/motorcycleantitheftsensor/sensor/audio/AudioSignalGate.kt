package com.example.motorcycleantitheftsensor.sensor.audio

import kotlin.math.log10
import kotlin.math.max

sealed interface AudioGateDecision {
    data class Suppress(val features: AudioFrameFeatures) : AudioGateDecision
    data class Classify(val features: AudioFrameFeatures, val onsetElapsedMs: Long) : AudioGateDecision
}

class AudioSignalGate {

    fun evaluate(features: AudioFrameFeatures, baseline: AudioBaseline?): AudioGateDecision {
        if (baseline == null) {
            return AudioGateDecision.Suppress(features)
        }

        val thresholdDbfs = max(baseline.p95Dbfs + 6.0, baseline.medianDbfs + 12.0)
        val peakDbfs = 20.0 * log10(max(features.peak, 1e-6))

        val qualifies = features.rmsDbfs >= thresholdDbfs || peakDbfs >= thresholdDbfs

        return if (qualifies) {
            AudioGateDecision.Classify(
                features = features,
                onsetElapsedMs = features.atElapsedMs,
            )
        } else {
            AudioGateDecision.Suppress(features)
        }
    }
}
