package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
) {
    fun blockers(): Set<String> = buildSet {
        if (!botTokenConfigured) add("TELEGRAM bot token not configured")
        if (!ownerPaired) add("TELEGRAM owner not paired")
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
    val audioTelemetry: StateFlow<AudioTelemetry> get() = MutableStateFlow(AudioTelemetry.off())
    val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>> get() = MutableStateFlow(emptyMap())

    fun readiness(): ReadinessReport

    fun startDetectors(): DetectorStartResult

    fun startDetectors(armedSessionId: String): DetectorStartResult = startDetectors()

    fun stopDetectors()

    fun applySensitivity(level: Int)

    fun applySensorConfiguration(config: SensorFusionConfiguration): SensorConfigurationApplyResult =
        SensorConfigurationApplyResult(
            status = SensorConfigurationApplyResult.Status.APPLIED,
            affectedCapabilities = emptySet()
        )

    fun effectiveSensorConfiguration(): SensorFusionConfiguration =
        SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)

    fun currentGenerationId(): Long = 0L

    fun currentSensorHealth(): Map<SensorKind, SensorHealth>

    fun sourceHealth(source: SensorSource): SensorHealthState {
        val kind = when (source.capability) {
            SensorCapability.LIGHT -> SensorKind.LIGHT
            SensorCapability.PROXIMITY -> SensorKind.LIGHT
            else -> SensorKind.VIBRATION
        }
        return currentSensorHealth()[kind]?.state ?: SensorHealthState.UNAVAILABLE
    }
}
