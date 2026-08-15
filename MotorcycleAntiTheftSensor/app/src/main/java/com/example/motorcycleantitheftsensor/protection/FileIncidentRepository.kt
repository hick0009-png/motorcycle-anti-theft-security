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
) : IncidentRepository {
    private val records by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readRecords().associateByTo(linkedMapOf()) { incident -> incident.id }
    }

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    @Synchronized
    override fun upsert(incident: SecurityIncident) {
        records[incident.id] = incident
        val retained = records.values
            .sortedBy { item -> item.updatedAtMs }
            .takeLast(maxRecords)
        records.clear()
        retained.associateByTo(records) { item -> item.id }
        persist(records.values.toList())
    }

    @Synchronized
    override fun findById(id: String): SecurityIncident? = records[id]

    @Synchronized
    override fun listNewestFirst(): List<SecurityIncident> = records.values
        .sortedByDescending { incident -> incident.updatedAtMs }

    @Synchronized
    override fun clearHistory() {
        records.clear()
        persist(emptyList())
    }

    private fun readRecords(): List<SecurityIncident> {
        val backup = backupFile()
        val source = when {
            file.exists() && file.length() > 0L -> file
            backup.exists() && backup.length() > 0L -> backup
            else -> return emptyList()
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                val magic = input.readInt()
                if (magic != FILE_MAGIC) throw IOException("Unsupported incident file")
                val version = input.readInt()
                val recordCount = input.readInt()
                when (version) {
                    LEGACY_FILE_VERSION -> List(recordCount) { readLegacyV1Incident(input) }.mapNotNull { it }
                    LEGACY_V2_FILE_VERSION -> List(recordCount) { readLegacyV2Incident(input) }
                    FILE_VERSION -> List(recordCount) { readIncident(input) }
                    else -> throw IOException("Unsupported incident version: $version")
                }
            }
        } catch (error: EOFException) {
            throw IOException("Truncated incident file", error)
        }
    }

    private fun persist(incidents: List<SecurityIncident>) {
        file.parentFile?.mkdirs()
        val temporary = temporaryFile()
        FileOutputStream(temporary).use { outputStream ->
            DataOutputStream(BufferedOutputStream(outputStream)).use { output ->
                output.writeInt(FILE_MAGIC)
                output.writeInt(FILE_VERSION)
                output.writeInt(incidents.size)
                incidents.forEach { incident -> writeIncident(output, incident) }
                output.flush()
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
        val incident = readIncidentBody(input, id, type, null)
        return incident.takeIf { legacyOrigin == REAL_ORIGIN_TOKEN }
    }

    private fun readLegacyV2Incident(input: DataInputStream): SecurityIncident = readIncidentBody(
        input = input,
        id = input.readUTF(),
        type = IncidentType.valueOf(input.readUTF()),
        location = null,
    )

    private fun readIncident(input: DataInputStream): SecurityIncident {
        val id = input.readUTF()
        val type = IncidentType.valueOf(input.readUTF())
        val base = readIncidentBody(input, id, type, null)
        val hasLocation = input.readBoolean()
        val location = if (hasLocation) {
            val lat = input.readDouble()
            val lon = input.readDouble()
            val accuracy = input.readFloat()
            val capturedAt = input.readLong()
            if (lat.isFinite() && lat in -90.0..90.0 &&
                lon.isFinite() && lon in -180.0..180.0 &&
                accuracy.isFinite() && accuracy >= 0f && accuracy <= 1500f &&
                capturedAt >= 0L
            ) {
                IncidentLocation(lat, lon, accuracy, capturedAt)
            } else {
                null
            }
        } else {
            null
        }
        return base.copy(location = location)
    }

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
            IncidentEvidence(
                kind = SensorKind.valueOf(input.readUTF()),
                eventElapsedMs = input.readLong(),
                wallClockMs = input.readLong(),
                normalizedValue = input.readDouble(),
                baselineDelta = input.readDouble(),
                diagnostic = input.readNullableString(),
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
        const val LEGACY_FILE_VERSION = 1
        const val LEGACY_V2_FILE_VERSION = 2
        const val FILE_VERSION = 3
        const val REAL_ORIGIN_TOKEN = "REAL"
    }
}
