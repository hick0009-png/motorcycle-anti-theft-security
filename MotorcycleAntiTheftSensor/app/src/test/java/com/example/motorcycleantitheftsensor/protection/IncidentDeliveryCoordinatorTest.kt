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
    fun smsFallbackUsesRedactedSmsMessageWithoutCoordinatesOrMapsUrl() = runTest {
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

        // SMS received redacted content
        assertTrue(deliveredSmsMessage != null)
        assertFalse(deliveredSmsMessage!!.contains("maps.google.com"))
        assertFalse(deliveredSmsMessage!!.contains("13.7563"))
        assertFalse(deliveredSmsMessage!!.contains("100.5018"))
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
