package com.example.motorcycleantitheftsensor.protection

enum class IncidentType {
    VIBRATION,
    TAMPER,
    POWER,
    THERMAL,
    AUDIO,
    ENTRY_DOOR,
}

data class IncidentEvidence(
    val kind: SensorKind,
    val source: SensorSource? = null,
    val capability: SensorCapability? = null,
    val role: SensorRole? = null,
    val unit: SensorUnit? = null,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val diagnostic: String?,
    val audioThreat: AudioThreatMetadata? = null,
)

fun SensorObservation.toEvidence(): IncidentEvidence = IncidentEvidence(
    kind = kind,
    source = source,
    capability = capability,
    role = role,
    unit = unit,
    eventElapsedMs = eventElapsedMs,
    wallClockMs = wallClockMs,
    normalizedValue = normalizedValue,
    baselineDelta = baselineDelta,
    diagnostic = diagnostic,
    audioThreat = audioThreat,
)

data class IncidentLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtWallClockMs: Long,
)

enum class DeliveryChannel {
    LOCAL_STORAGE,
    TELEGRAM,
    SMS,
}

data class DeliveryAttempt(
    val channel: DeliveryChannel,
    val state: DeliveryState,
    val attemptedAtMs: Long,
    val detail: String? = null,
)

data class SecurityIncident(
    val id: String,
    val type: IncidentType,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val evidence: List<IncidentEvidence>,
    val openedAtMs: Long,
    val updatedAtMs: Long,
    val closedAtMs: Long?,
    val protectionState: ProtectionState,
    val deliveryState: DeliveryState,
    val deliveryAttempts: List<DeliveryAttempt> = emptyList(),
    val closeReason: String? = null,
    val location: IncidentLocation? = null,
)

fun interface IncidentIdGenerator {
    fun nextId(): String
}

sealed interface IncidentUpdate {
    data object Ignored : IncidentUpdate

    data class Opened(val incident: SecurityIncident) : IncidentUpdate

    data class Updated(val incident: SecurityIncident) : IncidentUpdate

    data class Escalated(val incident: SecurityIncident) : IncidentUpdate

    data class Closed(val incident: SecurityIncident) : IncidentUpdate
}
