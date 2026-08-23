package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorConfigurationApplyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

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

    /**
     * Frozen-configuration entry point: the coordinator passes the immutable armed
     * snapshot configuration so late Settings edits cannot change running detectors.
     */
    fun startDetectors(
        armedSessionId: String,
        configuration: SensorFusionConfiguration,
    ): DetectorStartResult = startDetectors(armedSessionId)

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

    /**
     * Entry Guard armed-session hook: freezes the commissioned hinge model and starts
     * the relative-orientation baseline capture. Default no-op keeps non-Entry runtimes
     * and host fakes unaffected.
     */
    fun beginEntrySession(sessionId: String, model: EntryHingeModel, settings: EntryProfileSettings) {
    }

    /** Clears the armed-session Entry baseline (owner disarm or controlled profile change). */
    fun clearEntryBaseline() {
    }

    /**
     * Commissioning-time orientation stream: registers the rotation source without an
     * armed session so the two-cycle guided flow can observe live samples.
     */
    fun startEntryCommissioningStream() {
    }

    /** Stops the commissioning orientation stream unless an armed session needs it. */
    fun stopEntryCommissioningStream() {
    }

    /** Live relative-orientation samples for commissioning UI; empty by default. */
    fun entryOrientationSamples(): Flow<EntryOrientationSample> = emptyFlow()
}
