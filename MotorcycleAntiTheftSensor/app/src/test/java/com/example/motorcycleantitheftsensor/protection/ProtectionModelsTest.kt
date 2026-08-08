package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProtectionModelsTest {
    @Test
    fun offlineSnapshotDoesNotClaimLiveMonitoring() {
        val snapshot = ProtectionSnapshot.offline(nowMs = 1_000L)

        assertEquals(ProtectionState.OFFLINE, snapshot.state)
        assertFalse(snapshot.serviceRunning)
        assertFalse(snapshot.telegramPolling)
        assertEquals(1_000L, snapshot.lastTransitionAtMs)
        assertEquals(emptySet<String>(), snapshot.permissionBlockers)
    }
}
