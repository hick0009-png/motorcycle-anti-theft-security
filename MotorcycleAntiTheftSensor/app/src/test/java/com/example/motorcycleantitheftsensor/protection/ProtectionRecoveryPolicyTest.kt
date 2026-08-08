package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
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
}
