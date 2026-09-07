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

    /** Type of the incident currently holding the single active slot, if any. */
    val activeIncidentType: IncidentType?
        @Synchronized get() = activeIncident?.incident?.type
    private var lightPrecursor: IncidentEvidence? = null
    private val audioPrecursors = mutableListOf<IncidentEvidence>()
    private var vibrationPrecursor: IncidentEvidence? = null
    private var chargerPrecursor: IncidentEvidence? = null
    private var locationPrecursor: IncidentEvidence? = null

    /**
     * When movement was last seen, on the observation clock.
     *
     * Kept apart from [vibrationPrecursor] deliberately: that one is consumed the moment it
     * helps open an incident, and correlation would then forget the very shake it just used.
     * This one only ever records that movement happened, which is the question the door
     * watch asks of it.
     */
    private var lastMovementElapsedMs: Long? = null

    /** Set per call from [accept]; see that parameter for why the caller owns this. */
    private var soundAndMovementDoorWatch: Boolean = false

    /** Set per call from [accept]; see that parameter for why the caller owns this. */
    private var doorAngleWatch: Boolean = false

    /**
     * True under either door watch — the angle level or the sound-and-movement level. The
     * charging line is a device-tamper signal under both, and is handled the same way under both.
     */
    private val entryDoorWatch: Boolean
        get() = doorAngleWatch || soundAndMovementDoorWatch

    @Synchronized
    fun accept(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation? = null,
        /**
         * Whether this armed session runs a movement signal at all. Only the caller knows,
         * and a caller that does not say is taken to have none — a door watch that cannot
         * corroborate keeps the behaviour it has today rather than falling silent.
         */
        movementCorroborationArmed: Boolean = false,
        /**
         * Whether the armed use is the door watch running on sound and movement alone. The
         * evidence is identical to a blow against a vehicle; what it means is not, and only
         * the caller knows which use is running. A caller that does not say gets the vehicle
         * reading, which is what every caller got before this existed.
         */
        soundAndMovementDoorWatch: Boolean = false,
        /**
         * Whether the armed use is the door watch running at the angle level. Here the
         * orientation verdict is the sole host and arrives with its own entry diagnostic;
         * every other movement sample that reaches an opening decision is a raw orientation
         * or accelerometer tick that `signalRoles(ENTRY, DOOR_ANGLE)` makes supporting, even
         * though the source role stamped upstream still reads PRIMARY. A caller that does not
         * say gets the vehicle reading, which is what every caller got before this existed.
         */
        doorAngleWatch: Boolean = false,
    ): IncidentUpdate {
        this.soundAndMovementDoorWatch = soundAndMovementDoorWatch
        this.doorAngleWatch = doorAngleWatch
        if (protectionState !in ACTIVE_PROTECTION_STATES) {
            clearAllPrecursors()
            return IncidentUpdate.Ignored
        }

        // Entry Guard verdicts arrive fully evaluated with typed diagnostics; they own
        // their episode lifecycle and never participate in precursor correlation.
        if (observation.diagnostic?.startsWith(ENTRY_DIAGNOSTIC_PREFIX) == true) {
            return acceptEntry(observation, protectionState, location, movementCorroborationArmed)
        }

        // Power Guard verdicts arrive fully evaluated by the armed-session arbiter with
        // typed diagnostics; they own their episode lifecycle and never participate in
        // precursor correlation either.
        if (observation.diagnostic?.startsWith(POWER_DIAGNOSTIC_PREFIX) == true) {
            return acceptPower(observation, protectionState, location)
        }

        // Under a door watch the charging line is not a supply signal at all — it is tamper with
        // the guarding phone. Dispatched here, before the generic charger paths, so it can never
        // open a POWER incident or relabel the door episode POWER (which speaks supply words and
        // strands the episode against a recovery verdict that only a POWER incident can consume).
        if (
            entryDoorWatch &&
            observation.kind == SensorKind.POWER_THERMAL &&
            observation.diagnostic == ProtectionDiagnostics.CHARGER_DISCONNECTED
        ) {
            return acceptDoorModeChargerTamper(observation, protectionState, location)
        }

        // Every real movement sample, whatever it goes on to do: the door watch needs to know
        // that something shook, not what the shake was classified as. Entry verdicts share the
        // movement kind but were dispatched above, so they can never answer their own question.
        if (observation.kind == SensorKind.VIBRATION && observation.valid && shookSomething(observation)) {
            lastMovementElapsedMs = observation.eventElapsedMs
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
            // The one opening path that never asked whether either signal was allowed to
            // host. Sound near a door and a phone that drifted a few metres would open a
            // critical alert for a use that hosts on neither.
            if (matchedAudio != null && hasPrimaryRole(observation, matchedAudio)) {
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

    /**
     * Ends an incident that has gone quiet. This is a movement-domain rule — no further
     * vibration means the event is over — and it does not hold for a power episode: the
     * supply arbiter fires one verdict and an on-change light sensor then reports
     * nothing at all while the lamp stays dark, so silence is exactly what an ongoing
     * outage looks like. Closing on it told the owner the supply was stable again while
     * the cable was still cut, and left the real recovery with no incident to close. A
     * power episode ends on its own recovery verdict, or when the armed session ends.
     */
    @Synchronized
    fun closeIfQuiet(
        nowElapsedMs: Long,
        quietWindowMs: Long,
    ): IncidentUpdate.Closed? {
        val active = activeIncident ?: return null
        if (active.incident.type == IncidentType.POWER) return null
        val quietDurationMs = nowElapsedMs - active.lastEvidenceElapsedMs
        if (quietDurationMs < quietWindowMs) return null
        val closeWallClockMs = active.incident.updatedAtMs + quietDurationMs
        return close(closeWallClockMs, IncidentCloseReason.QUIET_WINDOW_ELAPSED)
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
            closeReason = IncidentCloseReason.PROCESS_INTERRUPTED,
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
        // Disarm ends the session: a shake from before it can corroborate nothing after it.
        lastMovementElapsedMs = null
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
        // permanently-open incident in history. This holds whether or not the caller asked
        // to supersede: the slot has one occupant, so an unannounced replacement left the
        // previous incident OPEN forever, invisible to the close paths that only ever look
        // at the active one.
        val displaced = activeIncident?.incident
        val superseded = displaced?.copy(
            lifecycle = IncidentLifecycle.CLOSED,
            updatedAtMs = observation.wallClockMs,
            closedAtMs = observation.wallClockMs,
            closeReason = if (supersede) {
                "superseded by a confirmed power episode"
            } else {
                "superseded by ${classification.type.name.lowercase()}"
            },
        )
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
        // A door watch with no angle still knows what it is watching. Typing this as a
        // vehicle incident would hand the owner copy about a bike being struck, and would
        // route it past the door wording entirely.
        if (soundAndMovementDoorWatch) {
            val severity = when (threat.category) {
                AudioThreatCategory.BREAKING,
                AudioThreatCategory.POWER_TOOL,
                -> IncidentSeverity.CRITICAL
                AudioThreatCategory.IMPACT -> if (isRepeated) IncidentSeverity.CRITICAL else IncidentSeverity.WARNING
                else -> IncidentSeverity.WARNING
            }
            return Classification(IncidentType.ENTRY_DOOR, severity)
        }
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

    /**
     * Whether this sample is evidence that something physically moved, rather than evidence
     * that an angle changed.
     *
     * The door watch asks for corroboration because drift arrives alone: a still phone's
     * reported angle walks a few degrees an hour, a real opening shakes the door. But the
     * same orientation sensor the watch reads is also sampled by the general detector set,
     * which forwards its raw angle deltas as movement — so the walk was answering its own
     * question. Eight hours of drift crossed the alert angle, the raw form of that very drift
     * had advanced the movement clock a moment earlier, and the gate built to stop the 03:00
     * false alert waved it through. On the test device that was three overnight alerts on a
     * door nobody touched, with the black box showing the phone dead still and the sample
     * stream perfectly healthy all night.
     *
     * An orientation vector is an angle, and an angle is the thing being corroborated. Only
     * the sources that measure motion itself may say that something moved. The gyroscope is
     * not excluded: it reports a rate, and a phone at rest reports zero however far its
     * reference has wandered. Nor is a sample whose source is unnamed — this refuses what it
     * can prove is an angle, and nothing else, because the cost of refusing wrongly is a door
     * watch that never speaks.
     *
     * Only under the angle watch, which is the only use with an angle to protect from itself.
     */
    private fun shookSomething(observation: SensorObservation): Boolean {
        if (!doorAngleWatch) return true
        return observation.source !in DOOR_ANGLE_SOURCES
    }

    /**
     * Only a declared host may open an incident.
     *
     * A missing role used to count as primary, which quietly made hosts of every signal
     * that reached the engine without one — the microphone, the location fix and the
     * charging line. The safe reading of "nobody said" is "may not", so an unstamped
     * observation now corroborates and never starts. Every signal that must host says so
     * through `ProtectionProfilePolicy.signalRoles`, applied where the observation is
     * recorded.
     */
    private fun isPrimaryRole(role: SensorRole?): Boolean {
        return role == SensorRole.PRIMARY
    }

    /**
     * Whether this signal is allowed to host — open or re-type — an incident of its own.
     *
     * At the door-angle level the orientation verdict is the only host, and it is dispatched
     * to [acceptEntry] by its entry diagnostic before any opening decision runs here. Every
     * raw movement sample that reaches one is therefore a bare orientation or accelerometer
     * tick that `signalRoles(ENTRY, DOOR_ANGLE)` makes supporting, yet the source role stamped
     * upstream still reads PRIMARY. Demote it here so it corroborates — it has already advanced
     * the movement clock, and may still join an open door incident as evidence — but can never
     * start or relabel a generic incident. Two orientation pipelines read the same sensor at
     * this level; without this the raw one opens a vehicle-shaped incident beside the door one.
     */
    private fun hostsIncident(role: SensorRole?, kind: SensorKind?): Boolean {
        if (doorAngleWatch && kind == SensorKind.VIBRATION) return false
        return isPrimaryRole(role)
    }

    private fun hasPrimaryRole(vararg items: Any?): Boolean {
        return items.any { item ->
            when (item) {
                is SensorObservation -> hostsIncident(item.role, item.kind)
                is IncidentEvidence -> hostsIncident(item.role, item.kind)
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
        movementCorroborationArmed: Boolean = false,
    ): IncidentUpdate {
        val evidence = observation.toEvidence()
        val active = activeIncident
        val isEntryIncident = active?.incident?.type == IncidentType.ENTRY_DOOR
        // Only claims about a door are asked for a shake, and only when they would raise the
        // alarm themselves. A health episode says the source is gone, which is true whether or
        // not anything moved, and updates to an episode already believed are not new claims.
        // A verdict whose own orientation stream turned fast enough to prove the door physically
        // moved carries its corroboration with it. The shake it would otherwise be asked for can
        // only come from the accelerometer, and a door on its hinge rotates the phone without
        // accelerating it — so demanding one silenced every correct verdict on a door the watch
        // was reading perfectly. Rotation rate answers the same question drift cannot fake.
        val corroborated = observation.doorMotionCorroborated || EntryCorroborationPolicy.mayOpen(
            verdictElapsedMs = observation.eventElapsedMs,
            lastMovementElapsedMs = lastMovementElapsedMs,
            corroborationArmed = movementCorroborationArmed,
        )
        return when (observation.diagnostic) {
            ENTRY_DOOR_OPEN, ENTRY_DOOR_STILL_OPEN -> {
                if (active == null || !isEntryIncident) {
                    if (!corroborated) return IncidentUpdate.Ignored
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
                // Mount movement outranks any door event and escalates to critical. Which is
                // exactly why it is asked for the same proof: drift reaches the residual gate
                // before the angle gate on most mountings, and a mount-moved verdict does not
                // merely alarm — it puts the session into the degraded displaced watch.
                if (active == null || !isEntryIncident) {
                    if (!corroborated) return IncidentUpdate.Ignored
                    openIncident(
                        Classification(IncidentType.ENTRY_DOOR, IncidentSeverity.CRITICAL),
                        listOf(evidence),
                        observation,
                        protectionState,
                        location,
                    )
                } else {
                    val alreadyCritical = active.incident.severity == IncidentSeverity.CRITICAL
                    val updated = active.incident.copy(
                        severity = IncidentSeverity.CRITICAL,
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
                    // A displaced phone that is displaced *again* says something the owner has
                    // not been told: somebody is still handling it. Reported as an escalation
                    // it would be swallowed by the rule that a rise to a severity already
                    // announced is not news — which is right about severity and wrong about
                    // this. It is a condition change, and takes the floor between repeats
                    // rather than the silence.
                    if (alreadyCritical) {
                        IncidentUpdate.Updated(updated, ownerVisibleConditionChange = true)
                    } else {
                        IncidentUpdate.Escalated(updated)
                    }
                }
            }
            // Neither is a claim that anything moved, so neither is asked for a shake. One
            // says the source is gone, the other says the model does not describe where the
            // phone is now; both are true whether or not the door was touched.
            ENTRY_SOURCE_UNAVAILABLE, ENTRY_MOUNT_UNRECOGNIZED -> {
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
            ENTRY_DOOR_CLOSED, ENTRY_SOURCE_RECOVERED, ENTRY_MOUNT_RESTORED -> {
                if (isEntryIncident) {
                    // Land the terminal verdict in the incident's evidence before closing, the
                    // way the power-recovery path below does. The message formatter speaks from
                    // the last entry diagnostic on the incident, so a close that never records
                    // its own diagnostic is rendered from whatever came before it — a stale
                    // mount-moved or door-open — and the owner is told the phone was moved on
                    // the very message that means the door finally shut.
                    val resolved = active.incident.copy(
                        evidence = appendEvidence(active.incident.evidence, evidence),
                        updatedAtMs = observation.wallClockMs,
                        protectionState = protectionState,
                        location = location ?: active.incident.location,
                    )
                    activeIncident = ActiveIncident(resolved, observation.eventElapsedMs)
                    close(
                        nowMs = observation.wallClockMs,
                        reason = when (observation.diagnostic) {
                            ENTRY_DOOR_CLOSED -> IncidentCloseReason.ENTRY_DOOR_CLOSED_CONFIRMED
                            ENTRY_MOUNT_RESTORED -> IncidentCloseReason.ENTRY_MOUNT_RESTORED
                            else -> IncidentCloseReason.ENTRY_SOURCE_RECOVERED
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
     * A pulled charger under a door watch, treated as tamper with the guarding phone rather
     * than as a supply signal.
     *
     * It hosts a door-typed CRITICAL incident on its own — a thief who only unplugs the phone
     * must still be heard, so it opens even with no door movement and takes the slot from any
     * incident that is not already a door episode. On an open door episode it corroborates and
     * raises: a rise from WARNING is an escalation, and a pull while the episode is already
     * critical is a new fact the owner has not heard (a hand on the phone) and takes the floor
     * as a condition change rather than being coalesced into silence — the same shape as a mount
     * displaced twice. It is never typed POWER: door mode speaks door words, and the incident
     * must stay ENTRY_DOOR so a later door-closed verdict can resolve it.
     */
    private fun acceptDoorModeChargerTamper(
        observation: SensorObservation,
        protectionState: ProtectionState,
        location: IncidentLocation?,
    ): IncidentUpdate {
        val evidence = observation.toEvidence()
        val active = activeIncident
        if (active == null || active.incident.type != IncidentType.ENTRY_DOOR) {
            return openIncident(
                Classification(IncidentType.ENTRY_DOOR, IncidentSeverity.CRITICAL),
                listOf(evidence),
                observation,
                protectionState,
                location,
            )
        }
        val alreadyCritical = active.incident.severity == IncidentSeverity.CRITICAL
        val updated = active.incident.copy(
            severity = IncidentSeverity.CRITICAL,
            evidence = appendEvidence(active.incident.evidence, evidence),
            updatedAtMs = observation.wallClockMs,
            protectionState = protectionState,
            location = location ?: active.incident.location,
        )
        activeIncident = ActiveIncident(updated, observation.eventElapsedMs)
        return if (alreadyCritical) {
            IncidentUpdate.Updated(updated, ownerVisibleConditionChange = true)
        } else {
            IncidentUpdate.Escalated(updated)
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
            POWER_CHARGING_HEALTH, POWER_WITNESS_DARK,
            POWER_PARTIAL_WITNESS_DARK, POWER_PARTIAL_CHARGING_LOST,
            -> {
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
                    // Partial recovery leaves the reached severity untouched — one
                    // signal is still lost — but it is a new condition for the owner,
                    // so it must not be coalesced away as a silent update.
                    IncidentUpdate.Updated(
                        updated,
                        ownerVisibleConditionChange =
                            observation.diagnostic in POWER_PARTIAL_RECOVERY_DIAGNOSTICS,
                    )
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
                        reason = IncidentCloseReason.POWER_SUPPLY_STABLE,
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
        val ENTRY_MOUNT_RESTORED = ProtectionDiagnostics.ENTRY_MOUNT_RESTORED
        val ENTRY_MOUNT_UNRECOGNIZED = ProtectionDiagnostics.ENTRY_MOUNT_UNRECOGNIZED

        /** The rotation vectors an armed door watch may itself be reading; see [shookSomething]. */
        val DOOR_ANGLE_SOURCES = setOf(
            SensorSource.ROTATION_VECTOR,
            SensorSource.GAME_ROTATION_VECTOR,
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
        )

        val POWER_DIAGNOSTIC_PREFIX = ProtectionDiagnostics.POWER_PREFIX
        val POWER_CHARGING_HEALTH = ProtectionDiagnostics.POWER_CHARGING_HEALTH
        val POWER_WITNESS_DARK = ProtectionDiagnostics.POWER_WITNESS_DARK
        val POWER_CONFIRMED_LOSS = ProtectionDiagnostics.POWER_CONFIRMED_LOSS
        val POWER_PARTIAL_WITNESS_DARK = ProtectionDiagnostics.POWER_PARTIAL_WITNESS_DARK
        val POWER_PARTIAL_CHARGING_LOST = ProtectionDiagnostics.POWER_PARTIAL_CHARGING_LOST
        val POWER_PARTIAL_RECOVERY_DIAGNOSTICS = setOf(
            POWER_PARTIAL_WITNESS_DARK,
            POWER_PARTIAL_CHARGING_LOST,
        )
        val POWER_RECOVERED = ProtectionDiagnostics.POWER_RECOVERED

        val ACTIVE_PROTECTION_STATES = setOf(
            ProtectionState.ARMED_HEALTHY,
            ProtectionState.ARMED_DEGRADED,
            ProtectionState.ALERT_ACTIVE,
        )
    }
}
