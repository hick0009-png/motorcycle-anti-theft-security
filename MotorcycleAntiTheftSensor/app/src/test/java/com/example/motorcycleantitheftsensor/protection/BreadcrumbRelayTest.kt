package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.telephony.SmsSendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbRelayTest {

    @Test
    fun crumbsWrittenBeforeAnythingIsListeningAreDropped() {
        val relay = BreadcrumbRelay()

        relay.note(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.OK)

        val received = mutableListOf<String>()
        relay.attach { domain, event, _ -> received += "${domain.code}:${event.code}" }

        // The crumb above belonged to a moment with no file open. It is gone, not queued: a
        // backlog of crumbs replayed into the next run would date every one of them wrongly.
        assertTrue(received.isEmpty())
    }

    @Test
    fun anAttachedSinkReceivesDomainEventAndDetails() {
        val relay = BreadcrumbRelay()
        val received = mutableListOf<String>()
        relay.attach { domain, event, details ->
            received += "${domain.code}:${event.code}:${details.joinToString(",") { it.code }}"
        }

        relay.note(
            BreadcrumbDomain.TELEGRAM,
            BreadcrumbEvent.FAILED,
            listOf(BreadcrumbDetail.TIMEOUT),
        )

        assertEquals(listOf("tg:failed:timeout"), received)
    }

    @Test
    fun aDetachedRelayIsSilentAgain() {
        val relay = BreadcrumbRelay()
        val received = mutableListOf<String>()
        relay.attach { domain, event, _ -> received += "${domain.code}:${event.code}" }

        relay.note(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.OK)
        relay.detach()
        relay.note(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.OK)

        assertEquals(listOf("tg:ok"), received)
    }

    /**
     * The relay sits on the send path of the thing it describes. A sink that throws — a
     * recorder torn down mid-write, a disk that went away — must cost the send nothing.
     */
    @Test
    fun aThrowingSinkCannotTakeDownTheCallerItDescribes() {
        val relay = BreadcrumbRelay()
        relay.attach { _, _, _ -> error("recorder gone") }

        relay.note(BreadcrumbDomain.TELEGRAM, BreadcrumbEvent.OK)
    }
}

class SmsOutcomeBreadcrumbTest {

    /**
     * A fallback that decided not to send is a working fallback. Reading that as a failure
     * sends the next person looking for a radio fault that never happened — so the two must
     * not share an event code.
     */
    @Test
    fun refusingToSendAndFailingToSendAreDifferentRows() {
        assertEquals("sms:ok", row(SmsSendOutcome.SENT))
        assertEquals("sms:denied:limited", row(SmsSendOutcome.RATE_LIMITED))
        assertEquals("sms:denied:noconf", row(SmsSendOutcome.NO_DESTINATION))
        assertEquals("sms:denied:nokey", row(SmsSendOutcome.NO_KEY))
        assertEquals("sms:failed:timeout", row(SmsSendOutcome.TIMED_OUT))
        assertEquals("sms:failed:unknown", row(SmsSendOutcome.FAILED))
    }

    @Test
    fun everyOutcomeHasARow() {
        SmsSendOutcome.entries.forEach { outcome ->
            assertTrue(row(outcome).startsWith("sms:"))
        }
    }

    private fun row(outcome: SmsSendOutcome): String {
        val details = outcome.breadcrumbDetails().joinToString(",") { it.code }
        val suffix = if (details.isEmpty()) "" else ":$details"
        return "${BreadcrumbDomain.SMS.code}:${outcome.breadcrumbEvent().code}$suffix"
    }
}
