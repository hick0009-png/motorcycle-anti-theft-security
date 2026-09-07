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

/**
 * How much of the door watch this owner has set up.
 *
 * The angle is the better answer and the expensive one: it needs the phone mounted, two
 * guided open/close cycles, and a measurement of the phone's own drift before it can be
 * trusted overnight. An owner who has just chosen the use has none of that, and until now
 * they had no protection at all in the meantime — the use could not arm without a
 * commissioned model, so the first night was spent unwatched.
 *
 * Sound and movement together need none of it. They cannot say how far the door opened, and
 * a lorry in the street will reach the microphone, which is why they are not the finished
 * article. They can be armed the moment the use is chosen, which the finished article
 * cannot.
 */
enum class EntryWatchLevel {
    /** Sound and movement, together, with no calibration of any kind. */
    SOUND_AND_MOVEMENT,

    /** The commissioned hinge angle, which can say how far the door opened. */
    DOOR_ANGLE,
}

data class EntryProfileSettings(
    val angleThresholdDegrees: Int = 15,
    val openConfirmationMs: Long = 750L,
    /**
     * At or past this angle the door is swung wide enough that the 750ms dwell is not asked for
     * — only [fastOpenConfirmationMs], a floor short enough to catch a door flung open and shut
     * again inside the dwell (the fast flick that used to leave no alert) and long enough to
     * reject a lone spurious sample. A door edged just past [angleThresholdDegrees] still has to
     * hold the full [openConfirmationMs]; the shortcut is only for a swing too wide to be noise.
     */
    val fastOpenAngleDegrees: Int = 45,
    val fastOpenConfirmationMs: Long = 200L,
    val closeThresholdDegrees: Int = 3,
    val closeConfirmationMs: Long = 5_000L,
    val level: EntryWatchLevel = EntryWatchLevel.SOUND_AND_MOVEMENT,
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
    /** Null means the owner has not chosen a level by hand; see `ProtectionProfilePolicy.resolve`. */
    val level: EntryWatchLevel? = null,
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
    /**
     * The door watch level this session was armed at; null for every other use, and for
     * a session frozen before this field existed.
     *
     * The level decides which sensors detect at all, and it lived only in a private
     * field of the coordinator. Anything reading the frozen session back — a report, a
     * degradation count after a process restart — had to assume `DOOR_ANGLE` and would
     * count sensors the sound-and-movement level never registered.
     */
    val entryLevel: EntryWatchLevel? = null,
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
