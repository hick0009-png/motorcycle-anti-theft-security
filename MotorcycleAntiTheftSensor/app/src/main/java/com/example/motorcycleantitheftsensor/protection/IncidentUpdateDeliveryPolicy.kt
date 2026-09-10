package com.example.motorcycleantitheftsensor.protection

import android.os.SystemClock

enum class DeliveryAction {
    NONE,
    PERSIST_ONLY,
    PERSIST_AND_EDIT,
    SEND,
    SEND_CONTINUATION,
    SEND_CLOSE_SUMMARY,

    /**
     * Persisted exactly like [PERSIST_ONLY], and recorded, because an alert the owner would
     * otherwise have received was withheld as a repeat. Distinct so that a deliberate silence
     * can be told apart from a broken one — which is the whole reason the black box exists.
     */
    SUPPRESS_REPEAT,
}

class IncidentUpdateDeliveryPolicy(
    private val updateCoalesceIntervalMs: Long = 1000L,
    private val progressEditIntervalMs: Long = 10_000L,
    private val continuationInitialDelayMs: Long = 60_000L,
    private val continuationRepeatIntervalMs: Long = 300_000L,
    private val minConditionAlertIntervalMs: Long = DEFAULT_MIN_CONDITION_ALERT_INTERVAL_MS,
    private val maxAlertsPerIncident: Int = DEFAULT_MAX_ALERTS_PER_INCIDENT,
) {
    @Volatile
    private var lastUpdatePersistedElapsedMs: Long = 0L
    private var activeIncidentId: String? = null
    private var lastProgressEditElapsedMs: Long = 0L
    private var nextContinuationElapsedMs: Long = Long.MAX_VALUE

    /** The worst this incident has already been announced as. */
    private var announcedSeverity: IncidentSeverity? = null
    private var lastConditionAlertElapsedMs: Long = 0L
    private var alertsSent: Int = 0

    fun action(
        update: IncidentUpdate,
        nowElapsedMs: Long = try { SystemClock.elapsedRealtime() } catch (_: Throwable) { System.currentTimeMillis() },
    ): DeliveryAction = when (update) {
        IncidentUpdate.Ignored -> DeliveryAction.NONE
        is IncidentUpdate.Opened -> {
            trackIncident(update.incident.id, nowElapsedMs)
            lastUpdatePersistedElapsedMs = nowElapsedMs
            recordAlert(update.incident.severity)
            DeliveryAction.SEND
        }
        is IncidentUpdate.Updated -> {
            ensureTracked(update.incident.id, nowElapsedMs)
            if (update.ownerVisibleConditionChange) {
                // The condition itself changed for the owner: coalescing it into a
                // progress edit would hide the new meaning behind generic copy. The first
                // one therefore always goes. A condition that flaps, though, says the same
                // thing each time it turns over, and a floor between repeats bounds what
                // that costs without ever silencing the change itself.
                lastUpdatePersistedElapsedMs = nowElapsedMs
                if (mayRepeatConditionAlert(nowElapsedMs)) {
                    lastConditionAlertElapsedMs = nowElapsedMs
                    recordAlert(update.incident.severity)
                    DeliveryAction.SEND
                } else {
                    DeliveryAction.SUPPRESS_REPEAT
                }
            } else if (nowElapsedMs >= nextContinuationElapsedMs) {
                lastUpdatePersistedElapsedMs = nowElapsedMs
                lastProgressEditElapsedMs = nowElapsedMs
                do {
                    nextContinuationElapsedMs += continuationRepeatIntervalMs
                } while (nextContinuationElapsedMs <= nowElapsedMs)
                DeliveryAction.SEND_CONTINUATION
            } else if (nowElapsedMs - lastProgressEditElapsedMs >= progressEditIntervalMs) {
                lastUpdatePersistedElapsedMs = nowElapsedMs
                lastProgressEditElapsedMs = nowElapsedMs
                DeliveryAction.PERSIST_AND_EDIT
            } else if (nowElapsedMs - lastUpdatePersistedElapsedMs >= updateCoalesceIntervalMs) {
                lastUpdatePersistedElapsedMs = nowElapsedMs
                DeliveryAction.PERSIST_ONLY
            } else {
                DeliveryAction.NONE
            }
        }
        is IncidentUpdate.Escalated -> {
            ensureTracked(update.incident.id, nowElapsedMs)
            lastUpdatePersistedElapsedMs = nowElapsedMs
            // An escalation to a severity the owner has already been told about is not news.
            // Never bounded by time or by the ceiling: a real rise in severity is the most
            // important thing this app ever has to say, and it can happen at most twice.
            val announced = announcedSeverity
            if (announced == null || update.incident.severity > announced) {
                recordAlert(update.incident.severity)
                DeliveryAction.SEND
            } else {
                DeliveryAction.SUPPRESS_REPEAT
            }
        }
        is IncidentUpdate.Closed -> {
            if (activeIncidentId == update.incident.id) clearTrackedIncident()
            lastUpdatePersistedElapsedMs = nowElapsedMs
            DeliveryAction.SEND_CLOSE_SUMMARY
        }
    }

    private fun ensureTracked(incidentId: String, nowElapsedMs: Long) {
        if (activeIncidentId != incidentId) trackIncident(incidentId, nowElapsedMs)
    }

    private fun trackIncident(incidentId: String, nowElapsedMs: Long) {
        activeIncidentId = incidentId
        lastProgressEditElapsedMs = nowElapsedMs
        nextContinuationElapsedMs = nowElapsedMs + continuationInitialDelayMs
        announcedSeverity = null
        lastConditionAlertElapsedMs = 0L
        alertsSent = 0
    }

    private fun clearTrackedIncident() {
        activeIncidentId = null
        lastProgressEditElapsedMs = 0L
        nextContinuationElapsedMs = Long.MAX_VALUE
        announcedSeverity = null
        lastConditionAlertElapsedMs = 0L
        alertsSent = 0
    }

    /**
     * The first condition change always passes; repeats wait out the floor, and the ceiling
     * is the backstop for a case nobody has thought of yet. Neither gate can reach an
     * escalation or a close summary.
     */
    private fun mayRepeatConditionAlert(nowElapsedMs: Long): Boolean {
        if (alertsSent >= maxAlertsPerIncident) return false
        val last = lastConditionAlertElapsedMs
        return last == 0L || nowElapsedMs - last >= minConditionAlertIntervalMs
    }

    private fun recordAlert(severity: IncidentSeverity) {
        val announced = announcedSeverity
        announcedSeverity = if (announced == null || severity > announced) severity else announced
        alertsSent += 1
    }

    companion object {
        /** How long a flapping condition must hold before it may say the same thing again. */
        const val DEFAULT_MIN_CONDITION_ALERT_INTERVAL_MS: Long = 60_000L

        /**
         * A backstop, not the working limit: the rules above already bound an ordinary
         * incident to a handful of messages. Twelve is more than any real story needs and
         * few enough that a path nobody has found yet cannot run up a phone bill overnight.
         */
        const val DEFAULT_MAX_ALERTS_PER_INCIDENT: Int = 12
    }
}
