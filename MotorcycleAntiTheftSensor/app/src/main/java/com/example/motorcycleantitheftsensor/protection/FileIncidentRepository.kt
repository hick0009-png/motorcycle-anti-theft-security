package com.example.motorcycleantitheftsensor.protection

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class FileIncidentRepository(
    private val file: File,
    private val maxRecords: Int,
    private val encryptor: ((ByteArray) -> ByteArray)? = null,
    private val decryptor: ((ByteArray) -> ByteArray)? = null,
) : IncidentRepository {

    constructor(
        file: File,
        maxRecords: Int,
        secureKeyManager: com.example.motorcycleantitheftsensor.security.SecureKeyManager,
    ) : this(
        file = file,
        maxRecords = maxRecords,
        encryptor = secureKeyManager::encrypt,
        decryptor = secureKeyManager::decrypt,
    )

    private val records by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readRecords().associateByTo(linkedMapOf()) { incident -> incident.id }
    }

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    @Synchronized
    fun upsert(incident: SecurityIncident, syncImmediate: Boolean) {
        val updatedMap = linkedMapOf<String, SecurityIncident>()
        updatedMap.putAll(records)
        updatedMap[incident.id] = incident
        val retained = updatedMap.values
            .sortedBy { item -> item.updatedAtMs }
            .takeLast(maxRecords)
        persist(retained, syncImmediate = syncImmediate)
        records.clear()
        retained.associateByTo(records) { item -> item.id }
    }

    @Synchronized
    override fun upsert(incident: SecurityIncident) {
        val isCritical = incident.lifecycle == IncidentLifecycle.OPEN ||
            incident.lifecycle == IncidentLifecycle.CLOSED ||
            incident.lifecycle == IncidentLifecycle.INTERRUPTED ||
            incident.deliveryState == DeliveryState.PENDING
        upsert(incident, syncImmediate = isCritical)
    }

    @Synchronized
    override fun findById(id: String): SecurityIncident? = records[id]

    @Synchronized
    override fun listNewestFirst(): List<SecurityIncident> = records.values
        .sortedByDescending { incident -> incident.updatedAtMs }

    @Synchronized
    override fun clearHistory() {
        persist(emptyList())
        records.clear()
    }

    private fun readRecords(): List<SecurityIncident> {
        val backup = backupFile()
        if (file.exists() && file.length() > 0L) {
            try {
                return readFromFile(file)
            } catch (error: Exception) {
                if (backup.exists() && backup.length() > 0L) {
                    try {
                        return readFromFile(backup)
                    } catch (_: Exception) {}
                }
                if (error is IOException) throw error
                throw IOException("Corrupted incident file", error)
            }
        }
        if (backup.exists() && backup.length() > 0L) {
            return readFromFile(backup)
        }
        return emptyList()
    }

    private fun readFromFile(source: File): List<SecurityIncident> {
        return try {
            val rawBytes = source.readBytes()
            if (rawBytes.size < 4) throw IOException("Truncated incident file")

            val firstMagic = DataInputStream(java.io.ByteArrayInputStream(rawBytes)).readInt()
            val bytesToRead = if (firstMagic == FILE_MAGIC_ENCRYPTED) {
                if (decryptor == null) throw IOException("Encrypted incident file requires decryptor")
                val ciphertext = rawBytes.copyOfRange(4, rawBytes.size)
                decryptor.invoke(ciphertext)
            } else {
                rawBytes
            }

            DataInputStream(BufferedInputStream(java.io.ByteArrayInputStream(bytesToRead))).use { input ->
                val magic = input.readInt()
                if (magic != FILE_MAGIC) throw IOException("Unsupported incident file")
                val version = input.readInt()
                val recordCount = input.readInt()
                when (version) {
                    LEGACY_FILE_VERSION -> List(recordCount) { readLegacyV1Incident(input) }.mapNotNull { it }
                    LEGACY_V2_FILE_VERSION -> List(recordCount) { readLegacyV2Incident(input) }
                    LEGACY_V3_FILE_VERSION -> List(recordCount) { readLegacyV3Incident(input) }
                    FILE_VERSION -> List(recordCount) { readIncident(input) }
                    else -> throw IOException("Unsupported incident version: $version")
                }
            }
        } catch (error: EOFException) {
            throw IOException("Truncated incident file", error)
        }
    }

    private fun persist(incidents: List<SecurityIncident>, syncImmediate: Boolean = true) {
        file.parentFile?.mkdirs()
        val temporary = temporaryFile()

        val baos = java.io.ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(baos)).use { output ->
            output.writeInt(FILE_MAGIC)
            output.writeInt(FILE_VERSION)
            output.writeInt(incidents.size)
            incidents.forEach { incident -> writeIncident(output, incident) }
            output.flush()
        }
        val plainBytes = baos.toByteArray()
        val dataToWrite = if (encryptor != null) {
            val ciphertext = encryptor.invoke(plainBytes)
            val encBaos = java.io.ByteArrayOutputStream()
            DataOutputStream(BufferedOutputStream(encBaos)).use { encOut ->
                encOut.writeInt(FILE_MAGIC_ENCRYPTED)
                encOut.write(ciphertext)
                encOut.flush()
            }
            encBaos.toByteArray()
        } else {
            plainBytes
        }

        FileOutputStream(temporary).use { outputStream ->
            outputStream.write(dataToWrite)
            outputStream.flush()
            if (syncImmediate) {
                outputStream.fd.sync()
            }
        }

        val backup = backupFile()
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IOException("Unable to replace incident backup")
        }
        if (file.exists() && !file.renameTo(backup)) {
            temporary.delete()
            throw IOException("Unable to back up incident file")
        }
        if (!temporary.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            throw IOException("Unable to publish incident file")
        }
        if (backup.exists()) backup.delete()
    }

    private fun writeIncident(
        output: DataOutputStream,
        incident: SecurityIncident,
    ) {
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
            output.writeNullableString(evidence.diagnostic)
            val threat = evidence.audioThreat
            if (threat != null) {
                output.writeBoolean(true)
                output.writeUTF(threat.category.name)
                output.writeDouble(threat.confidence)
                output.writeDouble(threat.loudnessDeltaDb)
                output.writeLong(threat.firstDetectedElapsedMs)
                output.writeLong(threat.lastDetectedElapsedMs)
                output.writeInt(threat.occurrenceCount)
                output.writeLong(threat.onsetElapsedMs)
                output.writeBoolean(threat.onsetCoherent)
            } else {
                output.writeBoolean(false)
            }
        }
        output.writeLong(incident.openedAtMs)
        output.writeLong(incident.updatedAtMs)
        output.writeNullableLong(incident.closedAtMs)
        output.writeUTF(incident.protectionState.name)
        output.writeUTF(incident.deliveryState.name)
        output.writeInt(incident.deliveryAttempts.size)
        incident.deliveryAttempts.forEach { attempt ->
            output.writeUTF(attempt.channel.name)
            output.writeUTF(attempt.state.name)
            output.writeLong(attempt.attemptedAtMs)
            output.writeNullableString(attempt.detail)
        }
        output.writeNullableString(incident.closeReason)
        if (incident.location != null) {
            output.writeBoolean(true)
            output.writeDouble(incident.location.latitude)
            output.writeDouble(incident.location.longitude)
            output.writeFloat(incident.location.accuracyMeters)
            output.writeLong(incident.location.capturedAtWallClockMs)
        } else {
            output.writeBoolean(false)
        }
    }

    private fun readLegacyV1Incident(input: DataInputStream): SecurityIncident? {
        val id = input.readUTF()
        val type = IncidentType.valueOf(input.readUTF())
        val legacyOrigin = input.readUTF()
        val incident = readLegacyIncidentBody(input, id, type, null)
        return incident.takeIf { legacyOrigin == REAL_ORIGIN_TOKEN }
    }

    private fun readLegacyV2Incident(input: DataInputStream): SecurityIncident = readLegacyIncidentBody(
        input = input,
        id = input.readUTF(),
        type = IncidentType.valueOf(input.readUTF()),
        location = null,
    )

    private fun readLegacyV3Incident(input: DataInputStream): SecurityIncident {
        val id = input.readUTF()
        val type = IncidentType.valueOf(input.readUTF())
        val base = readLegacyIncidentBody(input, id, type, null)
        val hasLocation = input.readBoolean()
        val location = if (hasLocation) readLocation(input) else null
        return base.copy(location = location)
    }

    private fun readIncident(input: DataInputStream): SecurityIncident {
        val id = input.readUTF()
        val type = IncidentType.valueOf(input.readUTF())
        val base = readIncidentBody(input, id, type, null)
        val hasLocation = input.readBoolean()
        val location = if (hasLocation) readLocation(input) else null
        return base.copy(location = location)
    }

    private fun readLocation(input: DataInputStream): IncidentLocation? {
        val lat = input.readDouble()
        val lon = input.readDouble()
        val accuracy = input.readFloat()
        val capturedAt = input.readLong()
        return if (lat.isFinite() && lat in -90.0..90.0 &&
            lon.isFinite() && lon in -180.0..180.0 &&
            accuracy.isFinite() && accuracy >= 0f && accuracy <= 1500f &&
            capturedAt >= 0L
        ) {
            IncidentLocation(lat, lon, accuracy, capturedAt)
        } else {
            null
        }
    }

    private fun readLegacyIncidentBody(
        input: DataInputStream,
        id: String,
        type: IncidentType,
        location: IncidentLocation?,
    ): SecurityIncident = SecurityIncident(
        id = id,
        type = type,
        severity = IncidentSeverity.valueOf(input.readUTF()),
        lifecycle = IncidentLifecycle.valueOf(input.readUTF()),
        evidence = List(input.readInt()) {
            IncidentEvidence(
                kind = SensorKind.valueOf(input.readUTF()),
                eventElapsedMs = input.readLong(),
                wallClockMs = input.readLong(),
                normalizedValue = input.readDouble(),
                baselineDelta = input.readDouble(),
                diagnostic = input.readNullableString(),
                audioThreat = null,
            )
        },
        openedAtMs = input.readLong(),
        updatedAtMs = input.readLong(),
        closedAtMs = input.readNullableLong(),
        protectionState = ProtectionState.valueOf(input.readUTF()),
        deliveryState = DeliveryState.valueOf(input.readUTF()),
        deliveryAttempts = List(input.readInt()) {
            DeliveryAttempt(
                channel = DeliveryChannel.valueOf(input.readUTF()),
                state = DeliveryState.valueOf(input.readUTF()),
                attemptedAtMs = input.readLong(),
                detail = input.readNullableString(),
            )
        },
        closeReason = input.readNullableString(),
        location = location,
    )

    private fun readIncidentBody(
        input: DataInputStream,
        id: String,
        type: IncidentType,
        location: IncidentLocation?,
    ): SecurityIncident = SecurityIncident(
        id = id,
        type = type,
        severity = IncidentSeverity.valueOf(input.readUTF()),
        lifecycle = IncidentLifecycle.valueOf(input.readUTF()),
        evidence = List(input.readInt()) {
            val kind = SensorKind.valueOf(input.readUTF())
            val eventElapsedMs = input.readLong()
            val wallClockMs = input.readLong()
            val normalizedValue = input.readDouble()
            val baselineDelta = input.readDouble()
            val diagnostic = input.readNullableString()
            val hasAudioThreat = input.readBoolean()
            val audioThreat = if (hasAudioThreat) {
                val categoryToken = input.readUTF()
                val confidence = input.readDouble()
                val deltaDb = input.readDouble()
                val firstDetected = input.readLong()
                val lastDetected = input.readLong()
                val count = input.readInt()
                val onset = input.readLong()
                val coherent = input.readBoolean()

                runCatching {
                    AudioThreatMetadata(
                        category = AudioThreatCategory.valueOf(categoryToken),
                        confidence = confidence,
                        loudnessDeltaDb = deltaDb,
                        firstDetectedElapsedMs = firstDetected,
                        lastDetectedElapsedMs = lastDetected,
                        occurrenceCount = count,
                        onsetElapsedMs = onset,
                        onsetCoherent = coherent,
                    )
                }.getOrNull()
            } else {
                null
            }
            IncidentEvidence(
                kind = kind,
                eventElapsedMs = eventElapsedMs,
                wallClockMs = wallClockMs,
                normalizedValue = normalizedValue,
                baselineDelta = baselineDelta,
                diagnostic = diagnostic,
                audioThreat = audioThreat,
            )
        },
        openedAtMs = input.readLong(),
        updatedAtMs = input.readLong(),
        closedAtMs = input.readNullableLong(),
        protectionState = ProtectionState.valueOf(input.readUTF()),
        deliveryState = DeliveryState.valueOf(input.readUTF()),
        deliveryAttempts = List(input.readInt()) {
            DeliveryAttempt(
                channel = DeliveryChannel.valueOf(input.readUTF()),
                state = DeliveryState.valueOf(input.readUTF()),
                attemptedAtMs = input.readLong(),
                detail = input.readNullableString(),
            )
        },
        closeReason = input.readNullableString(),
        location = location,
    )

    private fun temporaryFile(): File = File(file.parentFile, "${file.name}.tmp")

    private fun backupFile(): File = File(file.parentFile, "${file.name}.bak")

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private companion object {
        const val FILE_MAGIC = 0x4D475249
        const val FILE_MAGIC_ENCRYPTED = 0x4D475245
        const val LEGACY_FILE_VERSION = 1
        const val LEGACY_V2_FILE_VERSION = 2
        const val LEGACY_V3_FILE_VERSION = 3
        const val FILE_VERSION = 4
        const val REAL_ORIGIN_TOKEN = "REAL"
    }
}
