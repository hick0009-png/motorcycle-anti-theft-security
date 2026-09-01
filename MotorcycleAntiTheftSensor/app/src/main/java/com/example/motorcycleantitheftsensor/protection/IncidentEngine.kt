package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs

class IncidentEngine(
    private val idGenerator: IncidentIdGenerator,
    private val correlationWindowMs: Long = AUDIO_CORRELATION_WINDOW_MS,
    restoredActiveIncident: SecurityIncident? = null,
    restoredAtElapsedMs: Long = 0L,
) {
    private data class ActiveIncident(
        val incident: SecurityIncident,
        val lastEvidenceElapsedMs: Long,
    )

    private var activeIncident: ActiveIncident? = restoredActiveIncident
        ?.takeIf { incident ->
            incident.type == IncidentType.POWER && incident.lifecycle == IncidentLifecycle.OPEN
        }
        ?.let { incident ->
            ActiveIncident(
                incident = incident,
                lastEvidenceElapsedMs = restoredAtElapsedMs.coerceAtLeast(0L),
            )
        }
    val hasActiveIncident: Boolean
        @Synchronized get() = activeIncident != null
    private var lightPrecursor: IncidentEvidence? = null
    private val audioPrecursors = mutableListOf<IncidentEvidence>()
    private var vibrationPrecursor: IncidentEvidence? = null
    private var chargerPrecursor: IncidentEvidence? = null
    private var locationPrecursor: IncidentEvidence? = null

    @Synchronized
    fun accept(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation? = null,
    ): IncidentUpdate {
        if (protectionState !in ACTIVE_PROTECTION_STATES) {
            clearAllPrecursors()
            return IncidentUpdate.Ignored
        }

        // Entry Guard verdicts arrive fully evaluated with typed diagnostics; they own
        // their episode lifecycle and never participate in precursor correlation.
        if (observation.diagnostic?.startsWith(ENTRY_DIAGNOSTIC_PREFIX) == true) {
            return acceptEntry(observation, protectionState, location)
        }

        // Power Guard verdicts arrive fully evaluated by the armed-session arbiter with
        // typed diagnostics; they own their episode lifecycle and never participate in
        // precursor correlation either.
        if (observation.diagnostic?.startsWith(POWER_DIAGNOSTIC_PREFIX) == true) {
            return acceptPower(observation, protectionState, location)
        }

        purgePrecursors(observation.eventElapsedMs)
        val evidence = observation.toEvidence()
        val active = activeIncident

        if (active == null) {
            if (observation.kind == SensorKind.LIGHT) {
                lightPrecursor = evidence
                return IncidentUpdate.Ignored
            }

            if (observation.kind == SensorKind.MICROPHONE) {
                val threat = observation.audioThreat ?: return IncidentUpdate.Ignored
                val matchedVibration = vibrationPrecursor?.takeIf {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }
                val matchedCharger = chargerPrecursor?.takeIf {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }
                val matchedLocation = locationPrecursor?.takeIf {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }

                val primaryPresent = hasPrimaryRole(observation, matchedVibration, matchedCharger, matchedLocation)

                if (matchedVibration != null && primaryPresent) {
                    vibrationPrecursor = null
                    val coherent = abs(matchedVibration.eventElapsedMs - observation.eventElapsedMs) <= 250L
                    val finalAudio = if (coherent) {
                        evidence.copy(audioThreat = threat.copy(onsetCoherent = true))
                    } else {
                        evidence
                    }
                    val classification = classifyAudioVibration(threat, isRepeated = threat.occurrenceCount >= 2)
                    val openingEvidence = listOf(matchedVibration, finalAudio)
                    return openIncident(classification, openingEvidence, observation, protectionState, location)
                }

                if (matchedCharger != null && primaryPresent) {
                    chargerPrecursor = null
                    val classification = Classification(IncidentType.POWER, IncidentSeverity.CRITICAL)
                    val openingEvidence = listOf(matchedCharger, evidence)
                    return openIncident(classification, openingEvidence, observation, protectionState, location)
                }

                if (matchedLocation != null && primaryPresent) {
                    locationPrecursor = null
                    val classification = Classification(IncidentType.AUDIO, IncidentSeverity.CRITICAL)
                    val openingEvidence = listOf(matchedLocation, evidence)
                    return openIncident(classification, openingEvidence, observation, protectionState, location)
                }

                recordAudioPrecursor(evidence)
                return IncidentUpdate.Ignored
            }

            if (observation.kind == SensorKind.VIBRATION) {
                val priorVibration = vibrationPrecursor?.takeIf {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs && it.source != observation.source
                }
                vibrationPrecursor = evidence
                val matchedAudio = audioPrecursors.lastOrNull {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }
                val priorLight = lightPrecursor?.takeIf {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }

                val primaryPresent = hasPrimaryRole(observation, matchedAudio, priorLight, priorVibration)
                if (!primaryPresent) {
                    return IncidentUpdate.Ignored
                }

                if (matchedAudio != null) {
                    audioPrecursors.remove(matchedAudio)
                    val threat = matchedAudio.audioThreat
                    val coherent = abs(observation.eventElapsedMs - matchedAudio.eventElapsedMs) <= 250L
                    val finalAudio = if (coherent && threat != null) {
                        matchedAudio.copy(audioThreat = threat.copy(onsetCoherent = true))
                    } else {
                        matchedAudio
                    }
                    val classification = if (priorLight != null) {
                        lightPrecursor = null
                        Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
                    } else if (threat != null) {
                        classifyAudioVibration(threat, isRepeated = threat.occurrenceCount >= 2)
                    } else {
                        Classification(IncidentType.VIBRATION, IncidentSeverity.WARNING)
                    }
                    val openingEvidence = listOfNotNull(priorLight, priorVibration, finalAudio, evidence)
                    return openIncident(classification, openingEvidence, observation, protectionState, location)
                }

                val openingClassification = if (priorLight != null) {
                    lightPrecursor = null
                    Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
                } else {
                    Classification(IncidentType.VIBRATION, IncidentSeverity.WARNING)
                }
                val openingEvidence = listOfNotNull(priorLight, priorVibration, evidence)
                return openIncident(openingClassification, openingEvidence, observation, protectionState, location)
            }

            if (observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == ProtectionDiagnostics.CHARGER_DISCONNECTED) {
                chargerPrecursor = evidence
                val matchedAudio = audioPrecursors.lastOrNull {
                    abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
                }
                val primaryPresent = hasPrimaryRole(observation, matchedAudio)
                if (!primaryPresent) {
                    return IncidentUpdate.Ignored
                }
                if (matchedAudio != null) {
                    audioPrecursors.remove(matchedAudio)
                }
                val classification = Classification(IncidentType.POWER, IncidentSeverity.CRITICAL)
                val openingEvidence = listOfNotNull(matchedAudio, evidence)
                return openIncident(classification, openingEvidence, observation, protectionState, location)
            }

            if (observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == "temperature_celsius") {
                if (!isPrimaryRole(observation.role)) {
                    return IncidentUpdate.Ignored
                }
                val classification = Classification(IncidentType.THERMAL, IncidentSeverity.WARNING)
                return openIncident(classification, listOf(evidence), observation, protectionState, location)
            }

            // Ordinary location observation does not open incident or act as confirmed movement
            return IncidentUpdate.Ignored
        }

        // Active incident is open
        if (observation.kind == SensorKind.MICROPHONE) {
            val matchedVibration = vibrationPrecursor?.takeIf {
                abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
            }
            val matchedCharger = chargerPrecursor?.takeIf {
                abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
            }
            val matchedLocation = locationPrecursor?.takeIf {
                abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
            }
            val hasRecentVibrationInEvidence = active.incident.evidence.any {
                it.kind == SensorKind.VIBRATION && abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
            }

            if (matchedVibration == null && matchedCharger == null && matchedLocation == null && !hasRecentVibrationInEvidence) {
                recordAudioPrecursor(evidence)
                return IncidentUpdate.Ignored
            }
        }

        val evidenceList = appendEvidence(active.incident.evidence, evidence)
        val classification = updatedClassification(active.incident, observation, evidenceList)
        val updated = active.incident.copy(
            type = classification.type,
            severity = classification.severity,
            evidence = evidenceList,
            updatedAtMs = observation.wallClockMs,
            protectionState = protectionState,
            location = location ?: active.incident.location,
        )
        activeIncident = ActiveIncident(updated, observation.eventElapsedMs)

        return if (updated.severity.ordinal > active.incident.severity.ordinal) {
            IncidentUpdate.Escalated(updated)
        } else {
            IncidentUpdate.Updated(updated)
        }
    }

    @Synchronized
    fun onConfirmedMovement(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation? = null,
    ): IncidentUpdate {
        if (protectionState !in ACTIVE_PROTECTION_STATES) {
            clearAllPrecursors()
            return IncidentUpdate.Ignored
        }
        if (observation.kind != SensorKind.LOCATION) return IncidentUpdate.Ignored

        purgePrecursors(observation.eventElapsedMs)
        val evidence = observation.toEvidence()
        val active = activeIncident

        if (active == null) {
            val matchedAudio = audioPrecursors.lastOrNull {
                abs(observation.eventElapsedMs - it.eventElapsedMs) <= correlationWindowMs
            }
            if (matchedAudio != null) {
                audioPrecursors.remove(matchedAudio)
                val classification = Classification(IncidentType.AUDIO, IncidentSeverity.CRITICAL)
                val openingEvidence = listOf(matchedAudio, evidence)
                return openIncident(classification, openingEvidence, observation, protectionState, location)
            }
            locationPrecursor = evidence
            return IncidentUpdate.Ignored
        } else {
            val evidenceList = appendEvidence(active.incident.evidence, evidence)
            val updated = active.incident.copy(
                severity = IncidentSeverity.CRITICAL,
                evidence = evidenceList,
                updatedAtMs = observation.wallClockMs,
                protectionState = protectionState,
                location = location ?: active.incident.location,
            )
            activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
            return if (updated.severity.ordinal > active.incident.severity.ordinal) {
                IncidentUpdate.Escalated(updated)
            } else {
                IncidentUpdate.Updated(updated)
            }
        }
    }

    @Synchronized
    fun clearPendingAudio() {
        clearAudioPrecursors()
    }

    @Synchronized
    fun close(
        nowMs: Long,
        reason: String,
    ): IncidentUpdate.Closed? {
        clearAllPrecursors()
        val active = activeIncident ?: return null
        activeIncident = null
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
    ): IncidentUpdate.Closed? {
        val active = activeIncident ?: return null
        val quietDurationMs = nowElapsedMs - active.lastEvidenceElapsedMs
        if (quietDurationMs < quietWindowMs) return null
        val closeWallClockMs = active.incident.updatedAtMs + quietDurationMs
        return close(closeWallClockMs, "quiet window elapsed")
    }

    @Synchronized
    fun interrupted(
        nowMs: Long,
    ): IncidentUpdate.Closed? {
        clearAllPrecursors()
        val active = activeIncident ?: return null
        activeIncident = null
        val interrupted = active.incident.copy(
            lifecycle = IncidentLifecycle.INTERRUPTED,
            updatedAtMs = nowMs,
            closedAtMs = nowMs,
            closeReason = "process interrupted",
        )
        return IncidentUpdate.Closed(interrupted)
    }

    private fun clearAudioPrecursors() {
        audioPrecursors.clear()
    }

    private fun clearAllPrecursors() {
        lightPrecursor = null
        audioPrecursors.clear()
        vibrationPrecursor = null
        chargerPrecursor = null
        locationPrecursor = null
    }

    private fun recordAudioPrecursor(evidence: IncidentEvidence) {
        audioPrecursors.add(evidence)
        if (audioPrecursors.size > AUDIO_MAX_CANDIDATES) {
            audioPrecursors.removeAt(0)
        }
    }

    private fun openIncident(
        classification: Classification,
        openingEvidence: List<IncidentEvidence>,
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation?,
        supersede: Boolean = false,
    ): IncidentUpdate.Opened {
        // Taking the single active slot must settle whatever held it, never orphan a
        // permanently-open incident in history.
        val superseded = if (supersede) {
            activeIncident?.incident?.copy(
                lifecycle = IncidentLifecycle.CLOSED,
                updatedAtMs = observation.wallClockMs,
                closedAtMs = observation.wallClockMs,
                closeReason = "superseded by a confirmed power episode",
            )
        } else {
            null
        }
        val incident = SecurityIncident(
            id = idGenerator.nextId(),
            type = classification.type,
            severity = classification.severity,
            lifecycle = IncidentLifecycle.OPEN,
            evidence = openingEvidence,
            openedAtMs = openingEvidence.minOf(IncidentEvidence::wallClockMs),
            updatedAtMs = observation.wallClockMs,
            closedAtMs = null,
            protectionState = protectionState,
            deliveryState = DeliveryState.PENDING,
            location = location,
        )
        activeIncident = ActiveIncident(incident, observation.eventElapsedMs)
        return IncidentUpdate.Opened(incident, supersededIncident = superseded)
    }

    private fun classificationFromAudioThreat(audioThreat: AudioThreatMetadata): Classification {
        return when (audioThreat.category) {
            AudioThreatCategory.IMPACT,
            AudioThreatCategory.BREAKING,
            AudioThreatCategory.POWER_TOOL,
            AudioThreatCategory.ENGINE_START -> {
                Classification(IncidentType.AUDIO, IncidentSeverity.CRITICAL)
            }
            AudioThreatCategory.METAL_TAMPER,
            AudioThreatCategory.ENGINE_RUNNING -> {
                Classification(IncidentType.AUDIO, IncidentSeverity.WARNING)
            }
        }
    }

    private fun appendEvidence(
        existing: List<IncidentEvidence>,
        newEvidence: IncidentEvidence,
        maxCount: Int = 30
    ): List<IncidentEvidence> {
        if (existing.size < maxCount) return existing + newEvidence
        val initialPrecursors = existing.take(5)
        val remainingSlots = maxCount - initialPrecursors.size
        val recent = (existing.drop(5) + newEvidence).takeLast(remainingSlots)
        return initialPrecursors + recent
    }

    private fun classifyAudioVibration(threat: AudioThreatMetadata, isRepeated: Boolean): Classification {
        return when (threat.category) {
            AudioThreatCategory.IMPACT -> {
                if (isRepeated) {
                    Classification(IncidentType.VIBRATION, IncidentSeverity.CRITICAL)
                } else {
                    Classification(IncidentType.VIBRATION, IncidentSeverity.WARNING)
                }
            }
            AudioThreatCategory.BREAKING,
            AudioThreatCategory.POWER_TOOL,
            AudioThreatCategory.ENGINE_START -> {
                Classification(IncidentType.AUDIO, IncidentSeverity.CRITICAL)
            }
            AudioThreatCategory.METAL_TAMPER,
            AudioThreatCategory.ENGINE_RUNNING -> {
                Classification(IncidentType.AUDIO, IncidentSeverity.WARNING)
            }
        }
    }

    private fun updatedClassification(
        current: SecurityIncident,
        observation: SensorObservation,
        evidence: List<IncidentEvidence>,
    ): Classification {
        if (observation.kind == SensorKind.POWER_THERMAL && observation.diagnostic == ProtectionDiagnostics.CHARGER_DISCONNECTED) {
            return Classification(IncidentType.POWER, IncidentSeverity.CRITICAL)
        }

        // An open power episode owns its incident until its own recovery verdict closes
        // it. Movement or audio picked up while the owner works on the cable is context,
        // not grounds to relabel the incident — relabelling strands the episode, because
        // only a POWER incident can consume the recovery verdict.
        if (current.type == IncidentType.POWER) {
            return Classification(IncidentType.POWER, current.severity)
        }

        val vibration = evidence.lastOrNull { item -> item.kind == SensorKind.VIBRATION }
        val light = evidence.lastOrNull { item -> item.kind == SensorKind.LIGHT }
        val powerTool = evidence.lastOrNull { item -> item.kind == SensorKind.MICROPHONE && item.audioThreat?.category == AudioThreatCategory.POWER_TOOL }

        if (powerTool != null && light != null && vibration != null) {
            return Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
        }

        if (
            vibration != null &&
            light != null &&
            abs(vibration.eventElapsedMs - light.eventElapsedMs) <= correlationWindowMs
        ) {
            return Classification(IncidentType.TAMPER, IncidentSeverity.CRITICAL)
        }

        val audioThreat = observation.audioThreat ?: evidence.lastOrNull { it.kind == SensorKind.MICROPHONE }?.audioThreat
        if (audioThreat != null && (
            audioThreat.category == AudioThreatCategory.BREAKING ||
            audioThreat.category == AudioThreatCategory.POWER_TOOL ||
            audioThreat.category == AudioThreatCategory.ENGINE_START
        )) {
            val updatedType = if (current.type == IncidentType.TAMPER) IncidentType.TAMPER else IncidentType.AUDIO
            return Classification(updatedType, IncidentSeverity.CRITICAL)
        }

        return Classification(current.type, current.severity)
    }

    private fun purgePrecursors(nowElapsedMs: Long) {
        if (lightPrecursor != null && (nowElapsedMs - lightPrecursor!!.eventElapsedMs > correlationWindowMs)) {
            lightPrecursor = null
        }
        audioPrecursors.removeAll { nowElapsedMs - it.eventElapsedMs > correlationWindowMs }
        if (vibrationPrecursor != null && (nowElapsedMs - vibrationPrecursor!!.eventElapsedMs > correlationWindowMs)) {
            vibrationPrecursor = null
        }
        if (chargerPrecursor != null && (nowElapsedMs - chargerPrecursor!!.eventElapsedMs > correlationWindowMs)) {
            chargerPrecursor = null
        }
        if (locationPrecursor != null && (nowElapsedMs - locationPrecursor!!.eventElapsedMs > correlationWindowMs)) {
            locationPrecursor = null
        }
    }

    private fun isPrimaryRole(role: SensorRole?): Boolean {
        return role == SensorRole.PRIMARY || role == null
    }

    private fun hasPrimaryRole(vararg items: Any?): Boolean {
        return items.any { item ->
            when (item) {
                is SensorObservation -> isPrimaryRole(item.role)
                is IncidentEvidence -> isPrimaryRole(item.role)
                else -> false
            }
        }
    }

    private data class Classification(
        val type: IncidentType,
        val severity: IncidentSeverity,
    )



    /**
     * Typed Entry Guard handling (spec sections 7-8): one door episode per physical
     * opening, mount-moved outranks door events, health episodes deduplicate, and a
     * confirmed close/recovery resolves only an ENTRY_DOOR incident — never another type.
     */
    private fun acceptEntry(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation?,
    ): IncidentUpdate {
        val evidence = observation.toEvidence()
        val active = activeIncident
        val isEntryIncident = active?.incident?.type == IncidentType.ENTRY_DOOR
        return when (observation.diagnostic) {
            ENTRY_DOOR_OPEN, ENTRY_DOOR_STILL_OPEN -> {
                if (active == null || !isEntryIncident) {
                    openIncident(
                        Classification(IncidentType.ENTRY_DOOR, IncidentSeverity.WARNING),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                    )
                } else {
                    val updated = active.incident.copy(
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    IncidentUpdate.Updated(updated)
                }
            }
            ENTRY_MOUNT_MOVED -> {
                // Mount movement outranks any door event and escalates to critical.
                if (active == null || !isEntryIncident) {
                    openIncident(
                        Classification(IncidentType.ENTRY_DOOR, IncidentSeverity.CRITICAL),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                    )
                } else {
                    val updated = active.incident.copy(
                        severity = IncidentSeverity.CRITICAL,
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    IncidentUpdate.Escalated(updated)
                }
            }
            ENTRY_SOURCE_UNAVAILABLE -> {
                if (active == null || !isEntryIncident) {
                    openIncident(
                        Classification(IncidentType.ENTRY_DOOR, IncidentSeverity.WARNING),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                    )
                } else {
                    val updated = active.incident.copy(
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    IncidentUpdate.Updated(updated)
                }
            }
            ENTRY_DOOR_CLOSED, ENTRY_SOURCE_RECOVERED -> {
                if (isEntryIncident) {
                    close(
                        nowMs = observation.wallClockMs,
                        reason = if (observation.diagnostic == ENTRY_DOOR_CLOSED) {
                            "entry door closed confirmed"
                        } else {
                            "entry source recovered"
                        },
                    ) ?: IncidentUpdate.Ignored
                } else {
                    IncidentUpdate.Ignored
                }
            }
            else -> IncidentUpdate.Ignored
        }
    }

    /**
     * Typed Power Guard handling (parent spec sections 4.3/5): verdicts arrive fully
     * evaluated with typed diagnostics. One-signal conditions open WARNING health
     * incidents that are never called a power outage; confirmed dual loss opens or
     * escalates a CRITICAL POWER incident; recovery closes only a POWER incident —
     * never another type.
     */
    private fun acceptPower(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation?,
    ): IncidentUpdate {
        val evidence = observation.toEvidence()
        val active = activeIncident
        val isPowerIncident = active?.incident?.type == IncidentType.POWER
        return when (observation.diagnostic) {
            POWER_CHARGING_HEALTH, POWER_WITNESS_DARK -> {
                if (active == null || !isPowerIncident) {
                    openIncident(
                        Classification(IncidentType.POWER, IncidentSeverity.WARNING),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                        supersede = active != null,
                    )
                } else {
                    val updated = active.incident.copy(
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    IncidentUpdate.Updated(updated)
                }
            }
            POWER_CONFIRMED_LOSS -> {
                if (active == null || !isPowerIncident) {
                    openIncident(
                        Classification(IncidentType.POWER, IncidentSeverity.CRITICAL),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                        supersede = active != null,
                    )
                } else {
                    val updated = active.incident.copy(
                        severity = IncidentSeverity.CRITICAL,
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    if (updated.severity.ordinal > active.incident.severity.ordinal) {
                        IncidentUpdate.Escalated(updated)
                    } else {
                        IncidentUpdate.Updated(updated)
                    }
                }
            }
            POWER_RECOVERED -> {
                if (isPowerIncident) {
                    val recovered = active.incident.copy(
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(recovered, observation.eventElapsedMs)
                    close(
                        nowMs = observation.wallClockMs,
                        reason = "power supply stable again",
                    ) ?: IncidentUpdate.Ignored
                } else {
                    IncidentUpdate.Ignored
                }
            }
            else -> IncidentUpdate.Ignored
        }
    }

    private companion object {
        val ENTRY_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.ENTRY_PREFIX
        val ENTRY_DOOR_OPEN = ProtectionDiagnostics.ENTRY_DOOR_OPEN
        val ENTRY_DOOR_STILL_OPEN = ProtectionDiagnostics.ENTRY_DOOR_STILL_OPEN
        val ENTRY_DOOR_CLOSED = ProtectionDiagnostics.ENTRY_DOOR_CLOSED
        val ENTRY_SOURCE_UNAVAILABLE = ProtectionDiagnostics.ENTRY_SOURCE_UNAVAILABLE
        val ENTRY_SOURCE_RECOVERED = ProtectionDiagnostics.ENTRY_SOURCE_RECOVERED
        val ENTRY_MOUNT_MOVED = ProtectionDiagnostics.ENTRY_MOUNT_MOVED

        val POWER_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.POWER_PREFIX
        val POWER_CHARGING_HEALTH = ProtectionDiagnostics.POWER_CHARGING_HEALTH
        val POWER_WITNESS_DARK = ProtectionDiagnostics.POWER_WITNESS_DARK
        val POWER_CONFIRMED_LOSS = ProtectionDiagnostics.POWER_CONFIRMED_LOSS
        val POWER_RECOVERED = ProtectionDiagnostics.POWER_RECOVERED

        val ACTIVE_PROTECTION_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
    }
}
