package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

class SensorObservationProcessor(
    private val staleAfterMs: Long,
    private val debounceSamples: Map<SensorKind, Int>,
    thresholdDeltas: Map<SensorKind, Double>,
    private val absoluteMinimumsByDiagnostic: Map<String, Double> = emptyMap(),
) {
    private data class BaselineKey(
        val kind: SensorKind,
        val source: SensorSource?,
    )

    private val baselines = mutableMapOf<BaselineKey, SensorBaseline>()
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
        if (observation.kind == SensorKind.MICROPHONE) {
            val threat = observation.audioThreat
            return if (threat != null) {
                ObservationDecision.Accepted(observation)
            } else {
                ObservationDecision.Rejected("untyped microphone observation")
            }
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
            val baselineDelta = baselines[baselineKey(observation)]
                ?.let { baseline -> observation.normalizedValue - baseline.average }
                ?: observation.baselineDelta
            return ObservationDecision.Accepted(observation.copy(baselineDelta = baselineDelta))
        }

        val threshold = thresholds[observation.kind]
            ?: return ObservationDecision.Accepted(observation)
        val baseline = baselines[baselineKey(observation)]
        val delta = if (observation.source != null) {
            // Continuous sources are emitted only after SensorCalibrationManager has
            // established their own baseline. Keep that source-specific delta instead
            // of comparing values with a different VIBRATION source or unit.
            observation.baselineDelta
        } else if (baseline != null) {
            observation.normalizedValue - baseline.average
        } else {
            observation.baselineDelta
        }
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
    fun baseline(kind: SensorKind): SensorBaseline? = baselines[BaselineKey(kind, null)]

    @Synchronized
    fun baseline(kind: SensorKind, source: SensorSource): SensorBaseline? =
        baselines[BaselineKey(kind, source)]

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
        baselines[BaselineKey(kind, null)] = baseline
        consecutiveSamples.remove(kind)
    }

    @Synchronized
    fun seedBaseline(
        kind: SensorKind,
        source: SensorSource,
        baseline: SensorBaseline,
    ) {
        baselines[BaselineKey(kind, source)] = baseline
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
    fun thresholdDelta(kind: SensorKind): Double = thresholds[kind] ?: 0.0

    private fun updateBaseline(observation: SensorObservation) {
        val key = baselineKey(observation)
        val current = baselines[key] ?: SensorBaseline(
            average = 0.0,
            sampleCount = 0,
        )
        val nextCount = current.sampleCount + 1
        val nextAverage = current.average + (observation.normalizedValue - current.average) / nextCount
        baselines[key] = SensorBaseline(
            average = nextAverage,
            sampleCount = nextCount,
        )
    }

    private fun baselineKey(observation: SensorObservation): BaselineKey =
        BaselineKey(observation.kind, observation.source)

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
