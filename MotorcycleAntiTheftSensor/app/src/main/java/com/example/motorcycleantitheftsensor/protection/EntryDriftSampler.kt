package com.example.motorcycleantitheftsensor.protection

/** One recorded point of orientation drift, all angles in degrees from the frozen baseline. */
data class EntryDriftRow(
    val elapsedMs: Long,
    val totalDeg: Double,
    val twistDeg: Double,
    val swingDeg: Double,
)

/**
 * Measures how far a still phone's reported orientation walks away from where it started.
 *
 * The door watch freezes its baseline for the whole armed session and never rebaselines,
 * so an orientation source that drifts eventually crosses the open-angle threshold with
 * nothing having moved — a false alert in the middle of the night. How fast that happens
 * is a property of the chipset and its fusion, not something to reason about from first
 * principles, which is why this exists at all: it produces the number instead of guessing.
 *
 * It reports the same three quantities the detection policy computes, from the same math,
 * so a row can be read against the thresholds directly:
 *
 * - [EntryDriftRow.twistDeg] is what the open-angle threshold compares against,
 * - [EntryDriftRow.swingDeg] is what the mount-moved residual gate compares against,
 * - [EntryDriftRow.totalDeg] is axis-independent and bounds both, so it stays meaningful
 *   even though a phone measured flat on a table has no commissioned hinge.
 *
 * Rows are thinned to [rowIntervalMs] because the sensor delivers at game rate and eight
 * hours of that is megabytes of a signal that moves in minutes, not milliseconds.
 */
