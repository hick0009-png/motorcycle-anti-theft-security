package com.example.motorcycleantitheftsensor.protection

enum class ProtectionProfile {
    VEHICLE,
    ENTRY,
    POWER,
}

/**
 * Durable phases of a confirmed profile change while armed. They always advance in
 * declaration order and every persisted phase converges to disarmed/selected-target
 * after a crash or reboot; the old profile is never rearmed.
 */
enum class ProfileSwitchPhase {
    STOP_REQUESTED,
    OLD_RUNTIME_QUIESCED,
    SNAPSHOT_CLEARED,
    NEW_PROFILE_SELECTED,
}

data class ProfileSwitchTransaction(
    val transactionId: String,
    val oldArmedSessionId: String?,
    val targetProfile: ProtectionProfile,
    val phase: ProfileSwitchPhase,
)

enum class ProfileSetupState {
    READY,
    SETUP_REQUIRED,
    UNAVAILABLE,
}

sealed interface ProfileSpecificSettings

data object VehicleProfileSettings : ProfileSpecificSettings

data class EntryProfileSettings(
    val angleThresholdDegrees: Int = 15,
    val openConfirmationMs: Long = 750L,
    val closeThresholdDegrees: Int = 3,
    val closeConfirmationMs: Long = 5_000L,
) : ProfileSpecificSettings

data class PowerProfileSettings(
    val lossConfirmationMs: Long = 10_000L,
    val recoveryConfirmationMs: Long = 10_000L,
) : ProfileSpecificSettings

data class SensorCapabilityProfileOverrides(
    val sensitivity: Int? = null,
    val correlationWindowMs: Long? = null,
    val confirmationDurationMs: Long? = null,
)

data class SensorSourceProfileOverrides(
    val role: SensorRole? = null,
    val thresholdOverride: Double? = null,
    val debounceOverrideMs: Long? = null,
    val samplingProfileOverride: SensorSamplingProfile? = null,
)

data class SensorFusionProfileOverrides(
    val samplingProfile: SensorSamplingProfile? = null,
    val capabilities: Map<SensorCapability, SensorCapabilityProfileOverrides> = emptyMap(),
    val sources: Map<SensorSource, SensorSourceProfileOverrides> = emptyMap(),
)

sealed interface ProfileSpecificOverrides

data object VehicleProfileOverrides : ProfileSpecificOverrides

data class EntryProfileOverrides(
    val angleThresholdDegrees: Int? = null,
    val openConfirmationMs: Long? = null,
) : ProfileSpecificOverrides

data class PowerProfileOverrides(
    val lossConfirmationMs: Long? = null,
    val recoveryConfirmationMs: Long? = null,
) : ProfileSpecificOverrides

/**
 * Immutable calibration evidence frozen at Arm time. The generation changes whenever
 * listeners are re-registered or the process restarts; it never mutates the frozen
 * armed configuration.
 */
sealed interface ArmedCalibrationSnapshot {
    val generation: Long
}

data class VehicleArmedCalibrationSnapshot(
    override val generation: Long,
) : ArmedCalibrationSnapshot

data class EntryArmedCalibrationSnapshot(
    override val generation: Long,
    val modelFingerprint: String,
) : ArmedCalibrationSnapshot

data class PowerArmedCalibrationSnapshot(
    override val generation: Long,
    val modelFingerprint: String,
    val witnessPlacementValidated: Boolean = false,
) : ArmedCalibrationSnapshot

/**
 * The single authoritative protection policy for an armed session. Once created it is
 * immutable until controlled disarm; Settings edits, preset migrations, late sensor
 * samples, or process callbacks cannot change the running policy.
 */
data class ArmedProfileSnapshot(
    val armedSessionId: String,
    val profile: ProtectionProfile,
    val resolvedPresetVersion: Int,
    val effectiveConfiguration: SensorFusionConfiguration,
    val configurationFingerprint: String,
    val commissionedModelFingerprint: String?,
    val armedCalibrationSnapshot: ArmedCalibrationSnapshot,
)

data class StoredProfileConfiguration(
    val profile: ProtectionProfile,
    val presetVersion: Int,
    val sensorOverrides: SensorFusionProfileOverrides,
    val specificOverrides: ProfileSpecificOverrides,
    val setupState: ProfileSetupState,
    /** Commissioned Entry hinge model; null until two-cycle commissioning succeeds. */
    val entryHingeModel: EntryHingeModel? = null,
    /** Commissioned Power witness-lamp evidence; null until lamp off/on commissioning succeeds. */
    val powerWitnessModel: PowerWitnessModel? = null,
)

data class ResolvedProfileConfiguration(
    val profile: ProtectionProfile,
    val presetVersion: Int,
    val sensorConfiguration: SensorFusionConfiguration,
    val specificSettings: ProfileSpecificSettings,
    val setupState: ProfileSetupState,
    val customized: Boolean,
)

data class ProtectionProfileStoreState(
    val schemaVersion: Int = 1,
    val selectedProfile: ProtectionProfile?,
    val profiles: Map<ProtectionProfile, StoredProfileConfiguration>,
    val legacyConfiguration: SensorFusionConfiguration?,
    val switchTransaction: ProfileSwitchTransaction? = null,
)
