package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class IncidentDeliveryCoordinatorTest {
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
    fun demoNeverUsesSmsAfterTelegramFailure() = runTest {
        var smsCalls = 0
        val coordinator = coordinator(
            telegramSuccess = false,
            onSms = { smsCalls++; true },
        )

        val delivered = coordinator.deliver(
            criticalDemo(),
            DeliveryConfiguration(smsConfigured = true),
        )

        assertEquals(0, smsCalls)
        assertEquals(DeliveryState.FAILED, delivered.deliveryState)
        assertEquals(listOf(DeliveryChannel.TELEGRAM), delivered.deliveryAttempts.map { it.channel })
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
    source = IncidentSource.REAL,
    severity = IncidentSeverity.CRITICAL,
)

private fun criticalDemo(): SecurityIncident = incident(
    id = "demo-critical",
    updatedAtMs = 1_000L,
    source = IncidentSource.DEMO,
    severity = IncidentSeverity.CRITICAL,
)

private fun warningReal(): SecurityIncident = incident(
    id = "real-warning",
    updatedAtMs = 1_000L,
    source = IncidentSource.REAL,
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
