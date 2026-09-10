package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A budget phone that ships the sensor but reports from it at 2 Hz is not the same as a
 * phone that streams it at 200 Hz, and neither is the same as a phone without it. These
 * proofs pin the three states apart, including the sources that are on-change by design
 * and must never be called limited for it.
 */
class SensorAvailabilityPolicyTest {

    private fun descriptor(
        source: SensorSource,
        isAvailable: Boolean = true,
        reportingMode: Int = SensorAvailabilityPolicy.REPORTING_MODE_CONTINUOUS,
        minDelayUs: Int = 5_000,
    ) = SensorDescriptor(
        source = source,
        androidType = SensorTypeMap.androidType(source),
        name = "test",
        vendor = "test-vendor",
        reportingMode = reportingMode,
        isWakeUp = false,
        minDelayUs = minDelayUs,
        maxDelayUs = 1_000_000,
        maximumRange = 100f,
        resolution = 0.1f,
        powerMa = 0.5f,
        isAvailable = isAvailable,
    )

    @Test
    fun absentHardwareIsMissing() {
        assertEquals(
            SensorAvailability.MISSING,
            SensorAvailabilityPolicy.availability(
                descriptor(SensorSource.GYROSCOPE, isAvailable = false),
            ),
        )
    }

    @Test
    fun aFastContinuousStreamIsFullyAvailable() {
        assertEquals(
            SensorAvailability.AVAILABLE,
            SensorAvailabilityPolicy.availability(
                descriptor(SensorSource.ACCELEROMETER, minDelayUs = 4_000),
            ),
        )
    }

    @Test
    fun aStreamSlowerThanTenHertzIsLimited() {
        assertEquals(
            SensorAvailability.LIMITED,
            SensorAvailabilityPolicy.availability(
                descriptor(SensorSource.GYROSCOPE, minDelayUs = 500_000),
            ),
        )
    }

    @Test
    fun aStreamThatOnlyReportsOnChangeIsLimited() {
        assertEquals(
            SensorAvailability.LIMITED,
            SensorAvailabilityPolicy.availability(
                descriptor(
                    SensorSource.MAGNETIC_FIELD,
                    reportingMode = SensorAvailabilityPolicy.REPORTING_MODE_ON_CHANGE,
                    minDelayUs = 0,
                ),
            ),
        )
    }

    @Test
    fun sourcesThatAreEventsByDesignAreNotLimitedForBeingEvents() {
        // Light and proximity are on-change on every phone; significant motion is a
        // one-shot trigger. Calling any of them limited would be a permanent false alarm.
        listOf(
            SensorSource.AMBIENT_LIGHT to SensorAvailabilityPolicy.REPORTING_MODE_ON_CHANGE,
            SensorSource.PROXIMITY to SensorAvailabilityPolicy.REPORTING_MODE_ON_CHANGE,
            SensorSource.SIGNIFICANT_MOTION to SensorAvailabilityPolicy.REPORTING_MODE_ONE_SHOT,
        ).forEach { (source, mode) ->
            assertEquals(
                "$source must read as available",
                SensorAvailability.AVAILABLE,
                SensorAvailabilityPolicy.availability(
                    descriptor(source, reportingMode = mode, minDelayUs = 0),
                ),
            )
        }
    }

    @Test
    fun everySourceIsClassifiedWithNoGaps() {
        val classified = SensorAvailabilityPolicy.availability(
            SensorSource.entries.associateWith { descriptor(it) },
        )

        assertEquals(SensorSource.entries.toSet(), classified.keys)
    }
}
