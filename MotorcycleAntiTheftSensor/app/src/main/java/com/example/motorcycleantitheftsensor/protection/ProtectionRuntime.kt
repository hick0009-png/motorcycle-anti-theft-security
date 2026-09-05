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
        usedSensorKinds: Set<SensorKind> = SensorKind.entries.toSet(),
        /**
         * Which signals this use lets open an incident, for the ones the configuration
         * cannot name. An empty table means the caller did not declare any, and the runtime
         * then keeps the pre-declaration behaviour rather than silently muting a signal.
         */
        signalRoles: Map<SensorKind, SensorRole> = emptyMap(),
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
    /**
     * The orientation sensor an armed door watch will really listen to on this device, or
     * null when it has none — or when this runtime cannot tell. Commissioning stamps the
     * answer into the hinge model, so a phone that falls back is recommissioned instead of
     * carrying a model measured against a sensor it no longer uses.
     */
    fun entryOrientationSource(): EntryOrientationSource? = null

    fun beginEntrySession(sessionId: String, model: EntryHingeModel, settings: EntryProfileSettings) {
    }

    /**
     * The door angle against the frozen armed baseline, right now.
     *
     * Read on demand and never carried on the snapshot: it changes with every orientation
     * sample, and a snapshot field that moved that fast would make every sample a semantic
     * change and write a durable record for each one, all night.
     */
    fun liveDoorAngleDeg(): Double? = null

    /** What the armed power arbiter currently makes of the witness lamp. */
    fun liveWitnessLit(): Boolean? = null

    /**
     * Milliseconds until a running loss or recovery confirmation would conclude, or null
     * when nothing is being confirmed.
     *
     * @param nowElapsedMs the same clock the arbiter's deadline was computed against.
     */
    fun liveConfirmationCountdownMs(nowElapsedMs: Long): Long? = null

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

    /**
     * Power Guard armed-session hook: freezes the commissioned witness model and starts
     * the composite charging/witness evaluation. Default no-op keeps non-POWER runtimes
     * and host fakes unaffected.
     */
    fun beginPowerSession(sessionId: String, model: PowerWitnessModel, settings: PowerProfileSettings) {
    }

    /** Clears the armed-session Power arbiter (owner disarm or controlled profile change). */
    fun clearPowerSession() {
    }

    /** Starts presentation-only Power Guard status monitoring while the profile is visible. */
    fun startPowerStatusMonitoring() {
    }

    /** Stops presentation-only Power Guard status monitoring when the profile is hidden. */
    fun stopPowerStatusMonitoring() {
    }

    /**
     * Commissioning-time witness stream: registers the ambient-light source without an
     * armed session so the guided lamp off/on flow can observe live samples.
     *
     * @return true when a live witness-light source was acquired. A runtime that cannot
     * observe light must say so here; the guided flow has no other way to tell the
     * difference between "waiting for the owner" and "waiting for nothing".
     */
    fun startPowerCommissioningStream(): Boolean = false

    /** Stops the commissioning witness stream unless an armed session needs it. */
    fun stopPowerCommissioningStream() {
    }

    /** Live witness-light samples for commissioning UI; empty by default. */
    fun powerWitnessSamples(): Flow<PowerWitnessSample> = emptyFlow()
}
