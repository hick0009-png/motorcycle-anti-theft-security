package com.example.motorcycleantitheftsensor.protection

enum class IncidentType {
    VIBRATION,
    TAMPER,
    POWER,
    THERMAL,
    AUDIO,
}

data class IncidentEvidence(
    val kind: SensorKind,
    val eventElapsedMs: Long,
    val wallClockMs: Long,
    val normalizedValue: Double,
    val baselineDelta: Double,
    val diagnostic: String?,
)

data class SecurityIncident(
    val id: String,
    val type: IncidentType,
    val source: IncidentSource,
    val severity: IncidentSeverity,
    val lifecycle: IncidentLifecycle,
    val evidence: List<IncidentEvidence>,
    val openedAtMs: Long,
    val updatedAtMs: Long,
    val closedAtMs: Long?,
    val protectionState: ProtectionState,
    val deliveryState: DeliveryState,
    val closeReason: String? = null,
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
