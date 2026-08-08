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
}
