package com.example.motorcycleantitheftsensor.protection

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
