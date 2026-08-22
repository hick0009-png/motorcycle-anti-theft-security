package com.example.motorcycleantitheftsensor.protection

data class SensorObservation(
    val kind: SensorKind = SensorKind.VIBRATION,
    val source: SensorSource? = null,
    val capability: SensorCapability? = null,
    val role: SensorRole? = null,
    val generationId: Long = 0L,
    val unit: SensorUnit = SensorUnit.METERS_PER_SECOND_SQUARED,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val valid: Boolean,
    val diagnostic: String? = null,
    val diagnosticCode: SensorDiagnosticCode? = null,
    val audioThreat: AudioThreatMetadata? = null,
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
