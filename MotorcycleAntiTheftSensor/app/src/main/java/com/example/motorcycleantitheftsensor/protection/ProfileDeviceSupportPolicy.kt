package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorAvailability

/** Why a protection use cannot run, or cannot run fully, on this particular phone. */
enum class ProfileSupportReason {
    /** Nothing on this device senses movement, so a vehicle can be taken unnoticed. */
    NO_MOVEMENT_SENSOR,

    /** No light sensor: no second signal, so nothing can be confirmed by two. */
    NO_LIGHT_SENSOR,

    /** Neither a gyroscope nor a compass: a door angle cannot be measured at all. */
    NO_ANGLE_SENSOR,

    /** A compass but no gyroscope: the angle is measurable, but slower and coarser. */
    NO_GYROSCOPE_COMPASS_ONLY,

    /**
     * This phone measured its own orientation drift and found it fast enough to reach the
     * alert angle inside a long armed session. The watch works; the session has a ceiling.
     */
    DRIFT_LIMITS_SESSION,

    /**
     * Drift reaches the alert angle so quickly that no absence is covered. Offering the door
     * watch here would mean alerting the owner about a door nobody touched, over and over,
     * until they stop believing it — worse than saying plainly that this phone cannot.
     */
    DRIFT_TOO_FAST,
}

/**
 * Whether this device can carry a protection use.
 *
 * [Degraded] still protects and stays selectable; the owner is told what they lose.
 * [Unsupported] cannot detect the thing the use promises at all, and must be refused
 * at both the moment of choosing and the moment of arming.
 */
sealed interface ProfileDeviceSupport {
    data object Supported : ProfileDeviceSupport

    data class Degraded(
        val missing: Set<SensorSource>,
        val reason: ProfileSupportReason,
        /**
         * Hours this phone's own measurement says the watch can be believed. Only the drift
         * reasons carry one; a missing sensor has no ceiling to state, it simply is not there.
         */
        val trustedHours: Int? = null,
    ) : ProfileDeviceSupport

    data class Unsupported(
        val missing: Set<SensorSource>,
        val reason: ProfileSupportReason,
        val trustedHours: Int? = null,
    ) : ProfileDeviceSupport

    val selectable: Boolean get() = this !is Unsupported
}

/**
 * Reads a profile's hardware requirements against what the device actually has
 * (spec: device diversity, section A4).
 *
 * A phone that cannot measure a door angle must never arm the entry watch and report
 * "protecting": today it registers no listener and stays silent forever, which reads
 * as protection right up to the moment it was needed. The rule lives here rather than
 * in Compose so both the picker and the arm path enforce the same one, and an unknown
 * inventory is treated as supported — a missing catalog is not evidence of missing
 * hardware, and refusing every profile on it would be worse than the gap it guards.
 */
object ProfileDeviceSupportPolicy {

    /** Sources that can integrate a door's turn rate. */
    private val ANGLE_SOURCES = setOf(
        SensorSource.GYROSCOPE,
        SensorSource.ROTATION_VECTOR,
        SensorSource.GAME_ROTATION_VECTOR,
    )

    /** Sources that give an absolute heading, which needs no gyroscope. */
    private val COMPASS_SOURCES = setOf(
        SensorSource.MAGNETIC_FIELD,
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
    )

    private val MOVEMENT_SOURCES = setOf(
        SensorSource.ACCELEROMETER,
        SensorSource.LINEAR_ACCELERATION,
        SensorSource.SIGNIFICANT_MOTION,
    )

    /**
     * @param entryDrift what this phone measured about its own orientation drift. Hardware is
     *   asked first: a phone with no way to measure an angle is refused whatever it measured,
     *   and a phone that never measured is treated as fine rather than suspect — the door
     *   watch worked before any of this existed and an unasked question refuses nobody.
     */
    fun support(
        profile: ProtectionProfile,
        availability: Map<SensorSource, SensorAvailability>,
        entryDrift: EntryDriftVerdict = EntryDriftVerdict.NotMeasured,
    ): ProfileDeviceSupport {
        if (availability.isEmpty()) return ProfileDeviceSupport.Supported
        val present: (Set<SensorSource>) -> Boolean = { sources ->
            sources.any { availability[it] != null && availability[it] != SensorAvailability.MISSING }
        }
        val lightMissing = availability[SensorSource.AMBIENT_LIGHT] == SensorAvailability.MISSING

        return when (profile) {
            ProtectionProfile.VEHICLE -> when {
                !present(MOVEMENT_SOURCES) -> ProfileDeviceSupport.Unsupported(
                    missing = MOVEMENT_SOURCES,
                    reason = ProfileSupportReason.NO_MOVEMENT_SENSOR,
                )
                lightMissing -> ProfileDeviceSupport.Degraded(
                    missing = setOf(SensorSource.AMBIENT_LIGHT),
                    reason = ProfileSupportReason.NO_LIGHT_SENSOR,
                )
                else -> ProfileDeviceSupport.Supported
            }

            ProtectionProfile.ENTRY -> when {
                !present(ANGLE_SOURCES) && !present(COMPASS_SOURCES) -> ProfileDeviceSupport.Unsupported(
                    missing = ANGLE_SOURCES + COMPASS_SOURCES,
                    reason = ProfileSupportReason.NO_ANGLE_SENSOR,
                )
                !present(ANGLE_SOURCES) -> ProfileDeviceSupport.Degraded(
                    missing = ANGLE_SOURCES,
                    reason = ProfileSupportReason.NO_GYROSCOPE_COMPASS_ONLY,
                )
                entryDrift is EntryDriftVerdict.Unusable -> ProfileDeviceSupport.Unsupported(
                    missing = emptySet(),
                    reason = ProfileSupportReason.DRIFT_TOO_FAST,
                    trustedHours = EntryDriftBudgetPolicy.trustedHours(entryDrift),
                )
                entryDrift is EntryDriftVerdict.Limited -> ProfileDeviceSupport.Degraded(
                    missing = emptySet(),
                    reason = ProfileSupportReason.DRIFT_LIMITS_SESSION,
                    trustedHours = EntryDriftBudgetPolicy.trustedHours(entryDrift),
                )
                else -> ProfileDeviceSupport.Supported
            }

            // The charging signal exists on every phone, so Power Guard always runs;
            // without the lamp it simply cannot confirm an outage with two signals.
            ProtectionProfile.POWER -> when {
                lightMissing -> ProfileDeviceSupport.Degraded(
                    missing = setOf(SensorSource.AMBIENT_LIGHT),
                    reason = ProfileSupportReason.NO_LIGHT_SENSOR,
                )
                else -> ProfileDeviceSupport.Supported
            }
        }
    }
}
