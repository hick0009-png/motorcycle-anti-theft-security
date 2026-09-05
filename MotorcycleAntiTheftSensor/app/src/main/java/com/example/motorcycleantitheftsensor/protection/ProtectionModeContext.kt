package com.example.motorcycleantitheftsensor.protection

/**
 * The durable half of what a mode knows about itself.
 *
 * Everything here survives a process death and changes only when the owner changes a
 * setting or finishes a calibration, which is what makes it safe to carry inside
 * [ProtectionSnapshot]: it enters `ProjectionKey`, and a value that moved with every
 * sensor sample would turn every sample into a semantic change and write a durable
 * snapshot for each one. Live readings — the angle right now, the lux right now, the
 * metres from the parking spot right now — are deliberately absent and are read on
 * demand instead (see `ModeLiveReadings`).
 */
sealed interface ModeFacts

/**
 * The vehicle watch keeps its two owner-visible numbers where they already are:
 * the sensitivity in [ProtectionSnapshot.sensitivityLevel], the displacement threshold
 * in `MovementDisplacementPolicy`. Nothing durable is left for this to carry, and an
 * empty case is better than a field that restates one of those and can drift from it.
 */
data object VehicleModeFacts : ModeFacts

/**
 * @param driftVerdict what this phone measured about its own orientation drift, kept as
 *   the verdict rather than as whole hours so the status report can state the same
 *   sentence the settings screen states, from the same source.
 */
data class EntryModeFacts(
    val angleThresholdDegrees: Int,
    val openConfirmationMs: Long,
    val closeThresholdDegrees: Int,
    val closeConfirmationMs: Long,
    val hingeModelCommissioned: Boolean,
    val hingeOrientationSourceLabel: String? = null,
    val hingeCommissionedAtWallMs: Long? = null,
    val driftVerdict: EntryDriftVerdict = EntryDriftVerdict.NotMeasured,
) : ModeFacts

data class PowerModeFacts(
    val lossConfirmationMs: Long,
    val recoveryConfirmationMs: Long,
    val witnessCommissioned: Boolean,
    val witnessDarkThresholdLux: Double? = null,
    val witnessLitThresholdLux: Double? = null,
    val witnessCommissionedAtWallMs: Long? = null,
) : ModeFacts

/**
 * Which use the owner is being protected by, carried on the snapshot so that a report
 * built from the snapshot alone can speak for that use.
 *
 * [ArmedProfileSnapshot] cannot answer this: it is frozen at Arm and null every other
 * moment, and `/status` is asked while disarmed about as often as while armed — "why
 * will it not arm" only means something once the mode is known. So this is filled in
 * whether or not anything is armed, and its absence means one specific thing: a customer
 * upgraded from before modes existed who has never chosen one.
 *
 * @param selectedProfile null for that upgraded customer, never a guess. Inferring the
 *   mode from which sensors happen to be running would be wrong in exactly the case the
 *   owner most needs the truth.
 * @param entryLevel non-null only for [ProtectionProfile.ENTRY]. The two levels give
 *   completely different answers — one measures degrees, the other cannot — so a report
 *   that knew the mode but not the level would still speak for the wrong one.
 * @param switchingTo set only while a confirmed profile change is mid-flight, when the
 *   honest answer is that nothing is being watched right now.
 */
data class ProtectionModeContext(
    val selectedProfile: ProtectionProfile?,
    val entryLevel: EntryWatchLevel? = null,
    val setupState: ProfileSetupState = ProfileSetupState.READY,
    val support: ProfileDeviceSupport = ProfileDeviceSupport.Supported,
    val switchingTo: ProtectionProfile? = null,
    val modeFacts: ModeFacts? = null,
) {
    /**
     * The signal roles this mode detects with, as the single source every screen and
     * report must read. A formatter that kept its own table is what produced a report
     * telling the owner to fix the GPS in the mode that switches the GPS off.
     */
    fun signalRoles(): Map<SensorKind, SensorRole>? = selectedProfile?.let { profile ->
        ProtectionProfilePolicy.signalRoles(
            profile,
            entryLevel ?: EntryWatchLevel.DOOR_ANGLE,
        )
    }

    /** Sensor kinds this mode actually detects with; null when no mode has been chosen. */
    fun usedSensorKinds(): Set<SensorKind>? =
        signalRoles()?.filterValues { it != SensorRole.OFF }?.keys
}

/**
 * The sensor kinds the frozen session actually detects with.
 *
 * Callers used to reach for `usedSensorKinds(profile)` and take the default door level,
 * which counts five kinds for a session armed at the sound-and-movement level that only
 * ever registered two. Every degradation count built on that was wrong after a restart.
 */
fun ArmedProfileSnapshot.usedSensorKinds(): Set<SensorKind> =
    ProtectionProfilePolicy.usedSensorKinds(
        profile,
        entryLevel ?: EntryWatchLevel.DOOR_ANGLE,
    )
