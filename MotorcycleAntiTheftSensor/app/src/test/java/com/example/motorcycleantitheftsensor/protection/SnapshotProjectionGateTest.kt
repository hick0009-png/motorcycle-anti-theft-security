package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotProjectionGateTest {
    @Test
    fun timestampOnlySensorChurnIsRateLimitedButStateChangePublishesImmediately() {
        val gate = SnapshotProjectionGate(minIntervalMs = 5_000L)
        val initial = snapshot(
            state = ProtectionState.ARMED_HEALTHY,
            sampleAtMs = 1_000L,
        )

        assertTrue(gate.shouldProject(initial, nowMs = 1_000L))
        assertFalse(
            gate.shouldProject(initial.withSampleAt(1_100L), nowMs = 1_100L),
        )
        assertTrue(
            gate.shouldProject(initial.withSampleAt(6_000L), nowMs = 6_000L),
        )
        assertTrue(
            gate.shouldProject(
                initial.withSampleAt(6_100L).copy(state = ProtectionState.ALERT_ACTIVE),
                nowMs = 6_100L,
            ),
        )
    }
}

private fun snapshot(
    state: ProtectionState,
    sampleAtMs: Long,
): ProtectionSnapshot = ProtectionSnapshot.offline(0L).copy(
    state = state,
    serviceRunning = true,
    telegramPolling = true,
    sensorHealth = mapOf(
        SensorKind.VIBRATION to SensorHealth(
            state = SensorHealthState.HEALTHY,
            lastSampleAtMs = sampleAtMs,
            detail = "accelerometer_magnitude",
        ),
    ),
)

private fun ProtectionSnapshot.withSampleAt(atMs: Long): ProtectionSnapshot = copy(
    sensorHealth = sensorHealth.mapValues { (_, health) -> health.copy(lastSampleAtMs = atMs) },
)
