package com.example.motorcycleantitheftsensor.protection

/** What the platform last said about the quality of the orientation source. */
enum class EntrySourceAccuracy {
    /** Nothing reported yet. Not a complaint, and never treated as one. */
    UNKNOWN,
    NO_CONTACT,
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Whether the orientation sample about to be judged can be believed.
 *
 * The detection policy has always had a freshness gate as its first gate, and the runtime
 * has always handed it `fresh = true`, hardcoded, with an empty accuracy callback beside it.
 * The gate was therefore dead code on every phone: a source that stopped delivering, or that
 * told us its own fusion had lost the plot, went on being read as a door angle. On a phone
 * whose vendor freezes background apps — which is most of them outside this one — a watch
 * that stops receiving looks exactly like a door that never opened.
 *
 * Two things make a sample not believable:
 *
 * - **A gap.** The source is registered at game rate; a silence measured in seconds means
 *   delivery stopped, whatever the reason.
 * - **A complaint.** The platform reports the fusion as unreliable, or the sensor as having
 *   no contact. Momentary complaints right after registration are normal while a fusion
 *   settles, so a complaint has to persist before it counts — otherwise arming a phone would
 *   often announce a lost source in the same breath.
 *
 * A sample that is not believable produces `SourceUnavailable` and the owner is told; the
 * detection policy then requires a stretch of healthy samples before it trusts an angle
 * again. That is deliberately louder than staying silent: a door watch that cannot see is
 * not a door watch, and the owner has to know which one they have.
 */
class EntrySourceFreshnessTracker(
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    private val complaintGraceMs: Long = DEFAULT_COMPLAINT_GRACE_MS,
) {
    private var lastSampleAtMs: Long? = null
    private var complainingSinceMs: Long? = null

    /** Forgets everything. Called when the listener is registered again. */
    fun reset() {
        lastSampleAtMs = null
        complainingSinceMs = null
    }

    fun onAccuracy(accuracy: EntrySourceAccuracy, atMs: Long) {
        val complaining = accuracy == EntrySourceAccuracy.UNRELIABLE || accuracy == EntrySourceAccuracy.NO_CONTACT
        complainingSinceMs = if (complaining) complainingSinceMs ?: atMs else null
    }

    /** @return whether this sample may be judged as a door angle. */
    fun onSample(timestampMs: Long): Boolean {
        val previous = lastSampleAtMs
        lastSampleAtMs = timestampMs
        val gapped = previous != null && timestampMs - previous > maxGapMs
        val complained = complainingSinceMs?.let { timestampMs - it >= complaintGraceMs } == true
        return !gapped && !complained
    }

    companion object {
        /**
         * Registered at `SENSOR_DELAY_GAME`, samples arrive every few tens of milliseconds.
         * Ten seconds of nothing is not a slow phone; it is a stopped one. Generous on
         * purpose: the cost of calling a live source dead is an alert the owner does not
         * need, and this gate is meant to catch a watch that has actually stopped.
         */
        const val DEFAULT_MAX_GAP_MS = 10_000L

        /**
         * A fusion that has just been registered often reports itself unreliable for a moment
         * while it settles. Only a complaint that outlives that is about the sensor.
         */
        const val DEFAULT_COMPLAINT_GRACE_MS = 3_000L
    }
}
