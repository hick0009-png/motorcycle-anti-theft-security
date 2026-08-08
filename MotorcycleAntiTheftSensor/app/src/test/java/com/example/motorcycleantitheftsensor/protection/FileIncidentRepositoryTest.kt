package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileIncidentRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retainsNewestTwoHundredIncidentsAcrossRepositoryReopen() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)
        (1..205).forEach { index ->
            repository.upsert(incident(id = "i-$index", updatedAtMs = index.toLong()))
        }

        val reloaded = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()

        assertEquals(200, reloaded.size)
        assertEquals("i-205", reloaded.first().id)
        assertEquals("i-6", reloaded.last().id)
    }

    @Test
    fun upsertReplacesMatchingIncidentInsteadOfDuplicatingIt() {
        val repository = FileIncidentRepository(
            file = temporaryFolder.newFile("incidents.bin"),
            maxRecords = 200,
        )
        repository.upsert(incident(id = "i-1", updatedAtMs = 1L))
        repository.upsert(
            incident(id = "i-1", updatedAtMs = 2L).copy(
                severity = IncidentSeverity.CRITICAL,
            ),
        )

        val stored = repository.listNewestFirst().single()
        assertEquals(IncidentSeverity.CRITICAL, stored.severity)
        assertEquals(2L, stored.updatedAtMs)
    }

    @Test
    fun clearingHistoryRemovesOnlyIncidentRecords() {
        val repository = FileIncidentRepository(
            file = temporaryFolder.newFile("incidents.bin"),
            maxRecords = 200,
        )
        repository.upsert(incident(id = "i-1", updatedAtMs = 1L))

        repository.clearHistory()

        assertTrue(repository.listNewestFirst().isEmpty())
    }
}

internal fun incident(
    id: String,
    updatedAtMs: Long,
    source: IncidentSource = IncidentSource.REAL,
    severity: IncidentSeverity = IncidentSeverity.WARNING,
    lifecycle: IncidentLifecycle = IncidentLifecycle.OPEN,
    deliveryState: DeliveryState = DeliveryState.PENDING,
): SecurityIncident = SecurityIncident(
    id = id,
    type = IncidentType.VIBRATION,
    source = source,
    severity = severity,
    lifecycle = lifecycle,
    evidence = listOf(
        IncidentEvidence(
            kind = SensorKind.VIBRATION,
            eventElapsedMs = updatedAtMs,
            wallClockMs = updatedAtMs,
            normalizedValue = 2.0,
            baselineDelta = 1.0,
            diagnostic = "fixture",
        ),
    ),
    openedAtMs = 1L,
    updatedAtMs = updatedAtMs,
    closedAtMs = if (lifecycle == IncidentLifecycle.OPEN) null else updatedAtMs,
    protectionState = ProtectionState.ALERT_ACTIVE,
    deliveryState = deliveryState,
    closeReason = if (lifecycle == IncidentLifecycle.OPEN) null else "fixture closed",
)
