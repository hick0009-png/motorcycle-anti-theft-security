package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.security.ProtectionPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionUiTextTest {
    @Test
    fun recordAudioIsOptionalMicrophoneCoverageNotAProtectionBlocker() {
        val permission = ProtectionPermissionPolicy.RECORD_AUDIO
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(permission),
            sdkInt = 28,
        )

        assertTrue(readiness.blockers.isEmpty())
        assertEquals(setOf("RECORD_AUDIO unavailable"), readiness.degradations)
        assertEquals(
            "Microphone access is missing. Noise detection will be unavailable.",
            friendlyPermissionExplanation(permission),
        )
    }

    @Test
    fun notificationPermissionCanRemainATrueProtectionBlocker() {
        val permission = ProtectionPermissionPolicy.POST_NOTIFICATIONS
        val readiness = ProtectionPermissionPolicy.readiness(
            missingPermissions = setOf(permission),
            sdkInt = 33,
        )

        assertEquals(setOf("POST_NOTIFICATIONS"), readiness.blockers)
        assertEquals(
            "Notification access is missing. Protection alerts may not appear.",
            friendlyPermissionExplanation(permission),
        )
    }
}
