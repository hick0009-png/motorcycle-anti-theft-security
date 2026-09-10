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

    /**
     * The escalation that repeats is the shape the device found: a displaced phone re-raised
     * mount movement on every sample, each one a full delivery. That specific path is fixed at
     * the detector, and this is the rule that would have caught it without knowing it existed
     * — an escalation to a severity the owner already has is not news.
     */
    @Test
    fun anEscalationToASeverityAlreadyAnnouncedIsNotSentAgain() {
        val warning = warningIncident()
        val critical = warning.copy(severity = IncidentSeverity.CRITICAL)

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 1_000L))
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Escalated(critical), 2_000L))

        // Same severity, over and over, for as long as the phone stays displaced.
        repeat(10) { tick ->
            assertEquals(
                DeliveryAction.SUPPRESS_REPEAT,
                policy.action(IncidentUpdate.Escalated(critical), 3_000L + tick * 2_000L),
            )
        }

        // The close summary is never bounded by any of this.
        val closed = critical.copy(lifecycle = IncidentLifecycle.CLOSED, closedAtMs = 30_000L)
        assertEquals(
            DeliveryAction.SEND_CLOSE_SUMMARY,
            policy.action(IncidentUpdate.Closed(closed), 30_000L),
        )
    }

    /**
     * A real rise in severity is the most important thing this app ever says, and it can
     * happen at most once on this scale. No floor and no ceiling may reach it.
     */
    @Test
    fun aRealRiseInSeverityIsNeverWithheld() {
        val warning = warningIncident()
        val critical = warning.copy(severity = IncidentSeverity.CRITICAL)

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 1_000L))
        // Immediately after the opening, well inside every window there is.
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Escalated(critical), 1_050L))
    }

    @Test
    fun aFlappingConditionSaysItOnceAMinuteRatherThanEveryTimeItTurnsOver() {
        val warning = warningIncident()
        fun changed(atMs: Long) = policy.action(
            IncidentUpdate.Updated(warning, ownerVisibleConditionChange = true),
            atMs,
        )

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 1_000L))
        // The first changed condition always owns its own message.
        assertEquals(DeliveryAction.SEND, changed(2_000L))
        // Flapping inside the floor repeats what the owner already knows.
        assertEquals(DeliveryAction.SUPPRESS_REPEAT, changed(4_000L))
        assertEquals(DeliveryAction.SUPPRESS_REPEAT, changed(30_000L))
        assertEquals(DeliveryAction.SUPPRESS_REPEAT, changed(61_999L))
        // Past the floor it is worth saying again.
        assertEquals(DeliveryAction.SEND, changed(62_000L))
    }

    @Test
    fun anIncidentThatWillNotStopChangingStillStopsCostingMessages() {
        val warning = warningIncident()
        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 0L))

        var sent = 1
        var at = 0L
        repeat(40) {
            at += IncidentUpdateDeliveryPolicy.DEFAULT_MIN_CONDITION_ALERT_INTERVAL_MS
            val action = policy.action(
                IncidentUpdate.Updated(warning, ownerVisibleConditionChange = true),
                at,
            )
            if (action == DeliveryAction.SEND) sent += 1
        }

        assertEquals(IncidentUpdateDeliveryPolicy.DEFAULT_MAX_ALERTS_PER_INCIDENT, sent)
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
    fun ownerVisibleConditionChangeSendsItsOwnMessageInsteadOfBeingCoalesced() {
        val warning = warningIncident()

        assertEquals(DeliveryAction.SEND, policy.action(IncidentUpdate.Opened(warning), 1_000L))
        // Inside the coalesce window a routine update stays silent...
        assertEquals(DeliveryAction.NONE, policy.action(IncidentUpdate.Updated(warning), 1_100L))
        // ...but a changed condition owns its own message.
        assertEquals(
            DeliveryAction.SEND,
            policy.action(
                IncidentUpdate.Updated(warning, ownerVisibleConditionChange = true),
                1_200L,
            ),
        )
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
