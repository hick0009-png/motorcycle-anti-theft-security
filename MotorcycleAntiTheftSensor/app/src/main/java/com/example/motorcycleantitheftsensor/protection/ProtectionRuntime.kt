package com.example.motorcycleantitheftsensor.protection

data class ReadinessReport(
    val blockers: Set<String>,
    val degradations: Set<String>,
)

data class DetectorStartResult(
    val started: Boolean,
    val failureReason: String? = null,
)

fun interface ArmingDelay {
    suspend fun await()
}

fun interface ProtectionClock {
    fun nowMs(): Long
}

interface ProtectionRuntime {
    fun readiness(): ReadinessReport

    fun startDetectors(): DetectorStartResult

    fun stopDetectors()

    fun applySensitivity(level: Int)

    fun currentSensorHealth(): Map<SensorKind, SensorHealth>
}
