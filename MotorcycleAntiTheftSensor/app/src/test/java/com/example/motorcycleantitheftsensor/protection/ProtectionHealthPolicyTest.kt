package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionHealthPolicyTest {
    @Test
    fun hardwareAvailabilityIsNotHealthyUntilFreshSampleArrives() {
        val policy = ProtectionHealthPolicy(
            sensorFreshnessMs = 5_000L,
            serviceFreshnessMs = 10_000L,
            telegramFreshnessMs = 20_000L,
        )
        val available = SensorHealth(SensorHealthState.AVAILABLE)
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)

        assertEquals(SensorHealthState.AVAILABLE, policy.sensorState(available, nowMs = 10_001L))
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(sampled, nowMs = 14_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(sampled, nowMs = 15_001L))
    }

    @Test
    fun staleServiceHeartbeatForcesOffline() {
        val policy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L)

        assertEquals(
            ProtectionState.OFFLINE,
            policy.runtimeState(
                current = ProtectionState.ARMED_HEALTHY,
                lastServiceHeartbeatAtMs = 1_000L,
                nowMs = 11_001L,
            ),
        )
    }

    @Test
    fun telegramReachabilityRequiresRecentConfirmedContact() {
        val policy = ProtectionHealthPolicy(5_000L, 10_000L, 20_000L)

        assertFalse(policy.telegramReachable(lastContactAtMs = null, nowMs = 1L))
        assertTrue(policy.telegramReachable(lastContactAtMs = 1_000L, nowMs = 21_000L))
        assertFalse(policy.telegramReachable(lastContactAtMs = 1_000L, nowMs = 21_001L))
    }
}
