package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

class IncidentEngine(
    private val idGenerator: IncidentIdGenerator,
    private val correlationWindowMs: Long,
) {
    private data class ActiveIncident(
        val incident: SecurityIncident,
        val lastEvidenceElapsedMs: Long,
    )

    private val activeBySource = mutableMapOf<IncidentSource, ActiveIncident>()
    private val lightPrecursorBySource = mutableMapOf<IncidentSource, IncidentEvidence>()

    @Synchronized
    fun accept(
        observation: SensorObservation,
        protectionState: ProtectionState,
    ): IncidentUpdate {
        if (observation.source == IncidentSource.REAL && protectionState !in ACTIVE_PROTECTION_STATES) {
            return IncidentUpdate.Ignored
        }

        val evidence = observation.toEvidence()
        val active = activeBySource[observation.source]
        if (active == null) {
            if (observation.kind == SensorKind.LIGHT) {
                lightPrecursorBySource[observation.source] = evidence
                return IncidentUpdate.Ignored
            }
            val classification = initialClassification(observation) ?: return IncidentUpdate.Ignored
            val lightPrecursor = lightPrecursorBySource.remove(observation.source)
                ?.takeIf { light ->
                    observation.kind == SensorKind.VIBRATION &&
                        abs(observation.eventElapsedMs - light.eventElapsedMs) <= correlationWindowMs
                }
            val openingClassification = if (lightPrecursor != null) {
                Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
            } else {
                classification
            }
            val openingEvidence = listOfNotNull(lightPrecursor, evidence)
            val incident = SecurityIncident(
                id = idGenerator.nextId(),
                type = openingClassification.type,
                source = observation.source,
                severity = openingClassification.severity,
                lifecycle = IncidentLifecycle.OPEN,
                evidence = openingEvidence,
                openedAtMs = openingEvidence.minOf(IncidentEvidence::wallClockMs),
                updatedAtMs = observation.wallClockMs,
                closedAtMs = null,
                protectionState = protectionState,
                deliveryState = DeliveryState.PENDING,
            )
            activeBySource[observation.source] = ActiveIncident(incident, observation.eventElapsedMs)
            return IncidentUpdate.Opened(incident)
        }

        val evidenceList = active.incident.evidence + evidence
        val classification = updatedClassification(active.incident, observation, evidenceList)
        val updated = active.incident.copy(
            type = classification.type,
            severity = classification.severity,
            evidence = evidenceList,
            updatedAtMs = observation.wallClockMs,
            protectionState = protectionState,
        )
        activeBySource[observation.source] = ActiveIncident(updated, observation.eventElapsedMs)

        return if (updated.severity.ordinal > active.incident.severity.ordinal) {
            IncidentUpdate.Escalated(updated)
        } else {
            IncidentUpdate.Updated(updated)
        }
    }

    @Synchronized
    fun close(
        nowMs: Long,
        reason: String,
        source: IncidentSource = IncidentSource.REAL,
    ): IncidentUpdate.Closed? {
        val active = activeBySource.remove(source) ?: return null
        val closed = active.incident.copy(
            lifecycle = IncidentLifecycle.CLOSED,
            updatedAtMs = nowMs,
            closedAtMs = nowMs,
            closeReason = reason,
        )
        return IncidentUpdate.Closed(closed)
    }

    @Synchronized
    fun closeIfQuiet(
        nowElapsedMs: Long,
        quietWindowMs: Long,
        source: IncidentSource = IncidentSource.REAL,
    ): IncidentUpdate.Closed? {
        val active = activeBySource[source] ?: return null
        val quietDurationMs = nowElapsedMs - active.lastEvidenceElapsedMs
        if (quietDurationMs < quietWindowMs) return null
        val closeWallClockMs = active.incident.updatedAtMs + quietDurationMs
        return close(closeWallClockMs, "quiet window elapsed", source)
    }

    @Synchronized
    fun interrupted(
        nowMs: Long,
        source: IncidentSource = IncidentSource.REAL,
    ): IncidentUpdate.Closed? {
        val active = activeBySource.remove(source) ?: return null
        val interrupted = active.incident.copy(
            lifecycle = IncidentLifecycle.INTERRUPTED,
            updatedAtMs = nowMs,
            closedAtMs = nowMs,
            closeReason = "process interrupted",
        )
        return IncidentUpdate.Closed(interrupted)
    }

    private fun initialClassification(observation: SensorObservation): Classification? = when {
        observation.kind == SensorKind.VIBRATION -> Classification(
            IncidentType.VIBRATION,
            IncidentSeverity.WARNING,
        )

        observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "charger_disconnected" ->
            Classification(IncidentType.POWER, IncidentSeverity.CRITICAL)

        observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "temperature_celsius" ->
            Classification(IncidentType.THERMAL, IncidentSeverity.WARNING)

        else -> null
    }

    private fun updatedClassification(
        current: SecurityIncident,
        observation: SensorObservation,
        evidence: List<IncidentEvidence>,
    ): Classification {
        if (observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "charger_disconnected") {
            return Classification(IncidentType.POWER, IncidentSeverity.CRITICAL)
        }

        val vibration = evidence.lastOrNull { item -> item.kind == SensorKind.VIBRATION }
        val light = evidence.lastOrNull { item -> item.kind == SensorKind.LIGHT }
        if (
            vibration != null &&
            light != null &&
            abs(vibration.eventElapsedMs - light.eventElapsedMs) <= correlationWindowMs
        ) {
            return Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
        }

        return Classification(current.type, current.severity)
    }

    private data class Classification(
        val type: IncidentType,
        val severity: IncidentSeverity,
    )

    private fun SensorObservation.toEvidence(): IncidentEvidence = IncidentEvidence(
        kind = kind,
        eventElapsedMs = eventElapsedMs,
        wallClockMs = wallClockMs,
        normalizedValue = normalizedValue,
        baselineDelta = baselineDelta,
        diagnostic = diagnostic,
    )

    private companion object {
        val ACTIVE_PROTECTION_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
    }
}
