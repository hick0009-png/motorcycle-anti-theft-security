package com.example.motorcycleantitheftsensor.protection

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun v1HistoryDiscardsLegacyDemoRecordsButKeepsRealRecords() {
        val file = temporaryFolder.newFile("incidents.bin")
        writeV1History(
            file = file,
            records = listOf(
                LegacyV1Incident("real-1", "REAL"),
                LegacyV1Incident("demo-1", "DEMO"),
            ),
        )

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()

        assertEquals(listOf("real-1"), records.map(SecurityIncident::id))
    }

    @Test
    fun v2HistoryLoadsWithNullLocation() {
        val file = temporaryFolder.newFile("incidents.bin")
        writeV2History(file, listOf(incident(id = "v2-1", updatedAtMs = 100L)))

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()

        assertEquals(1, records.size)
        assertEquals("v2-1", records.first().id)
        assertNull(records.first().location)
    }

    @Test
    fun v3HistoryRoundTripsTypedLocation() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)
        val location = IncidentLocation(
            latitude = 13.7563,
            longitude = 100.5018,
            accuracyMeters = 8.5f,
            capturedAtWallClockMs = 123456L,
        )
        val incidentWithLocation = incident(id = "v3-loc", updatedAtMs = 200L).copy(location = location)
        repository.upsert(incidentWithLocation)

        val reloaded = FileIncidentRepository(file, maxRecords = 200).listNewestFirst().single()
        assertEquals("v3-loc", reloaded.id)
        assertEquals(location, reloaded.location)
    }

    @Test
    fun v3HistoryWithInvalidCoordinatesLoadsIncidentWithNullLocationWithoutThrowing() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)
        // Upsert valid first
        repository.upsert(incident(id = "v3-bad", updatedAtMs = 200L))
        // Now manually overwrite the file with an invalid latitude v3 record
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D475249)
            output.writeInt(3)
            output.writeInt(1)
            output.writeUTF("v3-bad")
            output.writeUTF(IncidentType.VIBRATION.name)
            output.writeUTF(IncidentSeverity.WARNING.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(0)
            output.writeLong(1L)
            output.writeLong(200L)
            output.writeBoolean(false) // closedAtMs null
            output.writeUTF(ProtectionState.ALERT_ACTIVE.name)
            output.writeUTF(DeliveryState.PENDING.name)
            output.writeInt(0) // delivery attempts
            output.writeBoolean(false) // closeReason null
            output.writeBoolean(true) // has location
            output.writeDouble(999.0) // invalid latitude (> 90.0)
            output.writeDouble(100.5018)
            output.writeFloat(8.5f)
            output.writeLong(123456L)
        }

        val reloaded = FileIncidentRepository(file, maxRecords = 200).listNewestFirst().single()
        assertEquals("v3-bad", reloaded.id)
        assertNull(reloaded.location)
    }
}

private data class LegacyV1Incident(
    val id: String,
    val sourceToken: String,
)

private fun writeV1History(file: File, records: List<LegacyV1Incident>) {
    DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
        output.writeInt(0x4D475249)
        output.writeInt(1)
        output.writeInt(records.size)
        records.forEach { record ->
            output.writeUTF(record.id)
            output.writeUTF(IncidentType.VIBRATION.name)
            output.writeUTF(record.sourceToken)
            output.writeUTF(IncidentSeverity.WARNING.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(0)
            output.writeLong(1L)
            output.writeLong(1L)
            output.writeBoolean(false)
            output.writeUTF(ProtectionState.ALERT_ACTIVE.name)
            output.writeUTF(DeliveryState.PENDING.name)
            output.writeInt(0)
            output.writeBoolean(false)
        }
    }
}

private fun writeV2History(file: File, records: List<SecurityIncident>) {
    DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
        output.writeInt(0x4D475249)
        output.writeInt(2)
        output.writeInt(records.size)
        records.forEach { incident ->
            output.writeUTF(incident.id)
            output.writeUTF(incident.type.name)
            output.writeUTF(incident.severity.name)
            output.writeUTF(incident.lifecycle.name)
            output.writeInt(incident.evidence.size)
            incident.evidence.forEach { evidence ->
                output.writeUTF(evidence.kind.name)
                output.writeLong(evidence.eventElapsedMs)
                output.writeLong(evidence.wallClockMs)
                output.writeDouble(evidence.normalizedValue)
                output.writeDouble(evidence.baselineDelta)
                output.writeBoolean(evidence.diagnostic != null)
                if (evidence.diagnostic != null) output.writeUTF(evidence.diagnostic)
            }
            output.writeLong(incident.openedAtMs)
            output.writeLong(incident.updatedAtMs)
            output.writeBoolean(incident.closedAtMs != null)
            if (incident.closedAtMs != null) output.writeLong(incident.closedAtMs)
            output.writeUTF(incident.protectionState.name)
            output.writeUTF(incident.deliveryState.name)
            output.writeInt(incident.deliveryAttempts.size)
            incident.deliveryAttempts.forEach { attempt ->
                output.writeUTF(attempt.channel.name)
                output.writeUTF(attempt.state.name)
                output.writeLong(attempt.attemptedAtMs)
                output.writeBoolean(attempt.detail != null)
                if (attempt.detail != null) output.writeUTF(attempt.detail)
            }
            output.writeBoolean(incident.closeReason != null)
            if (incident.closeReason != null) output.writeUTF(incident.closeReason)
        }
    }
}

internal fun incident(
    id: String,
    updatedAtMs: Long,
    severity: IncidentSeverity = IncidentSeverity.WARNING,
    lifecycle: IncidentLifecycle = IncidentLifecycle.OPEN,
    deliveryState: DeliveryState = DeliveryState.PENDING,
): SecurityIncident = SecurityIncident(
    id = id,
    type = IncidentType.VIBRATION,
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
    location = null,
)
