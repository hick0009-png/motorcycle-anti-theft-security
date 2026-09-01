package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorSource

/**
 * What this device can actually do with one sensor source.
 *
 * Three states, never two. A budget phone often ships the sensor its spec sheet
 * promises but reports from it far too slowly to confirm anything; showing that as
 * fully available would tell the owner they are as protected as a flagship.
 */
enum class SensorAvailability { AVAILABLE, LIMITED, MISSING }

/**
 * Reads availability off the hardware descriptor. Pure, so the rule is testable
 * without a device and cannot drift into Compose.
 *
 * "Limited" is judged against what the source is *supposed* to report like: light and
 * proximity are on-change by design and a one-shot trigger is the whole point of
 * significant motion, so none of those are limited for being what they are. A source
 * that should stream continuously and instead arrives on-change, or no faster than
 * [SLOW_MIN_DELAY_US], cannot support a confirmation window that assumes a rate.
 */
object SensorAvailabilityPolicy {

    /** Android's `Sensor.REPORTING_MODE_*` values, kept here so the rule stays pure. */
    const val REPORTING_MODE_CONTINUOUS = 0
    const val REPORTING_MODE_ON_CHANGE = 1
    const val REPORTING_MODE_ONE_SHOT = 2
    const val REPORTING_MODE_SPECIAL_TRIGGER = 3

    /** Slower than 10 Hz: too coarse for the movement and rotation confirmation windows. */
    const val SLOW_MIN_DELAY_US = 100_000

    fun availability(descriptor: SensorDescriptor): SensorAvailability {
        if (!descriptor.isAvailable) return SensorAvailability.MISSING
        if (!expectsContinuousStream(descriptor.source)) return SensorAvailability.AVAILABLE
        if (descriptor.reportingMode != REPORTING_MODE_CONTINUOUS) return SensorAvailability.LIMITED
        if (descriptor.minDelayUs > SLOW_MIN_DELAY_US) return SensorAvailability.LIMITED
        return SensorAvailability.AVAILABLE
    }

    fun availability(descriptors: Map<SensorSource, SensorDescriptor>): Map<SensorSource, SensorAvailability> =
        descriptors.mapValues { (_, descriptor) -> availability(descriptor) }

    /** True when the detectors read this source as a stream rather than as an event. */
    fun expectsContinuousStream(source: SensorSource): Boolean = when (source) {
        SensorSource.SIGNIFICANT_MOTION,
        SensorSource.AMBIENT_LIGHT,
        SensorSource.PROXIMITY,
        -> false

        SensorSource.ACCELEROMETER,
        SensorSource.LINEAR_ACCELERATION,
        SensorSource.GYROSCOPE,
        SensorSource.ROTATION_VECTOR,
        SensorSource.GAME_ROTATION_VECTOR,
        SensorSource.MAGNETIC_FIELD,
        SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
        -> true
    }
}
