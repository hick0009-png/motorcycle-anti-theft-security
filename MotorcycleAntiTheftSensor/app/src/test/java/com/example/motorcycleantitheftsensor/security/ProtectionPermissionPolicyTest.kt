package com.example.motorcycleantitheftsensor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionPermissionPolicyTest {
    @Test
    fun deniedMicrophoneAllowsArmingWithNamedOptionalDegradation() {
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(ProtectionPermissionPolicy.RECORD_AUDIO),
            sdkInt = 33,
        )

        assertTrue(readiness.canArm)
        assertEquals(emptySet<String>(), readiness.blockers)
        assertEquals(setOf("RECORD_AUDIO unavailable"), readiness.degradations)
    }

    @Test
    fun deniedNotificationPermissionBlocksArmingOnApiThirtyThree() {
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(ProtectionPermissionPolicy.POST_NOTIFICATIONS),
            sdkInt = 33,
        )

        assertFalse(readiness.canArm)
        assertEquals(setOf("POST_NOTIFICATIONS"), readiness.blockers)
    }

    @Test
    fun notificationPermissionIsNotRequiredBeforeApiThirtyThree() {
        assertEquals(emptySet<String>(), ProtectionPermissionPolicy.requiredPermissions(sdkInt = 32))
        assertEquals(
            setOf(ProtectionPermissionPolicy.POST_NOTIFICATIONS),
            ProtectionPermissionPolicy.requiredPermissions(sdkInt = 33),
        )
    }

    @Test
    fun deniedSmsPermissionIsManagedAsFallbackDegradation() {
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(ProtectionPermissionPolicy.SEND_SMS),
            sdkInt = 36,
        )

        assertTrue(readiness.canArm)
        assertEquals(emptySet<String>(), readiness.blockers)
        assertEquals(setOf("SEND_SMS unavailable"), readiness.degradations)
        assertTrue(ProtectionPermissionPolicy.optionalPermissions().contains(ProtectionPermissionPolicy.SEND_SMS))
    }

    @Test
    fun locationPermissionsAreManagedAsOptionalCapabilities() {
        assertTrue(
            ProtectionPermissionPolicy.optionalPermissions().containsAll(
                setOf(
                    ProtectionPermissionPolicy.ACCESS_FINE_LOCATION,
                    ProtectionPermissionPolicy.ACCESS_COARSE_LOCATION,
                ),
            ),
        )
    }

    @Test
    fun eitherGrantedLocationPermissionMakesLocationCapabilityAvailable() {
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(ProtectionPermissionPolicy.ACCESS_FINE_LOCATION),
            sdkInt = 36,
        )

        assertFalse(readiness.degradations.any { it.contains("LOCATION") })
    }
}
