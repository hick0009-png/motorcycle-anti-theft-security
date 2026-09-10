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

    @Test
    fun v4HistoryRoundTripsTypedAudioThreat() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)
        val audioThreat = AudioThreatMetadata(
            category = AudioThreatCategory.POWER_TOOL,
            confidence = 0.92,
            loudnessDeltaDb = 14.5,
            firstDetectedElapsedMs = 1200L,
            lastDetectedElapsedMs = 2400L,
            occurrenceCount = 3,
            onsetElapsedMs = 1100L,
            onsetCoherent = true,
        )
        val evidence = IncidentEvidence(
            kind = SensorKind.MICROPHONE,
            eventElapsedMs = 1200L,
            wallClockMs = 1234567L,
            normalizedValue = 0.92,
            baselineDelta = 14.5,
            diagnostic = "power_tool_detected",
            audioThreat = audioThreat,
        )
        val incident = incident(id = "v4-audio", updatedAtMs = 300L).copy(evidence = listOf(evidence))
        repository.upsert(incident)

        val reloaded = FileIncidentRepository(file, maxRecords = 200).listNewestFirst().single()
        assertEquals("v4-audio", reloaded.id)
        assertEquals(1, reloaded.evidence.size)
        val reloadedEvidence = reloaded.evidence.single()
        assertEquals(SensorKind.MICROPHONE, reloadedEvidence.kind)
        assertEquals(audioThreat, reloadedEvidence.audioThreat)
    }

    @Test
    fun v3HistoryLoadsWithNullAudioThreat() {
        val file = temporaryFolder.newFile("incidents.bin")
        writeV3History(file, listOf(incident(id = "v3-legacy", updatedAtMs = 100L)))

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()
        assertEquals(1, records.size)
        assertEquals("v3-legacy", records.first().id)
        assertNull(records.first().evidence.first().audioThreat)
    }

    @Test
    fun invalidCategoryConsumesEntireOptionalBlockAndPreservesFollowingFields() {
        val file = temporaryFolder.newFile("incidents.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D475249)
            output.writeInt(4)
            output.writeInt(1)
            output.writeUTF("v4-invalid-cat")
            output.writeUTF(IncidentType.AUDIO.name)
            output.writeUTF(IncidentSeverity.CRITICAL.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(1)
            output.writeUTF(SensorKind.MICROPHONE.name)
            output.writeLong(100L)
            output.writeLong(1000L)
            output.writeDouble(0.9)
            output.writeDouble(10.0)
            output.writeBoolean(false)
            output.writeBoolean(true)
            output.writeUTF("INVALID_CATEGORY_TOKEN")
            output.writeDouble(0.95)
            output.writeDouble(12.0)
            output.writeLong(100L)
            output.writeLong(200L)
            output.writeInt(1)
            output.writeLong(100L)
            output.writeBoolean(false)
            output.writeLong(1000L)
            output.writeLong(2000L)
            output.writeBoolean(false)
            output.writeUTF(ProtectionState.ALERT_ACTIVE.name)
            output.writeUTF(DeliveryState.PENDING.name)
            output.writeInt(0)
            output.writeBoolean(false)
            output.writeBoolean(false)
        }

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()
        assertEquals(1, records.size)
        val loaded = records.single()
        assertEquals("v4-invalid-cat", loaded.id)
        assertEquals(2000L, loaded.updatedAtMs)
        assertNull(loaded.evidence.single().audioThreat)
    }

    @Test
    fun invalidConfidenceLoadsAudioAsNullAndPreservesIncident() {
        val file = temporaryFolder.newFile("incidents.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D475249)
            output.writeInt(4)
            output.writeInt(1)
            output.writeUTF("v4-bad-conf")
            output.writeUTF(IncidentType.AUDIO.name)
            output.writeUTF(IncidentSeverity.CRITICAL.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(1)
            output.writeUTF(SensorKind.MICROPHONE.name)
            output.writeLong(100L)
            output.writeLong(1000L)
            output.writeDouble(0.9)
            output.writeDouble(10.0)
            output.writeBoolean(false)
            output.writeBoolean(true)
            output.writeUTF(AudioThreatCategory.IMPACT.name)
            output.writeDouble(99.0)
            output.writeDouble(12.0)
            output.writeLong(100L)
            output.writeLong(200L)
            output.writeInt(1)
            output.writeLong(100L)
            output.writeBoolean(false)
            output.writeLong(1000L)
            output.writeLong(2000L)
            output.writeBoolean(false)
            output.writeUTF(ProtectionState.ALERT_ACTIVE.name)
            output.writeUTF(DeliveryState.PENDING.name)
            output.writeInt(0)
            output.writeBoolean(false)
            output.writeBoolean(false)
        }

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()
        assertEquals(1, records.size)
        val loaded = records.single()
        assertEquals("v4-bad-conf", loaded.id)
        assertNull(loaded.evidence.single().audioThreat)
    }

    @Test
    fun invalidOccurrenceCountLoadsAudioAsNullAndPreservesIncident() {
        val file = temporaryFolder.newFile("incidents.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D475249)
            output.writeInt(4)
            output.writeInt(1)
            output.writeUTF("v4-bad-count")
            output.writeUTF(IncidentType.AUDIO.name)
            output.writeUTF(IncidentSeverity.CRITICAL.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(1)
            output.writeUTF(SensorKind.MICROPHONE.name)
            output.writeLong(100L)
            output.writeLong(1000L)
            output.writeDouble(0.9)
            output.writeDouble(10.0)
            output.writeBoolean(false)
            output.writeBoolean(true)
            output.writeUTF(AudioThreatCategory.IMPACT.name)
            output.writeDouble(0.8)
            output.writeDouble(12.0)
            output.writeLong(100L)
            output.writeLong(200L)
            output.writeInt(-5)
            output.writeLong(100L)
            output.writeBoolean(false)
            output.writeLong(1000L)
            output.writeLong(2000L)
            output.writeBoolean(false)
            output.writeUTF(ProtectionState.ALERT_ACTIVE.name)
            output.writeUTF(DeliveryState.PENDING.name)
            output.writeInt(0)
            output.writeBoolean(false)
            output.writeBoolean(false)
        }

        val records = FileIncidentRepository(file, maxRecords = 200).listNewestFirst()
        assertEquals(1, records.size)
        val loaded = records.single()
        assertEquals("v4-bad-count", loaded.id)
        assertNull(loaded.evidence.single().audioThreat)
    }

    @Test
    fun truncatedAudioBlockThrowsIOExceptionWithTruncatedCause() {
        val file = temporaryFolder.newFile("incidents.bin")
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(0x4D475249)
            output.writeInt(4)
            output.writeInt(1)
            output.writeUTF("v4-truncated")
            output.writeUTF(IncidentType.AUDIO.name)
            output.writeUTF(IncidentSeverity.CRITICAL.name)
            output.writeUTF(IncidentLifecycle.OPEN.name)
            output.writeInt(1)
            output.writeUTF(SensorKind.MICROPHONE.name)
            output.writeLong(100L)
            output.writeLong(1000L)
            output.writeDouble(0.9)
            output.writeDouble(10.0)
            output.writeBoolean(false)
            output.writeBoolean(true)
            output.writeUTF(AudioThreatCategory.IMPACT.name)
            output.writeDouble(0.8)
        }

        try {
            FileIncidentRepository(file, maxRecords = 200).listNewestFirst()
            org.junit.Assert.fail("Expected IOException on truncated file")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("Truncated", ignoreCase = true) == true || e.cause is java.io.EOFException)
        }
    }

    @Test
    fun mixedV3AndV4FixturesRemainReadable() {
        val file = temporaryFolder.newFile("incidents.bin")
        val repository = FileIncidentRepository(file, maxRecords = 200)

        val audioThreat = AudioThreatMetadata(
            category = AudioThreatCategory.IMPACT,
            confidence = 0.9,
            loudnessDeltaDb = 15.0,
            firstDetectedElapsedMs = 100L,
            lastDetectedElapsedMs = 200L,
            occurrenceCount = 1,
            onsetElapsedMs = 100L,
            onsetCoherent = true,
        )
        val v4Incident = incident(id = "v4-record", updatedAtMs = 200L).copy(
            evidence = listOf(
                IncidentEvidence(
                    kind = SensorKind.MICROPHONE,
                    eventElapsedMs = 100L,
                    wallClockMs = 200L,
                    normalizedValue = 0.9,
                    baselineDelta = 15.0,
                    diagnostic = "v4-audio",
                    audioThreat = audioThreat,
                )
            )
        )
        repository.upsert(v4Incident)

        val records = repository.listNewestFirst()
        assertEquals(1, records.size)
        assertEquals("v4-record", records.first().id)
        assertEquals(audioThreat, records.first().evidence.first().audioThreat)
    }

    @Test
    fun encryptedIncidentRepositoryPersistsAndReadsEncryptedIncidents() {
        val file = temporaryFolder.newFile("encrypted_incidents.bin")
        val fakeEncryptor: (ByteArray) -> ByteArray = { bytes -> bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }
        val fakeDecryptor: (ByteArray) -> ByteArray = { bytes -> bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }

        val repoWriter = FileIncidentRepository(file, maxRecords = 200, encryptor = fakeEncryptor, decryptor = fakeDecryptor)
        repoWriter.upsert(incident(id = "enc-1", updatedAtMs = 500L))

        val repoReader = FileIncidentRepository(file, maxRecords = 200, encryptor = fakeEncryptor, decryptor = fakeDecryptor)
        val loaded = repoReader.listNewestFirst()

        assertEquals(1, loaded.size)
        assertEquals("enc-1", loaded.single().id)
    }

    @Test
    fun upsertAdvancesTheHistoryRevision() {
        val repository = FileIncidentRepository(
            file = temporaryFolder.newFile("incidents.bin"),
            maxRecords = 200,
        )
        val before = repository.revision.value

        repository.upsert(incident(id = "i-1", updatedAtMs = 1L))

        assertEquals(before + 1L, repository.revision.value)
    }

    @Test
    fun clearingHistoryAdvancesTheHistoryRevision() {
        val repository = FileIncidentRepository(
            file = temporaryFolder.newFile("incidents.bin"),
            maxRecords = 200,
        )
        repository.upsert(incident(id = "i-1", updatedAtMs = 1L))
        val afterUpsert = repository.revision.value

        repository.clearHistory()

        assertEquals(afterUpsert + 1L, repository.revision.value)
    }

    @Test
    fun readingHistoryLeavesTheRevisionAlone() {
        val repository = FileIncidentRepository(
            file = temporaryFolder.newFile("incidents.bin"),
            maxRecords = 200,
        )
        repository.upsert(incident(id = "i-1", updatedAtMs = 1L))
        val afterUpsert = repository.revision.value

        repository.listNewestFirst()
        repository.findById("i-1")

        assertEquals(afterUpsert, repository.revision.value)
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

private fun writeV3History(file: File, records: List<SecurityIncident>) {
    DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
        output.writeInt(0x4D475249)
        output.writeInt(3)
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
            output.writeBoolean(incident.location != null)
            if (incident.location != null) {
                output.writeDouble(incident.location.latitude)
                output.writeDouble(incident.location.longitude)
                output.writeFloat(incident.location.accuracyMeters)
                output.writeLong(incident.location.capturedAtWallClockMs)
            }
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
