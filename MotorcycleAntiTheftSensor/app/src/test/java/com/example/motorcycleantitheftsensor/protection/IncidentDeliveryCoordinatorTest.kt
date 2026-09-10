package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentDeliveryCoordinatorTest {
    @Test
    fun openedIncidentTracksOneTelegramMessageAndProgressUpdatesEditIt() = runTest {
        val operations = mutableListOf<String>()
        val progress = object : IncidentProgressTransport {
            override suspend fun open(incidentId: String, message: String): Boolean {
                operations += "open:$incidentId:$message"
                return true
            }

            override suspend fun update(incidentId: String, message: String): Boolean {
                operations += "update:$incidentId:$message"
                return true
            }

            override suspend fun clear(incidentId: String) {
                operations += "clear:$incidentId"
            }
        }
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { false },
            sms = IncidentTransport { false },
            progressTelegram = progress,
        )
        val incident = criticalReal().copy(id = "progress-1", updatedAtMs = 1_000L)

        coordinator.deliver(IncidentUpdate.Opened(incident), DeliveryConfiguration(smsConfigured = false))
        coordinator.updateProgress(incident)

        assertEquals(2, operations.size)
        assertTrue(operations[0].startsWith("open:progress-1:"))
        assertTrue(operations[1].startsWith("update:progress-1:"))
        assertTrue(operations[1].contains("เหตุการณ์ยังดำเนินอยู่"))
    }

    @Test
    fun persistsPendingBeforeTelegramAndRecordsSentOnlyAfterSuccess() = runTest {
        val events = mutableListOf<String>()
        val repository = RecordingIncidentRepository(events)
        val coordinator = IncidentDeliveryCoordinator(
            repository = repository,
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { events += "telegram"; true },
            sms = IncidentTransport { events += "sms"; true },
        )

        val delivered = coordinator.deliver(
            criticalReal(),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(listOf("persist:PENDING", "telegram", "persist:SENT"), events)
        assertEquals(DeliveryState.SENT, delivered.deliveryState)
        assertEquals(DeliveryChannel.TELEGRAM, delivered.deliveryAttempts.single().channel)
        assertEquals(DeliveryState.SENT, delivered.deliveryAttempts.single().state)
    }

    @Test
    fun warningNeverUsesSmsAfterTelegramFailure() = runTest {
        var smsCalls = 0
        val coordinator = coordinator(
            telegramSuccess = false,
            onSms = { smsCalls++; true },
        )

        val delivered = coordinator.deliver(
            warningReal(),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(0, smsCalls)
        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
    }

    @Test
    fun realCriticalIncidentUsesConfiguredSmsOnlyAfterTelegramFailure() = runTest {
        val events = mutableListOf<String>()
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(events),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { events += "telegram"; false },
            sms = IncidentTransport { events += "sms"; true },
        )

        val delivered = coordinator.deliver(
            criticalReal(),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(
            listOf("persist:PENDING", "telegram", "sms", "persist:SENT"),
            events,
        )
        assertEquals(
            listOf(DeliveryChannel.TELEGRAM, DeliveryChannel.SMS),
            delivered.deliveryAttempts.map { it.channel },
        )
    }

    @Test
    fun pendingPersistenceFailureReturnsTerminalFailureWithoutExternalSend() = runTest {
        var telegramCalls = 0
        var smsCalls = 0
        val coordinator = IncidentDeliveryCoordinator(
            repository = FailingIncidentRepository(),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { telegramCalls += 1; true },
            sms = IncidentTransport { smsCalls += 1; true },
        )

        val delivered = coordinator.deliver(
            criticalReal(),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
        assertEquals(0, telegramCalls)
        assertEquals(0, smsCalls)
        assertEquals(DeliveryChannel.LOCAL_STORAGE, delivered.deliveryAttempts.single().channel)
        assertEquals("Incident persistence unavailable", delivered.deliveryAttempts.single().detail)
    }

    @Test
    fun smsFallbackCarriesCoordinatesButNotTheGeocodedLabelOrMapsUrl() = runTest {
        var deliveredSmsMessage: String? = null
        var deliveredTelegramMessage: String? = null
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { msg -> deliveredTelegramMessage = msg; false },
            sms = IncidentTransport { msg -> deliveredSmsMessage = msg; true },
            labelResolver = LocationLabelResolver { "Bangkok, Thailand" },
        )

        val incidentWithLocation = criticalReal().copy(
            location = IncidentLocation(13.7563, 100.5018, 5f, 1000L),
        )
        val delivered = coordinator.deliver(
            incidentWithLocation,
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(DeliveryState.SENT, delivered.deliveryState)
        // Telegram attempted with location presentation
        assertTrue(deliveredTelegramMessage != null && deliveredTelegramMessage!!.contains("maps.google.com"))
        assertTrue(deliveredTelegramMessage!!.contains("Bangkok, Thailand"))

        // SMS carries the coordinates a searcher can act on, and nothing the network paid for
        assertTrue(deliveredSmsMessage != null)
        assertTrue(deliveredSmsMessage!!.contains("13.75630,100.50180"))
        assertFalse(deliveredSmsMessage!!.contains("maps.google.com"))
        assertFalse(deliveredSmsMessage!!.contains("Bangkok, Thailand"))
    }

    @Test
    fun concurrentDeliveriesForSameEscalatedUpdateInvokeTelegramTransportExactlyOnce() = runTest {
        var telegramCallCount = 0
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport {
                kotlinx.coroutines.delay(50)
                telegramCallCount++
                true
            },
            sms = IncidentTransport { true },
        )

        val incident = criticalReal().copy(id = "inc-escalated-1", updatedAtMs = 5_000L)
        val update = IncidentUpdate.Escalated(incident)
        val config = DeliveryConfiguration(smsConfigured = true)

        val job1 = async { coordinator.deliver(update, config) }
        val job2 = async { coordinator.deliver(update, config) }
        val job3 = async { coordinator.deliver(update, config) }

        val res1 = job1.await()
        val res2 = job2.await()
        val res3 = job3.await()

        assertEquals(1, telegramCallCount)
        assertEquals(DeliveryState.SENT, res1.deliveryState)
        assertEquals(DeliveryState.SENT, res2.deliveryState)
        assertEquals(DeliveryState.SENT, res3.deliveryState)
    }

    /**
     * The memo exists so one event cannot be announced twice. A failure is not an announcement,
     * and remembering it turned every later attempt at the same event into a replay of the
     * failure — which is exactly what a retry after the network returns is.
     */
    @Test
    fun aDeliveryThatFailedIsAttemptedAgainRatherThanReplayedFromTheMemo() = runTest {
        var telegramWorks = false
        var attempts = 0
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport {
                attempts += 1
                telegramWorks
            },
            sms = IncidentTransport { false },
        )
        val update = IncidentUpdate.Opened(criticalReal())
        val configuration = DeliveryConfiguration(smsConfigured = false)

        val first = coordinator.deliver(update, configuration)
        telegramWorks = true
        val second = coordinator.deliver(update, configuration)
        val third = coordinator.deliver(update, configuration)

        assertEquals(DeliveryState.FAILED, first.deliveryState)
        assertEquals(DeliveryState.SENT, second.deliveryState)
        // The success is memoized, so the third call costs nothing and announces nothing.
        assertEquals(DeliveryState.SENT, third.deliveryState)
        assertEquals(2, attempts)
    }

    @Test
    fun aRedeliveredIncidentSaysItIsLate() = runTest {
        val messages = mutableListOf<String>()
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { message ->
                messages += message
                true
            },
            sms = IncidentTransport { false },
        )
        val incident = criticalReal().copy(updatedAtMs = 1_000L)

        coordinator.redeliver(
            incident,
            DeliveryConfiguration(smsConfigured = false),
            nowMs = 1_000L + 90L * 60L * 1000L,
        )

        assertEquals(1, messages.size)
        assertTrue(messages.single().startsWith("⏱ ส่งย้อนหลัง"))
        assertTrue(messages.single().contains("1 ชั่วโมง 30 นาที"))
    }
}

