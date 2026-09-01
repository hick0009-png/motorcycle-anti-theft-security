package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone that cannot measure a door angle must not be allowed to arm the entry watch
 * and report "protecting". These proofs pin which hardware each use actually needs, and
 * that a use which merely loses accuracy stays selectable rather than being taken away.
 */
class ProfileDeviceSupportPolicyTest {

    private fun inventory(
        missing: Set<SensorSource> = emptySet(),
    ): Map<SensorSource, SensorAvailability> = SensorSource.entries.associateWith { source ->
        if (source in missing) SensorAvailability.MISSING else SensorAvailability.AVAILABLE
    }

    @Test
    fun aCompleteDeviceSupportsEveryUse() {
        ProtectionProfile.entries.forEach { profile ->
            assertEquals(
                "$profile must be supported on a complete device",
                ProfileDeviceSupport.Supported,
                ProfileDeviceSupportPolicy.support(profile, inventory()),
            )
        }
    }

    @Test
    fun entryIsUnsupportedWithoutAnythingThatMeasuresAnAngle() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            inventory(
                missing = setOf(
                    SensorSource.GYROSCOPE,
                    SensorSource.ROTATION_VECTOR,
                    SensorSource.GAME_ROTATION_VECTOR,
                    SensorSource.MAGNETIC_FIELD,
                    SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
                ),
            ),
        )

        assertTrue(support is ProfileDeviceSupport.Unsupported)
        assertEquals(
            ProfileSupportReason.NO_ANGLE_SENSOR,
            (support as ProfileDeviceSupport.Unsupported).reason,
        )
        assertEquals(false, support.selectable)
    }

    @Test
    fun entryStaysSelectableOnACompassOnlyDevice() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            inventory(
                missing = setOf(
                    SensorSource.GYROSCOPE,
                    SensorSource.ROTATION_VECTOR,
                    SensorSource.GAME_ROTATION_VECTOR,
                ),
            ),
        )

        assertTrue(support is ProfileDeviceSupport.Degraded)
        assertEquals(
            ProfileSupportReason.NO_GYROSCOPE_COMPASS_ONLY,
            (support as ProfileDeviceSupport.Degraded).reason,
        )
        assertTrue(support.selectable)
    }

    @Test
    fun powerIsNeverUnsupportedBecauseChargingIsAlwaysReadable() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.POWER,
            inventory(missing = setOf(SensorSource.AMBIENT_LIGHT)),
        )

        assertTrue(support is ProfileDeviceSupport.Degraded)
        assertEquals(
            ProfileSupportReason.NO_LIGHT_SENSOR,
            (support as ProfileDeviceSupport.Degraded).reason,
        )
        assertTrue(support.selectable)
    }

    @Test
    fun vehicleNeedsSomethingThatSensesMovement() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.VEHICLE,
            inventory(
                missing = setOf(
                    SensorSource.ACCELEROMETER,
                    SensorSource.LINEAR_ACCELERATION,
                    SensorSource.SIGNIFICANT_MOTION,
                ),
            ),
        )

        assertTrue(support is ProfileDeviceSupport.Unsupported)
        assertEquals(
            ProfileSupportReason.NO_MOVEMENT_SENSOR,
            (support as ProfileDeviceSupport.Unsupported).reason,
        )
    }

    @Test
    fun aLimitedSensorStillCounts() {
        // Present but slow is a reason to warn, never a reason to call the use impossible.
        val limited = SensorSource.entries.associateWith { SensorAvailability.LIMITED }

        ProtectionProfile.entries.forEach { profile ->
            assertTrue(
                "$profile must stay selectable on limited hardware",
                ProfileDeviceSupportPolicy.support(profile, limited).selectable,
            )
        }
    }

    @Test
    fun anUnreadInventoryClaimsNothing() {
        // No catalog is not evidence of missing hardware; refusing every use would be
        // worse than the gap this policy guards.
        ProtectionProfile.entries.forEach { profile ->
            assertEquals(
                ProfileDeviceSupport.Supported,
                ProfileDeviceSupportPolicy.support(profile, emptyMap()),
            )
        }
    }
}
