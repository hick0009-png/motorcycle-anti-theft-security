package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentCloseDispatcherTest {
    @Test
    fun localClosePersistenceCompletesWithoutWaitingForExternalDelivery() = runTest {
        val persisted = mutableListOf<String>()
        val externalStarted = CompletableDeferred<Unit>()
        val allowExternal = CompletableDeferred<Unit>()
        val dispatcher = IncidentCloseDispatcher(
            scope = this,
            persistLocal = { incident -> persisted += incident.id },
            deliverExternal = {
                externalStarted.complete(Unit)
                allowExternal.await()
            },
        )
        val closed = IncidentUpdate.Closed(closedIncident("closed-1"))

        val result = async { dispatcher.persistAndDispatch(closed) }
        runCurrent()

        assertTrue(result.isCompleted)
        assertEquals(listOf("closed-1"), persisted)
        assertTrue(externalStarted.isCompleted)
        allowExternal.complete(Unit)
    }
}

private fun closedIncident(id: String): SecurityIncident = SecurityIncident(
    id = id,
    type = IncidentType.VIBRATION,
    source = IncidentSource.REAL,
    severity = IncidentSeverity.WARNING,
    lifecycle = IncidentLifecycle.CLOSED,
    evidence = emptyList(),
    openedAtMs = 1_000L,
    updatedAtMs = 2_000L,
    closedAtMs = 2_000L,
    protectionState = ProtectionState.ALERT_ACTIVE,
    deliveryState = DeliveryState.PENDING,
    closeReason = "owner disarmed",
)
