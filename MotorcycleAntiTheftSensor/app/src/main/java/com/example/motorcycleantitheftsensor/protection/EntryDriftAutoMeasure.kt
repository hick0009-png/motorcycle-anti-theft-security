package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Measures this phone's drift out of the door watch's own samples, while it is on watch.
 *
 * The manual recording asks three things of an owner and they are the wrong three: start it,
 * do not touch the phone for eight hours, remember to stop it. Every one of those is a way
 * to end up with no measurement or a false one, and the false one is worse — a phone lifted
 * once at dawn reports a rate that condemns hardware which is in fact excellent, and the
 * owner has no reason to suspect the number.
 *
 * None of it is necessary. An armed door watch has already registered the same sensor, at
 * the same rate, through [EntryOrientationSourcePolicy] — the samples this reads are the
 * ones detection is reading anyway, so measuring them costs no battery, no second listener
 * and no decision by the owner. It also measures the right thing: drift as suffered by the
 * source actually in use, in the place the phone actually sits, over the hours it is
 * actually asked to hold a baseline. The manual recording measured a phone on a table.
 *
 * The first night is where this pays. `ProfileDeviceSupportPolicy` lets an unmeasured phone
 * arm — a watch that refuses to start until it has proof of itself protects nothing — and
 * that session is exactly the one that produces the proof. By morning the phone has measured
 * itself, and no one was asked to leave it alone on a table for a night it could have been
 * guarding.
 *
 * What it will not do is invent a measurement. A stale source repeating one reading looks
 * like a perfectly still phone and would be recorded as flawless; a door being opened, or a
 * phone being handled during commissioning, looks like enormous drift. The first is refused
 * here by [EntryOrientationSample.fresh], the second by the sampler's own disturbance
 * ceiling, and both end the stretch rather than being averaged into it.
 */
class EntryDriftAutoMeasure(
    private val samples: () -> Flow<EntryOrientationSample>,
    private val store: EntryDriftMeasurementStore,
    /** The source the door watch is on now, or null when it cannot be named. */
    private val currentSourceLabel: () -> String?,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val saveIntervalMs: Long = DEFAULT_SAVE_INTERVAL_MS,
    private val sessionGapMs: Long = DEFAULT_SESSION_GAP_MS,
) {

    private var sampler = EntryDriftSampler()
    private var lastSampleAtMs: Long? = null
    private var lastSavedMs: Long = 0L

    /** Sessions this has measured across, for logging; the store keeps only the best. */
    var restarts: Int = 0
        private set

    /**
     * Reads the door watch's stream until the caller's scope ends.
     *
     * The stream exists only while an orientation source is registered, so there is no armed
     * session to subscribe to and none to unsubscribe from: samples arriving *is* the
     * session, and their stopping is its end.
     */
    suspend fun collect() {
        samples().collect { sample -> onSample(sample) }
    }

    /** Visible for the tests, which have no flow to drive. */
    suspend fun onSample(sample: EntryOrientationSample) {
        val nowMs = sample.timestampMs
        val previous = lastSampleAtMs
        // A gap is a new session — a different night, a different place, a baseline that has
        // nothing to do with the one before it. Continuing the old stretch across it would
        // count hours in which nothing was being watched.
        if (previous != null && nowMs - previous > sessionGapMs) restart()
        lastSampleAtMs = nowMs

        // Time passed with no trustworthy reading behind it. A source that has gone stale
        // repeats its last value, which is indistinguishable from a phone that never moved
        // and would be saved as the best measurement this phone ever took.
        if (!sample.fresh) {
            restart()
            return
        }

        sampler.onSample(nowMs, sample.quaternion)
        maybeSave()
    }

    private fun restart() {
        sampler = EntryDriftSampler()
        restarts += 1
        lastSavedMs = 0L
    }

    /**
     * Writes the stretch out while it is still growing, rather than at a finish it may never
     * reach.
     *
     * This runs inside a process built to be killed: the whole black box exists because the
     * phone does not get to choose when it dies. A measurement held in memory until the
     * session ends cleanly is a measurement lost on exactly the nights worth measuring. The
     * store keeps the longer of what it holds and what it is given, so writing the same
     * stretch repeatedly as it lengthens costs nothing and each write supersedes the last.
     */
    private suspend fun maybeSave() {
        val cleanMs = sampler.cleanMeasuredMs
        if (cleanMs < EntryDriftBudgetPolicy.MIN_USEFUL_MEASUREMENT_MS) return
        if (cleanMs - lastSavedMs < saveIntervalMs) return
        // Unnamed source means the door watch could not say what it is listening to, and a
        // measurement that cannot name its source is discarded on load anyway.
        val label = currentSourceLabel() ?: return
        val measurement = sampler.measurement(sourceLabel = label, wallClockMs = wallClockMs())
        lastSavedMs = cleanMs
        withContext(dispatcher) { runCatching { store.save(measurement) } }
    }

    companion object {
        /**
         * How much the clean stretch must grow before it is written again.
         *
         * Short enough that a kill costs minutes rather than a night, long enough that the
         * encrypted preferences are not rewritten off the back of a sensor stream.
         */
        const val DEFAULT_SAVE_INTERVAL_MS = 10L * 60_000L

        /**
         * Silence longer than this ends the session.
         *
         * The door watch delivers at game rate, so a real session never has a gap of this
         * size; anything approaching it is the listener having been unregistered.
         */
        const val DEFAULT_SESSION_GAP_MS = 60_000L
    }
}