class EntryDriftSampler(
    private val rowIntervalMs: Long = 10_000L,
    private val axis: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0),
    /**
     * Above this the angle is not drifting, it is being moved.
     *
     * Drift and handling are not near each other. Measured over one row interval on the
     * phone this was calibrated against, eight still hours never exceeded 0.035 deg/s across
     * 2843 intervals, while the first interval of the owner picking the phone up was
     * 0.136 deg/s and it reached 17.2 deg/s. This sits between them, and it is still fifty
     * times the drift rate of a phone too drifty to be usable at all — 15 degrees in the two
     * hours of [EntryDriftBudgetPolicy.MINIMUM_USEFUL_HOURS] is 0.002 deg/s. Nothing that
     * belongs in this measurement can cross it.
     */
    private val disturbanceRateDegPerSec: Double = DEFAULT_DISTURBANCE_RATE_DEG_PER_SEC,
) {
    private var baseline: EntryQuaternion? = null
    private var startedAtMs: Long? = null
    private var lastRowAtMs: Long? = null
    private var lastSampleAtMs: Long? = null

    var rowCount: Int = 0
        private set
    /** Baseline of the stretch being measured now, which is not the baseline of the file. */
    private var stretchBaseline: EntryQuaternion? = null
    private var stretchStartMs: Long = 0L
    private var stretchMaxTwistDeg: Double = 0.0
    private var checkpointAtMs: Long = 0L
    private var checkpointTotalDeg: Double = 0.0
    private var pendingMaxTwistDeg: Double = 0.0

    /** Peak door angle of the longest undisturbed stretch, which is the drift. */
    var cleanMaxTwistDeg: Double = 0.0
        private set

    /** How long that stretch lasted. The rate is this pair and nothing else. */
    var cleanMeasuredMs: Long = 0L
        private set

    /** How many times the phone was moved during the recording. */
    var disturbanceCount: Int = 0
        private set

    val started: Boolean get() = baseline != null

    /**
     * Feeds one sample, returning a row when one is due.
     *
     * The first sample becomes the baseline and emits a row of zeros, so every recording
     * starts at its own origin and a reader never has to assume where it began.
     */
    fun onSample(timestampMs: Long, quaternion: EntryQuaternion): EntryDriftRow? {
        lastSampleAtMs = timestampMs
        trackClean(timestampMs, quaternion)
        val frozen = baseline
        if (frozen == null) {
            baseline = quaternion
            startedAtMs = timestampMs
            lastRowAtMs = timestampMs
            rowCount = 1
            return EntryDriftRow(elapsedMs = 0L, totalDeg = 0.0, twistDeg = 0.0, swingDeg = 0.0)
        }

        val start = startedAtMs ?: timestampMs
        val previousRowAt = lastRowAtMs ?: start
        // A sample from before the last row (a clock that went backwards, or a replayed
        // batch) is measured but never emitted out of order.
        if (timestampMs - previousRowAt < rowIntervalMs) {
            return null
        }

        val relative = EntryOrientationMath.relativeRotation(frozen, quaternion)
        val row = EntryDriftRow(
            elapsedMs = timestampMs - start,
            totalDeg = EntryOrientationMath.totalRotationDeg(relative),
            twistDeg = EntryOrientationMath.doorAngleDeltaDeg(relative, axis),
            swingDeg = EntryOrientationMath.swingResidualDeg(relative, axis),
        )
        lastRowAtMs = timestampMs
        rowCount += 1
        return row
    }

    /**
     * Keeps the longest stretch the phone was left alone, and throws away the rest.
     *
     * The peaks above are the file's peaks and stay raw — the recording is evidence and an
     * excursion that happened must be readable in it. These are the measurement's, and the
     * measurement answers a different question: how far the *reading* walks while the phone
     * does not. An owner who lifts the phone at five in the morning has not discovered that
     * their chipset drifts a hundred and seventy degrees; they have ended the measurement.
     * Reported straight, that one movement condemns the phone as unusable on the strength of
     * the eight good hours that came before it, which is the exact inversion of the truth.
     *
     * So each movement closes the stretch it interrupted and opens a new one from a new
     * baseline — the phone was put back down somewhere else, and the constant offset that
     * creates is not drift either. The longest clean stretch wins, and a night that was
     * never touched is one stretch, which is the ordinary case and costs nothing.
     *
     * Judged over a whole row interval rather than sample to sample: at game rate the gap
     * between two readings is milliseconds, and ordinary noise across a few milliseconds is
     * a large number of degrees per second. The interval is the window the ceiling was
     * calibrated against.
     */
    private fun trackClean(nowMs: Long, quaternion: EntryQuaternion) {
        val base = stretchBaseline
        if (base == null) {
            openStretch(nowMs, quaternion)
            return
        }
        val relative = EntryOrientationMath.relativeRotation(base, quaternion)
        val totalDeg = EntryOrientationMath.totalRotationDeg(relative)
        val twistDeg = EntryOrientationMath.doorAngleDeltaDeg(relative, axis)
        // Provisional until the interval it falls in is judged: a peak reached while the
        // phone was in a hand must not survive into the stretch that hand ended.
        if (twistDeg > pendingMaxTwistDeg) pendingMaxTwistDeg = twistDeg

        val sinceCheckpointMs = nowMs - checkpointAtMs
        if (sinceCheckpointMs < rowIntervalMs) return
        val rateDegPerSec = kotlin.math.abs(totalDeg - checkpointTotalDeg) /
            (sinceCheckpointMs / 1_000.0)
        if (rateDegPerSec > disturbanceRateDegPerSec) {
            // The stretch ended at the last checkpoint, not here: everything measured since
            // then shared the interval with the movement and cannot be told apart from it.
            considerLongest(endMs = checkpointAtMs, peakTwistDeg = stretchMaxTwistDeg)
            disturbanceCount += 1
            openStretch(nowMs, quaternion)
            return
        }
        if (pendingMaxTwistDeg > stretchMaxTwistDeg) stretchMaxTwistDeg = pendingMaxTwistDeg
        pendingMaxTwistDeg = 0.0
        checkpointAtMs = nowMs
        checkpointTotalDeg = totalDeg
        // Updated as it runs rather than only when it ends, so a process killed mid-night
        // still leaves the hours it had already earned.
        considerLongest(endMs = nowMs, peakTwistDeg = stretchMaxTwistDeg)
    }

    private fun openStretch(nowMs: Long, quaternion: EntryQuaternion) {
        stretchBaseline = quaternion
        stretchStartMs = nowMs
        stretchMaxTwistDeg = 0.0
        pendingMaxTwistDeg = 0.0
        checkpointAtMs = nowMs
        checkpointTotalDeg = 0.0
    }

    private fun considerLongest(endMs: Long, peakTwistDeg: Double) {
        val durationMs = endMs - stretchStartMs
        if (durationMs <= cleanMeasuredMs) return
        cleanMeasuredMs = durationMs
        cleanMaxTwistDeg = peakTwistDeg
    }

    /** How long this recording has been running, by its own samples. */
    val measuredMs: Long
        get() {
            val start = startedAtMs ?: return 0L
            return ((lastSampleAtMs ?: start) - start).coerceAtLeast(0L)
        }

    /**
     * What this recording has learned about the phone, in the form the rest of the app reads.
     *
     * The rate is the *peak* angle over the elapsed time, not the endpoint over it. Drift
     * wanders rather than marching: a phone can sit at eight degrees for an hour and come back
     * to two, and what would have tripped the gate is the eight. Reporting the endpoint would
     * quietly excuse exactly the excursion the owner needs protecting from.
     *
     * Both halves come from the longest undisturbed stretch rather than from the whole run,
     * so the hours reported are hours the phone actually sat still. That matters twice over:
     * the rate is only honest if its numerator and denominator describe the same stretch, and
     * `EntryDriftMeasurementStore.save` keeps the longer measurement — a duration inflated by
     * time the phone spent in a hand would shadow a shorter honest one for good.
     */
    fun measurement(sourceLabel: String, wallClockMs: Long): EntryDriftMeasurement {
        val elapsed = cleanMeasuredMs
        val hours = elapsed / 3_600_000.0
        return EntryDriftMeasurement(
            sourceLabel = sourceLabel,
            degPerHour = if (hours <= 0.0) 0.0 else cleanMaxTwistDeg / hours,
            measuredMs = elapsed,
            measuredAtWallMs = wallClockMs,
        )
    }

    companion object {
        /** See [disturbanceRateDegPerSec]; calibrated, not chosen. */
        const val DEFAULT_DISTURBANCE_RATE_DEG_PER_SEC = 0.1

        const val CSV_HEADER = "elapsedMs,totalDeg,twistDeg,swingDeg"

        fun formatRow(row: EntryDriftRow): String = buildString {
            append(row.elapsedMs)
            append(',')
            append(format(row.totalDeg))
            append(',')
            append(format(row.twistDeg))
            append(',')
            append(format(row.swingDeg))
        }

        private fun format(value: Double): String = String.format(java.util.Locale.US, "%.4f", value)
    }
}
