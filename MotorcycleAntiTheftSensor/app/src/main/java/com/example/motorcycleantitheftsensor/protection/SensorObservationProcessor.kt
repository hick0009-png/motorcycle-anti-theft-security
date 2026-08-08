package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

class SensorObservationProcessor(
    private val staleAfterMs: Long,
    private val debounceSamples: Map<SensorKind, Int>,
    thresholdDeltas: Map<SensorKind, Double>,
    private val absoluteMinimumsByDiagnostic: Map<String, Double> = emptyMap(),
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
        rejectionReason(observation, nowElapsedMs)?.let { reason ->
            return ObservationDecision.Rejected(reason)
        }
        if (arming) {
            updateBaseline(observation)
            consecutiveSamples.remove(observation.kind)
            return ObservationDecision.BaselineUpdated
        }

        val absoluteMinimum = observation.diagnostic?.let(absoluteMinimumsByDiagnostic::get)
        if (absoluteMinimum != null) {
            if (observation.normalizedValue < absoluteMinimum) {
                consecutiveSamples.remove(observation.kind)
                return ObservationDecision.Debounced
            }
            val baselineDelta = baselines[observation.kind]
                ?.let { baseline -> observation.normalizedValue - baseline.average }
                ?: observation.baselineDelta
            return ObservationDecision.Accepted(observation.copy(baselineDelta = baselineDelta))
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
    fun resetSession() {
        baselines.clear()
        consecutiveSamples.clear()
    }

    fun isUsable(
        observation: SensorObservation,
        nowElapsedMs: Long,
    ): Boolean = rejectionReason(observation, nowElapsedMs) == null

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

    private fun rejectionReason(
        observation: SensorObservation,
        nowElapsedMs: Long,
    ): String? = when {
        !observation.valid -> "invalid sample"
        observation.eventElapsedMs > nowElapsedMs -> "future sample"
        nowElapsedMs - observation.eventElapsedMs > staleAfterMs -> "stale sample"
        else -> null
    }
}
