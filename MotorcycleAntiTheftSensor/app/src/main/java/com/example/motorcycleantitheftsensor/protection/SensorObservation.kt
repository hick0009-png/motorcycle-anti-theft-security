package com.example.motorcycleantitheftsensor.protection

data class SensorObservation(
    val kind: SensorKind,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val valid: Boolean,
    val diagnostic: String? = null,
)

data class SensorBaseline(
    val average: Double,
    val sampleCount: Int,
)

sealed interface ObservationDecision {
    data class Rejected(val reason: String) : ObservationDecision

    data object BaselineUpdated : ObservationDecision

    data object Debounced : ObservationDecision

    data class Accepted(val observation: SensorObservation) : ObservationDecision
}
