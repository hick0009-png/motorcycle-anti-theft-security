package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

class SensorObservationProcessor(
    private val staleAfterMs: Long,
    private val debounceSamples: Map<SensorKind, Int>,
    thresholdDeltas: Map<SensorKind, Double>,
) {
    private val baselines = mutableMapOf<SensorKind, SensorBaseline>()
    private val consecutiveSamples = mutableMapOf<SensorKind, Int>()
    private val thresholds = thresholdDeltas.toMutableMap()

    @Synchronized
    fun accept(
        observation: SensorObservation,
        nowElapsedMs: Long,
        arming: Boolean,
    ): ObservationDecision {
        if (!observation.valid) {
            return ObservationDecision.Rejected("invalid sample")
        }
        if (nowElapsedMs - observation.eventElapsedMs > staleAfterMs) {
            return ObservationDecision.Rejected("stale sample")
        }
        if (arming) {
            updateBaseline(observation)
            consecutiveSamples.remove(observation.kind)
            return ObservationDecision.BaselineUpdated
        }

        val threshold = thresholds[observation.kind]
            ?: return ObservationDecision.Accepted(observation)
        val baseline = baselines[observation.kind]
            ?: return ObservationDecision.Rejected("baseline unavailable")
        val delta = observation.normalizedValue - baseline.average
        if (abs(delta) <= threshold) {
            consecutiveSamples.remove(observation.kind)
            return ObservationDecision.Debounced
        }

        val nextCount = (consecutiveSamples[observation.kind] ?: 0) + 1
        val requiredCount = debounceSamples[observation.kind]?.coerceAtLeast(1) ?: 1
        if (nextCount < requiredCount) {
            consecutiveSamples[observation.kind] = nextCount
            return ObservationDecision.Debounced
        }

        consecutiveSamples.remove(observation.kind)
        return ObservationDecision.Accepted(observation.copy(baselineDelta = delta))
    }

    @Synchronized
    fun baseline(kind: SensorKind): SensorBaseline? = baselines[kind]

    @Synchronized
    fun seedBaseline(
        kind: SensorKind,
        baseline: SensorBaseline,
    ) {
        baselines[kind] = baseline
        consecutiveSamples.remove(kind)
    }

    @Synchronized
    fun setVibrationSensitivity(level: Int): Boolean {
        if (level !in 1..10) return false
        thresholds[SensorKind.VIBRATION] = 3.5 - ((level - 1) * (3.0 / 9.0))
        consecutiveSamples.remove(SensorKind.VIBRATION)
        return true
    }

    @Synchronized
    fun thresholdDelta(kind: SensorKind): Double = thresholds.getValue(kind)

    private fun updateBaseline(observation: SensorObservation) {
        val current = baselines[observation.kind] ?: SensorBaseline(
            average = 0.0,
            sampleCount = 0,
        )
        val nextCount = current.sampleCount + 1
        val nextAverage = current.average + (observation.normalizedValue - current.average) / nextCount
        baselines[observation.kind] = SensorBaseline(
            average = nextAverage,
            sampleCount = nextCount,
        )
    }
}