/**
 * The fallback used to be reserved for CRITICAL, and a door opening while armed is raised as
 * WARNING — CRITICAL is kept for the mount being moved. So the one event the door watch exists
 * to report was the one event with no second channel, which is what the phone would have done
 * on the night the network was gone even with a destination saved.
 */
class SmsFallbackEligibilityTest {

    @Test
    fun aDoorOpeningReachesTheFallbackEvenThoughItIsOnlyAWarning() = runTest {
        val smsMessages = mutableListOf<String>()
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { false },
            sms = IncidentTransport { message ->
                smsMessages += message
                true
            },
        )

        val delivered = coordinator.deliver(
            entryIncident(ProtectionDiagnostics.ENTRY_DOOR_OPEN),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(1, smsMessages.size)
        assertEquals(DeliveryState.SENT, delivered.deliveryState)
    }

    /**
     * An unavailable orientation source is a health notice about the watch rather than a report
     * of somebody at the door. Spending the fallback on those is how the allowance is gone
     * before the night it matters.
     */
    @Test
    fun aWarningThatIsNotADoorOpeningStillDoesNotSpendTheFallback() = runTest {
        var smsTried = false
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { false },
            sms = IncidentTransport { smsTried = true; true },
        )

        val delivered = coordinator.deliver(
            entryIncident(ProtectionDiagnostics.ENTRY_SOURCE_UNAVAILABLE),
            DeliveryConfiguration(smsConfigured = true),
        )

