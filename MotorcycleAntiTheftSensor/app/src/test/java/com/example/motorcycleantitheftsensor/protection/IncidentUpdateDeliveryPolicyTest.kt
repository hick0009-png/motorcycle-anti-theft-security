package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentUpdateDeliveryPolicyTest {
    private val policy = IncidentUpdateDeliveryPolicy()

    @Test
    fun onlyOpenEscalationAndCloseProduceExternalMessages() {
        val warning = warningIncident()
        val critical = warning.copy(severity = IncidentSeverity.CRITICAL)
        val closed = critical.copy(lifecycle = IncidentLifecycle.CLOSED, closedAtMs = 2_000L)

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning)))
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warning)))
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Escalated(critical)))
        assertEquals(DeliveryAction.SEND_CLOSE_SUMMARY, policy.action(IncidentUpdate.Closed(closed)))
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Ignored))
    }
}

private fun warningIncident(): SecurityIncident = incident(
    id = "warning-1",
    updatedAtMs = 1_000L,
    source = IncidentSource.REAL,
    severity = IncidentSeverity.WARNING,
)
