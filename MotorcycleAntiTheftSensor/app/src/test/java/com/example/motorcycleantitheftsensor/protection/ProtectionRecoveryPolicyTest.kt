package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionRecoveryPolicyTest {
    @Test
    fun persistedArmedStateReentersArmingInsteadOfTrustingStaleHealth() {
        val plan = ProtectionRecoveryPolicy.plan(ProtectionState.ARMED_HEALTHY)

        assertEquals(ProtectionState.ARMING, plan.initialState)
        assertTrue(plan.restartDetectors)
    }

    @Test
    fun previousActiveIncidentIsInterruptedDuringRecovery() {
        val plan = ProtectionRecoveryPolicy.plan(ProtectionState.ALERT_ACTIVE)

        assertEquals(IncidentLifecycle.INTERRUPTED, plan.previousIncidentLifecycle)
    }

    @Test
    fun invalidContinuityProjectsToSetupRequiredWithoutRestartingDetectors() {
        val plan = ProtectionRecoveryPolicy.plan(
            persistedState = ProtectionState.ARMED_HEALTHY,
            intent = ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.ARMED,
                autoRecoveryAfterBoot = true,
                armedSessionId = "session-1",
            ),
            trigger = RecoveryTrigger.ANDROID_BOOT,
            continuityValid = false,
        )

        assertEquals(ProtectionState.SETUP_REQUIRED, plan.initialState)
        assertFalse(plan.restartDetectors)
        assertEquals(RecoveryPhase.RECOVERY_BLOCKED, plan.phase)
    }

    @Test
    fun disabledBootRecoveryProjectsToDisarmedWithoutRestartingDetectors() {
        val plan = ProtectionRecoveryPolicy.plan(
            persistedState = ProtectionState.ARMED_HEALTHY,
            intent = ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.ARMED,
                autoRecoveryAfterBoot = false,
                armedSessionId = "session-1",
            ),
            trigger = RecoveryTrigger.ANDROID_BOOT,
            continuityValid = true,
        )

        assertEquals(ProtectionState.DISARMED_ONLINE, plan.initialState)
        assertFalse(plan.restartDetectors)
        assertEquals(RecoveryPhase.NOT_RECOVERED_BOOT_DISABLED, plan.phase)
    }
}
