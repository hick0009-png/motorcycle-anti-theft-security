package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSnapshotStoreTest {
    @Test
    fun savingSnapshotClearsRemovedLegacyDemoPreference() {
        val preferences = InMemoryProtectionSnapshotPreferences().apply {
            put(mapOf("demo_enabled" to true))
        }
        val store = ProtectionSnapshotStore(
            preferences = preferences,
            clock = ProtectionClock { 1_000L },
        )

        store.save(
            snapshot = ProtectionSnapshot.offline(nowMs = 1_000L),
            lastServiceHeartbeatAtMs = null,
        )

        assertFalse(preferences.contains("demo_enabled"))
    }

    @Test
    fun recoveryGateKeepsCapturedStateAndBlocksPersistenceUntilRecoveryCompletes() {
        val captured = ProtectionRecoveryState(
            liveSnapshot = ProtectionSnapshot.offline(nowMs = 9_000L),
            hints = ProtectionRecoveryHints(
                persistedState = ProtectionState.ARMED_DEGRADED,
                lastTransitionAtMs = 1_000L,
                lastServiceHeartbeatAtMs = 2_000L,
                lastTelegramContactAtMs = 3_000L,
                lastIncidentId = "incident-before-restart",
            ),
        )
        val gate = ProtectionRecoveryGate(captured)

        assertEquals(captured, gate.capturedState)
        assertFalse(gate.shouldPersistSnapshot())

        gate.markRecoveryComplete()

        assertTrue(gate.shouldPersistSnapshot())
    }

    @Test
    fun explicitCommandAfterRecoveryPlanningSupersedesPendingRecovery() {
        val captured = ProtectionRecoveryState(
            liveSnapshot = ProtectionSnapshot.offline(9_000L),
            hints = ProtectionRecoveryHints(
                persistedState = ProtectionState.ARMED_HEALTHY,
                lastTransitionAtMs = 1_000L,
                lastServiceHeartbeatAtMs = 2_000L,
                lastTelegramContactAtMs = 3_000L,
                lastIncidentId = null,
            ),
        )
        val gate = ProtectionRecoveryGate(captured)
        val recoveryWasPlanned = gate.shouldApplyRecovery()

        gate.supersedeRecovery()

        assertTrue(recoveryWasPlanned)
        assertFalse(gate.shouldApplyRecovery())
        assertTrue(gate.shouldPersistSnapshot())
    }

    @Test
    fun armedSnapshotLoadsAsOfflineWithRecoveryHintsAndNoFreshSensorHealth() {
        val preferences = InMemoryProtectionSnapshotPreferences()
        val store = ProtectionSnapshotStore(
            preferences = preferences,
            clock = ProtectionClock { 9_000L },
        )
        store.save(
            snapshot = ProtectionSnapshot.offline(nowMs = 1_000L).copy(
                state = ProtectionState.ARMED_HEALTHY,
                lastTransitionAtMs = 2_000L,
                serviceRunning = true,
                telegramPolling = true,
                telegramReachable = true,
                lastTelegramContactAtMs = 3_000L,
                sensorHealth = mapOf(
                    SensorKind.VIBRATION to SensorHealth(
                        state = SensorHealthState.HEALTHY,
                        lastSampleAtMs = 3_500L,
                    ),
                ),
                lastIncident = IncidentSummary(
                    id = "incident-1",
                    severity = IncidentSeverity.WARNING,
                    lifecycle = IncidentLifecycle.OPEN,
                    updatedAtMs = 4_000L,
                    deliveryState = DeliveryState.SENT,
                ),
                revision = 42L,
            ),
            lastServiceHeartbeatAtMs = 2_500L,
        )

        val recovery = store.loadForRecovery()

        assertEquals(ProtectionState.ARMED_HEALTHY, recovery.hints.persistedState)
        assertEquals(2_500L, recovery.hints.lastServiceHeartbeatAtMs)
        assertEquals(3_000L, recovery.hints.lastTelegramContactAtMs)
        assertEquals("incident-1", recovery.hints.lastIncidentId)
        assertEquals(42L, recovery.hints.revision)
        assertEquals(ProtectionState.OFFLINE, recovery.liveSnapshot.state)
        assertEquals(9_000L, recovery.liveSnapshot.lastTransitionAtMs)
        assertTrue(recovery.liveSnapshot.sensorHealth.isEmpty())
        assertFalse(recovery.liveSnapshot.serviceRunning)
        assertFalse(recovery.liveSnapshot.telegramReachable)
    }
}

private class InMemoryProtectionSnapshotPreferences : ProtectionSnapshotPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun put(values: Map<String, Any?>) {
        values.forEach { (key, value) ->
            if (value == null) {
                this.values.remove(key)
            } else {
                this.values[key] = value
            }
        }
    }

    override fun getString(key: String): String? = values[key] as? String

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    fun contains(key: String): Boolean = values.containsKey(key)
}
