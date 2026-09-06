package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentRedeliveryPolicyTest {

    private val policy = IncidentRedeliveryPolicy()

    @Test
    fun onlyIncidentsThatNeverReachedAnybodyAreSelected() {
        val history = listOf(
            failed("undelivered", at = 1_000L),
            incident("sent", updatedAtMs = 1_000L, deliveryState = DeliveryState.SENT),
            incident("pending", updatedAtMs = 1_000L, deliveryState = DeliveryState.PENDING),
        )

        assertEquals(listOf("undelivered"), policy.select(history, nowMs = 2_000L).map { it.id })
    }

    @Test
    fun aFailureOlderThanTheWindowIsLeftToTheHistory() {
        val old = failed("last-week", at = 1_000L)

        val selected = policy.select(
            listOf(old),
            nowMs = 1_000L + IncidentRedeliveryPolicy.DEFAULT_MAX_AGE_MS + 1L,
        )

        assertTrue(selected.isEmpty())
    }

    /**
     * A phone whose clock jumped forward and back must not lose a warning nobody ever saw:
     * the age gate exists to stop stale news, not to be a second way of dropping a message.
     */
    @Test
    fun aClockThatMovedBackwardsDoesNotDiscardTheBacklog() {
        val future = failed("clock-jumped", at = 9_000L)

        assertEquals(listOf("clock-jumped"), policy.select(listOf(future), nowMs = 1_000L).map { it.id })
    }

    @Test
    fun anIncidentTriedEnoughTimesStopsBeingTried() {
        val exhausted = failed("tried", at = 1_000L)
            .copy(deliveryAttempts = List(IncidentRedeliveryPolicy.DEFAULT_MAX_REMOTE_ATTEMPTS) { attempt ->
                DeliveryAttempt(DeliveryChannel.TELEGRAM, DeliveryState.FAILED, attemptedAtMs = 1_000L + attempt)
            })

        assertTrue(policy.select(listOf(exhausted), nowMs = 2_000L).isEmpty())
    }

    @Test
    fun theBacklogIsOrderedOldestFirstAndCapped() {
        val history = (1..IncidentRedeliveryPolicy.DEFAULT_MAX_PER_FLUSH + 5).map { index ->
            failed("i$index", at = 10_000L - index)
        }

        val selected = policy.select(history, nowMs = 20_000L)

        assertEquals(IncidentRedeliveryPolicy.DEFAULT_MAX_PER_FLUSH, selected.size)
        assertEquals(selected.sortedBy { it.updatedAtMs }, selected)
        assertEquals("i${IncidentRedeliveryPolicy.DEFAULT_MAX_PER_FLUSH + 5}", selected.first().id)
    }
}

class IncidentRedelivererTest {

    /**
     * The night the change exists for, end to end: three incidents recorded while the phone had
     * no way out, and one signal that the network is back.
     */
    @Test
    fun aBacklogGoesOutOldestFirstAndSaysHowLateItIs() = runTest {
        val repository = InMemoryIncidentRepository()
        val sent = mutableListOf<String>()
        val midnight = 1_000_000L
        val eightHoursLater = midnight + 8L * 60L * 60L * 1000L
        repository.upsert(failed("second", at = midnight + 60_000L))
        repository.upsert(failed("first", at = midnight))

        val redeliverer = redeliverer(repository, eightHoursLater) { message ->
            sent += message
            true
        }

        val outcome = redeliverer.flush()

        assertEquals(2, outcome.sent)
        assertEquals(0, outcome.stillFailing)
        assertEquals(2, sent.size)
        assertTrue(sent.first().contains("ส่งย้อนหลัง"))
        assertTrue(sent.first().contains("8 ชั่วโมง"))
        assertTrue(sent.last().contains("7 ชั่วโมง 59 นาที"))
        assertEquals(
            listOf(DeliveryState.SENT, DeliveryState.SENT),
            listOf("first", "second").map { repository.findById(it)?.deliveryState },
        )
    }

    @Test
    fun aBacklogThatHasAlreadyGoneOutIsNotSentAgain() = runTest {
        val repository = InMemoryIncidentRepository()
        val sent = mutableListOf<String>()
        repository.upsert(failed("only", at = 1_000L))
        val redeliverer = redeliverer(repository, nowMs = 60_000L) { message ->
            sent += message
            true
        }

        redeliverer.flush()
        val second = redeliverer.flush()

        assertEquals(1, sent.size)
        assertEquals(0, second.attempted)
    }

    /**
     * A transport that is still down answers the same way twenty times in a row. Spending the
     * whole backlog against it costs twenty timeouts and tells the owner nothing; the next
     * regained network brings the rest.
     */
    @Test
    fun aStillDeadPathCostsOneAttemptRatherThanTheWholeBacklog() = runTest {
        val repository = InMemoryIncidentRepository()
        var attempts = 0
        repository.upsert(failed("a", at = 1_000L))
        repository.upsert(failed("b", at = 2_000L))
        repository.upsert(failed("c", at = 3_000L))
        val redeliverer = redeliverer(repository, nowMs = 60_000L) {
            attempts += 1
            false
        }

        val outcome = redeliverer.flush()

        assertEquals(1, attempts)
        assertEquals(0, outcome.sent)
        assertEquals(3, outcome.stillFailing)
    }

    @Test
    fun eachFailedFlushCountsAgainstTheAttemptCeiling() = runTest {
        val repository = InMemoryIncidentRepository()
        var attempts = 0
        // No prior attempt, so the count this test watches is the ceiling itself.
        repository.upsert(failed("a", at = 1_000L).copy(deliveryAttempts = emptyList()))
        val redeliverer = redeliverer(repository, nowMs = 60_000L) {
            attempts += 1
            false
        }

        repeat(IncidentRedeliveryPolicy.DEFAULT_MAX_REMOTE_ATTEMPTS + 3) { redeliverer.flush() }

        assertEquals(IncidentRedeliveryPolicy.DEFAULT_MAX_REMOTE_ATTEMPTS, attempts)
    }

    private fun redeliverer(
        repository: IncidentRepository,
        nowMs: Long,
        telegram: suspend (String) -> Boolean,
    ) = IncidentRedeliverer(
        history = { repository.listNewestFirst() },
        delivery = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport(telegram),
            sms = IncidentTransport { false },
        ),
        configuration = { DeliveryConfiguration(smsConfigured = false) },
        nowMs = { nowMs },
    )
}

private fun failed(id: String, at: Long): SecurityIncident = incident(
    id = id,
    updatedAtMs = at,
    severity = IncidentSeverity.CRITICAL,
    deliveryState = DeliveryState.FAILED,
).copy(
    deliveryAttempts = listOf(
        DeliveryAttempt(DeliveryChannel.TELEGRAM, DeliveryState.FAILED, attemptedAtMs = at),
    ),
)

private class InMemoryIncidentRepository : IncidentRepository {
    private val incidents = linkedMapOf<String, SecurityIncident>()

    override fun upsert(incident: SecurityIncident) {
        incidents[incident.id] = incident
    }

    override fun findById(id: String): SecurityIncident? = incidents[id]

    override fun listNewestFirst(): List<SecurityIncident> =
        incidents.values.sortedByDescending { it.updatedAtMs }

    override fun clearHistory() = incidents.clear()
}
