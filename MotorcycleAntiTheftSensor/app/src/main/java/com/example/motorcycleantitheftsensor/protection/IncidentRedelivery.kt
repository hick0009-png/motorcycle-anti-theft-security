package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which incidents the owner was never actually told about, and still deserve another attempt.
 *
 * An incident used to get exactly one delivery attempt. A phone out of coverage at midnight
 * therefore had a door opening recorded in its history, marked FAILED, and never mentioned
 * again — the network came back at eight in the morning, the heartbeat resumed, and the three
 * things that happened in between stayed in the file and nowhere else. Nothing was broken in a
 * way any log would show: every part did its job once and then stopped.
 *
 * Selection is deliberately narrow. The history is the source, not a queue held in memory,
 * because the process this runs in is killed routinely and a queue would die with it; an
 * incident already carries whether it reached anybody.
 */
class IncidentRedeliveryPolicy(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxRemoteAttempts: Int = DEFAULT_MAX_REMOTE_ATTEMPTS,
    private val maxPerFlush: Int = DEFAULT_MAX_PER_FLUSH,
    private val abandonedAfterMs: Long = DEFAULT_ABANDONED_AFTER_MS,
) {

    /** Oldest first: a backlog read in order is a story, and read backwards is a puzzle. */
    fun select(history: List<SecurityIncident>, nowMs: Long): List<SecurityIncident> = history
        .asSequence()
        .filter { it.reachedNobody(nowMs) }
        // A clock that moved backwards must not silently discard an undelivered warning, so
        // only genuine age disqualifies one — never a negative interval.
        .filter { nowMs - it.updatedAtMs <= maxAgeMs }
        .filter { it.remoteAttempts() < maxRemoteAttempts }
        .sortedBy { it.updatedAtMs }
        .take(maxPerFlush)
        .toList()

    /**
     * Only what this sweep has already spent on the incident.
     *
     * Counting every remote attempt looked equivalent and was not. A door held open while the
     * phone is offline raises an update every couple of seconds, each one a full delivery that
     * fails: the device test produced twenty-nine attempts on a single incident inside thirty
     * seconds. Against a budget of six, the alert was disqualified from being resent before
     * the network had even come back — the sweep refusing to run in exactly the case it exists
     * for. The budget is there to stop the sweep looping, so it counts the sweep.
     */
    private fun SecurityIncident.remoteAttempts(): Int = deliveryAttempts.count { attempt ->
        attempt.detail == DeliveryAttempt.REDELIVERY
    }

    /**
     * A failure, or a delivery that was started and never finished.
     *
     * PENDING is written the moment a delivery begins and replaced within seconds — Telegram
     * gives up after ten and SMS after thirty. A record still saying PENDING long after that
     * is a delivery whose process was killed between the two writes, which on this phone is
     * an ordinary event rather than a rare one, and the owner heard nothing either way.
     *
     * The window is far wider than any real attempt so that a send genuinely in flight is
     * never resent underneath itself. What remains is the moment between the radio accepting
     * a message and the record being updated: a crash exactly there costs one duplicate. A
     * duplicate alert is a nuisance and a missed one is the thing this app exists to prevent.
     */
    private fun SecurityIncident.reachedNobody(nowMs: Long): Boolean = when (deliveryState) {
        DeliveryState.FAILED -> true
        DeliveryState.PENDING -> nowMs - updatedAtMs >= abandonedAfterMs
        else -> false
    }

    companion object {
        /**
         * A night, plus the morning it gets discovered in. Past that the incident history and
         * the black box are the right way to learn about it: an alert arriving a day late reads
         * as something happening now, and that is worse than no alert.
         */
        const val DEFAULT_MAX_AGE_MS: Long = 12L * 60L * 60L * 1000L

        /** Enough to cover a flapping connection, few enough to stop a permanent failure looping. */
        const val DEFAULT_MAX_REMOTE_ATTEMPTS: Int = 6

        /** A burst ceiling, so a long outage cannot empty itself into the owner's phone at once. */
        const val DEFAULT_MAX_PER_FLUSH: Int = 20

        /**
         * How long a delivery may sit unfinished before it is treated as abandoned. Ten times
         * the longest attempt any channel makes, so nothing still running is ever swept.
         */
        const val DEFAULT_ABANDONED_AFTER_MS: Long = 5L * 60L * 1000L
    }
}

/**
 * Sends the backlog [IncidentRedeliveryPolicy] selects, once something says the path may work
 * again. Serialized against itself: connectivity callbacks arrive in bursts, and two flushes
 * racing would send the same incident twice.
 */
class IncidentRedeliverer(
    private val history: suspend () -> List<SecurityIncident>,
    private val delivery: IncidentDeliveryCoordinator,
    private val configuration: suspend () -> DeliveryConfiguration,
    private val nowMs: () -> Long,
    private val policy: IncidentRedeliveryPolicy = IncidentRedeliveryPolicy(),
) {

    data class Outcome(val sent: Int, val stillFailing: Int) {
        val attempted: Int get() = sent + stillFailing
    }

    private val flushMutex = Mutex()

    suspend fun flush(): Outcome = flushMutex.withLock {
        val at = nowMs()
        val due = policy.select(history(), at)
        if (due.isEmpty()) return@withLock Outcome(sent = 0, stillFailing = 0)

        val deliveryConfiguration = configuration()
        var sent = 0
        for (incident in due) {
            val delivered = try {
                delivery.redeliver(incident, deliveryConfiguration, at)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            if (delivered?.deliveryState != DeliveryState.SENT) {
                // The path is still down. Twenty more attempts against it buys twenty more
                // timeouts and tells the owner nothing; whatever says the network is back
                // next will bring the rest of the backlog with it.
                return@withLock Outcome(sent = sent, stillFailing = due.size - sent)
            }
            sent += 1
        }
        Outcome(sent = sent, stillFailing = 0)
    }
}