        org.junit.Assert.assertFalse(smsTried)
        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
    }

    @Test
    fun theFallbackIsStillOnlyReachedAfterTelegramHasFailed() = runTest {
        var smsTried = false
        val coordinator = IncidentDeliveryCoordinator(
            repository = RecordingIncidentRepository(mutableListOf()),
            formatter = IncidentMessageFormatter(),
            telegram = IncidentTransport { true },
            sms = IncidentTransport { smsTried = true; true },
        )

        coordinator.deliver(
            entryIncident(ProtectionDiagnostics.ENTRY_DOOR_OPEN),
            DeliveryConfiguration(smsConfigured = true),
        )

        org.junit.Assert.assertFalse(smsTried)
    }

    private fun entryIncident(diagnostic: String): SecurityIncident = incident(
        id = "entry-$diagnostic",
        updatedAtMs = 1_000L,
        severity = IncidentSeverity.WARNING,
    ).copy(
        type = IncidentType.ENTRY_DOOR,
        evidence = listOf(
            IncidentEvidence(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = 1_000L,
                wallClockMs = 1_000L,
                normalizedValue = 25.0,
                baselineDelta = 25.0,
                diagnostic = diagnostic,
            ),
        ),
    )
}

private fun coordinator(
    telegramSuccess: Boolean,
    onSms: suspend (String) -> Boolean,
): IncidentDeliveryCoordinator = IncidentDeliveryCoordinator(
    repository = RecordingIncidentRepository(mutableListOf()),
    formatter = IncidentMessageFormatter(),
    telegram = IncidentTransport { telegramSuccess },
    sms = IncidentTransport(onSms),
)

private fun criticalReal(): SecurityIncident = incident(
    id = "real-critical",
    updatedAtMs = 1_000L,
    severity = IncidentSeverity.CRITICAL,
)

private fun warningReal(): SecurityIncident = incident(
    id = "real-warning",
    updatedAtMs = 1_000L,
    severity = IncidentSeverity.WARNING,
)

private class RecordingIncidentRepository(
    private val events: MutableList<String>,
) : IncidentRepository {
    private val incidents = linkedMapOf<String, SecurityIncident>()

    override fun upsert(incident: SecurityIncident) {
        events += "persist:${incident.deliveryState}"
        incidents[incident.id] = incident
    }

    override fun findById(id: String): SecurityIncident? = incidents[id]

    override fun listNewestFirst(): List<SecurityIncident> = incidents.values.toList()

    override fun clearHistory() {
        incidents.clear()
    }
}

private class FailingIncidentRepository : IncidentRepository {
    override fun upsert(incident: SecurityIncident) {
        error("disk unavailable")
    }

    override fun findById(id: String): SecurityIncident? = null

    override fun listNewestFirst(): List<SecurityIncident> = emptyList()

    override fun clearHistory() = Unit
}
