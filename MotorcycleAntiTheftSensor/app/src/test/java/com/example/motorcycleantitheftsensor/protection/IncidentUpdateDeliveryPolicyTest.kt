package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentUpdateDeliveryPolicyTest {
    private val policy = IncidentUpdateDeliveryPolicy(updateCoalesceIntervalMs = 1000L)

    @Test
    fun onlyOpenEscalationAndCloseProduceExternalMessages() {
        val warning = warningIncident()
        val critical = warning.copy(severity = IncidentSeverity.CRITICAL)
        val closed = critical.copy(lifecycle = IncidentLifecycle.CLOSED, closedAtMs = 2_000L)

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 1000L))
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warning), 2100L))
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Escalated(critical), 2200L))
        assertEquals(DeliveryAction.SEND_CLOSE_SUMMARY, policy.action(IncidentUpdate.Closed(closed), 2300L))
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Ignored, 2400L))
    }

    @Test
    fun rapidRoutineUpdatesAreCoalesced() {
        val warning = warningIncident()

        // First update at t=1000 -> PERSIST_ONLY
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warning), 1000L))

        // Rapid updates within 1000ms window -> NONE
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Updated(warning), 1100L))
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Updated(warning), 1500L))
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Updated(warning), 1999L))

        // Update after 1000ms window -> PERSIST_ONLY
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warning), 2000L))
    }

    @Test
    fun activeIncidentEditsProgressEveryTenSecondsAndSendsContinuationAfterOneMinute() {
        val policy = IncidentUpdateDeliveryPolicy(
            updateCoalesceIntervalMs = 1_000L,
            progressEditIntervalMs = 10_000L,
            continuationInitialDelayMs = 60_000L,
            continuationRepeatIntervalMs = 300_000L,
        )
        val warning = warningIncident()

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 0L))
        assertEquals(DeliveryAction.PERSIST_AND_EDIT, policy.action(IncidentUpdate.Updated(warning), 10_000L))
        assertEquals(DeliveryAction.SEND_CONTINUATION, policy.action(IncidentUpdate.Updated(warning), 60_000L))
        assertEquals(DeliveryAction.PERSIST_ONLY, policy.action(IncidentUpdate.Updated(warning), 69_999L))
        assertEquals(DeliveryAction.PERSIST_AND_EDIT, policy.action(IncidentUpdate.Updated(warning), 70_000L))
        assertEquals(DeliveryAction.SEND_CONTINUATION, policy.action(IncidentUpdate.Updated(warning), 360_000L))
    }
}

private fun warningIncident(): SecurityIncident = incident(
    id = "warning-1",
    updatedAtMs = 1_000L,
    severity = IncidentSeverity.WARNING,
)
