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

/**
 * Carries the delivery record already on file onto this copy of the same incident.
 *
 * The engine keeps its own copy of an open incident and never learns what became of the
 * message about it: every update it emits still says PENDING with no attempts, because that
 * is how the incident was born. Persisting one of those directly — which the progress paths
 * did — overwrote the SENT or FAILED the delivery had written moments earlier.
 *
 * The record is the only place that remembers whether the owner was told. Erasing it left an
 * open incident's history unable to answer that at all, and left a failed alert invisible to
 * the sweep that exists to send it again: one progress update a second later, and the FAILED
 * that would have been picked up became a PENDING that no longer qualified.
 */
fun SecurityIncident.withDeliveryRecordOf(previous: SecurityIncident?): SecurityIncident {
    if (previous == null) return this
    if (previous.deliveryState == DeliveryState.PENDING && previous.deliveryAttempts.isEmpty()) {
        return this
    }
    return copy(
        deliveryState = previous.deliveryState,
        deliveryAttempts = previous.deliveryAttempts,
    )
}

fun interface IncidentIdGenerator {
    fun nextId(): String
}

sealed interface IncidentUpdate {
    data object Ignored : IncidentUpdate

    /**
     * [supersededIncident] carries an unrelated incident that was still open when this
     * one had to take the engine's single active slot. It is already CLOSED and was
     * already notified when it opened, so it must be persisted but never re-delivered.
     */
    data class Opened(
        val incident: SecurityIncident,
        val supersededIncident: SecurityIncident? = null,
    ) : IncidentUpdate

    /**
     * [ownerVisibleConditionChange] marks an update whose meaning changed for the
     * owner while the incident stayed open — a Power Guard partial recovery, for
     * example. Such an update owns its own message instead of being coalesced into
     * the silent persist/progress path.
     */
    data class Updated(
        val incident: SecurityIncident,
        val ownerVisibleConditionChange: Boolean = false,
    ) : IncidentUpdate

    data class Escalated(val incident: SecurityIncident) : IncidentUpdate

    data class Closed(val incident: SecurityIncident) : IncidentUpdate
}
