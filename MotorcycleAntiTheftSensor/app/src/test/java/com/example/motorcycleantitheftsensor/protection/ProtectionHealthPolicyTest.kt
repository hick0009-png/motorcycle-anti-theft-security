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
    fun vibrationStaleAfter5s() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)
        val withDetail = SensorHealth(
            SensorHealthState.HEALTHY,
            vibrationDetail = VibrationHealthDetail(lastSampleWallClockMs = 10_000L),
        )

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.VIBRATION, sampled, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.VIBRATION, sampled, nowMs = 15_001L))

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.VIBRATION, withDetail, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.VIBRATION, withDetail, nowMs = 15_001L))
    }

    @Test
    fun lightStaleAfter5s() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)
        val withDetail = SensorHealth(
            SensorHealthState.HEALTHY,
            lightDetail = LightHealthDetail(lastSampleWallClockMs = 10_000L),
        )

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LIGHT, sampled, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LIGHT, sampled, nowMs = 15_001L))

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LIGHT, withDetail, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LIGHT, withDetail, nowMs = 15_001L))
    }

    @Test
    fun microphoneStaleAfter5s() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)
        val withDetail = SensorHealth(
            SensorHealthState.HEALTHY,
            microphoneDetail = MicrophoneHealthDetail(lastAudioSampleAtMs = 10_000L),
        )

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.MICROPHONE, sampled, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.MICROPHONE, sampled, nowMs = 15_001L))

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.MICROPHONE, withDetail, nowMs = 15_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.MICROPHONE, withDetail, nowMs = 15_001L))
    }

    @Test
    fun gpsStaleAfter30s() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)
        val withDetail = SensorHealth(
            SensorHealthState.HEALTHY,
            locationDetail = LocationHealthDetail(lastFixWallClockMs = 10_000L),
        )

        // fresh at 29s
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LOCATION, sampled, nowMs = 39_000L))
        // fresh at 30s
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LOCATION, sampled, nowMs = 40_000L))
        // stale at 31s
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LOCATION, sampled, nowMs = 41_000L))

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LOCATION, withDetail, nowMs = 39_000L))
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.LOCATION, withDetail, nowMs = 40_000L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LOCATION, withDetail, nowMs = 41_000L))
    }

    @Test
    fun powerThermalNotStaleEvenAfter60s() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)
        val withDetail = SensorHealth(
            SensorHealthState.HEALTHY,
            powerThermalDetail = PowerThermalHealthDetail(lastUpdateWallClockMs = 10_000L),
        )

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.POWER_THERMAL, sampled, nowMs = 70_000L))
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.POWER_THERMAL, sampled, nowMs = 3_600_000L))

        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.POWER_THERMAL, withDetail, nowMs = 70_000L))
        assertEquals(SensorHealthState.HEALTHY, policy.sensorState(SensorKind.POWER_THERMAL, withDetail, nowMs = 3_600_000L))
    }

    @Test
    fun negativeAgeClockAnomalyTreatedAsAbnormal() {
        val policy = ProtectionHealthPolicy()
        val sampled = SensorHealth(SensorHealthState.HEALTHY, lastSampleAtMs = 10_000L)

        // Negative age should not be HEALTHY
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.VIBRATION, sampled, nowMs = 9_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LIGHT, sampled, nowMs = 9_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.MICROPHONE, sampled, nowMs = 9_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.LOCATION, sampled, nowMs = 9_999L))
        assertEquals(SensorHealthState.STALE, policy.sensorState(SensorKind.POWER_THERMAL, sampled, nowMs = 9_999L))

        // Backward-compatible method
        assertEquals(SensorHealthState.STALE, policy.sensorState(sampled, nowMs = 9_999L))

        // Service & Telegram clock anomaly
        assertFalse(policy.serviceIsFresh(lastServiceHeartbeatAtMs = 10_000L, nowMs = 9_999L))
        assertFalse(policy.telegramReachable(lastContactAtMs = 10_000L, nowMs = 9_999L))
    }

    @Test
    fun serviceHeartbeatFreshWithin10sStaleAfter10s() {
        val policy = ProtectionHealthPolicy()

        assertTrue(policy.serviceIsFresh(lastServiceHeartbeatAtMs = 10_000L, nowMs = 20_000L))
        assertFalse(policy.serviceIsFresh(lastServiceHeartbeatAtMs = 10_000L, nowMs = 20_001L))
        assertFalse(policy.serviceIsFresh(lastServiceHeartbeatAtMs = null, nowMs = 20_000L))
    }

    @Test
    fun telegramContactFreshWithin20sUnreachableAfter20s() {
        val policy = ProtectionHealthPolicy()

        assertTrue(policy.telegramReachable(lastContactAtMs = 10_000L, nowMs = 30_000L))
        assertFalse(policy.telegramReachable(lastContactAtMs = 10_000L, nowMs = 30_001L))
        assertFalse(policy.telegramReachable(lastContactAtMs = null, nowMs = 30_000L))
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
    fun freshnessBoundaryEvaluations() {
        val policy = ProtectionHealthPolicy()

        assertEquals(FreshnessState.MISSING, policy.freshness(null, 10_000L, 5_000L))
        assertEquals(FreshnessState.FRESH, policy.freshness(5_000L, 10_000L, 5_000L))
        assertEquals(FreshnessState.STALE, policy.freshness(4_999L, 10_000L, 5_000L))
        assertEquals(FreshnessState.CLOCK_ANOMALY, policy.freshness(10_001L, 10_000L, 5_000L))
        assertEquals(FreshnessState.CLOCK_ANOMALY, policy.freshness(-1L, 10_000L, 5_000L))
    }
}
