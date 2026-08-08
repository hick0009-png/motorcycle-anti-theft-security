package com.example.motorcycleantitheftsensor.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionPermissionPolicyTest {
    @Test
    fun deniedMicrophonePreventsArming() {
        val readiness = ProtectionPermissionPolicy.readiness(setOf("android.permission.RECORD_AUDIO"))
        assertFalse(readiness.canArm)
    }

    @Test
    fun noMissingPermissionsAllowsArming() {
        assertTrue(ProtectionPermissionPolicy.readiness(emptySet()).canArm)
    }
}
