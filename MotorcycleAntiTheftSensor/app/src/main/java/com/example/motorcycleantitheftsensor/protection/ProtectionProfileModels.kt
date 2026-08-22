package com.example.motorcycleantitheftsensor.protection

enum class ProtectionProfile {
    VEHICLE,
    ENTRY,
    POWER,
}

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
    val recoveryConfirmationMs: Long = 30_000L,
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

data class StoredProfileConfiguration(
    val profile: ProtectionProfile,
    val presetVersion: Int,
    val sensorOverrides: SensorFusionProfileOverrides,
    val specificOverrides: ProfileSpecificOverrides,
    val setupState: ProfileSetupState,
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
)
