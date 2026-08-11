package com.example.motorcycleantitheftsensor.protection

data class ReadinessReport(
    val blockers: Set<String>,
    val degradations: Set<String>,
)

data class DetectorStartResult(
    val started: Boolean,
    val failureReason: String? = null,
)

data class RemoteControlReadiness(
    val botTokenConfigured: Boolean,
    val ownerPaired: Boolean,
    val totpConfigured: Boolean,
) {
    fun blockers(): Set<String> = buildSet {
        if (!botTokenConfigured) add("TELEGRAM bot token not configured")
        if (!ownerPaired) add("TELEGRAM owner not paired")
        if (!totpConfigured) add("TOTP not configured")
    }
}

data class MicrophoneReadiness(
    val hardwareAvailable: Boolean,
    val permissionGranted: Boolean,
) {
    fun degradations(): Set<String> = when {
        !hardwareAvailable -> setOf("MICROPHONE unavailable")
        !permissionGranted -> setOf("RECORD_AUDIO permission unavailable")
        else -> emptySet()
    }
}

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
