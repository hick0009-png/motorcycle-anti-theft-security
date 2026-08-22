package com.example.motorcycleantitheftsensor.protection

import android.os.SystemClock

enum class DeliveryAction {
    NONE,
    PERSIST_ONLY,
    PERSIST_AND_EDIT,
    SEND,
    SEND_CONTINUATION,
    SEND_CLOSE_SUMMARY,
}

class IncidentUpdateDeliveryPolicy(
    private val updateCoalesceIntervalMs: Long = 1000L,
    private val progressEditIntervalMs: Long = 10_000L,
    private val continuationInitialDelayMs: Long = 60_000L,
    private val continuationRepeatIntervalMs: Long = 300_000L,
) {
    @Volatile
    private var lastUpdatePersistedElapsedMs: Long = 0L
    private var activeIncidentId: String? = null
    private var lastProgressEditElapsedMs: Long = 0L
    private var nextContinuationElapsedMs: Long = Long.MAX_VALUE

    fun action(
        update: IncidentUpdate,
        nowElapsedMs: Long = try { SystemClock.elapsedRealtime() } catch (_: Throwable) { System.currentTimeMillis() },
    ): DeliveryAction = when (update) {
        IncidentUpdate.Ignored -> DeliveryAction.NONE
        is IncidentUpdate.Opened -> {
            trackIncident(update.incident.id, nowElapsedMs)
            lastUpdatePersistedElapsedMs = nowElapsedMs
            DeliveryAction.SEND
        }
        is IncidentUpdate.Updated -> {
            ensureTracked(update.incident.id, nowElapsedMs)
            if (nowElapsedMs >= nextContinuationElapsedMs) {
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
            DeliveryAction.SEND
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
    }

    private fun clearTrackedIncident() {
        activeIncidentId = null
        lastProgressEditElapsedMs = 0L
        nextContinuationElapsedMs = Long.MAX_VALUE
    }
}
