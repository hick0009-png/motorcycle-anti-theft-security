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
) {
    private var baseline: EntryQuaternion? = null
    private var startedAtMs: Long? = null
    private var lastRowAtMs: Long? = null

    var rowCount: Int = 0
        private set
    var maxTotalDeg: Double = 0.0
        private set
    var maxTwistDeg: Double = 0.0
        private set
    var maxSwingDeg: Double = 0.0
        private set

    val started: Boolean get() = baseline != null

    /**
     * Feeds one sample, returning a row when one is due.
     *
     * The first sample becomes the baseline and emits a row of zeros, so every recording
     * starts at its own origin and a reader never has to assume where it began.
     */
    fun onSample(timestampMs: Long, quaternion: EntryQuaternion): EntryDriftRow? {
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
            track(frozen, quaternion)
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
        remember(row)
        return row
    }

    /** Keeps the peaks honest between emitted rows, so thinning cannot hide a spike. */
    private fun track(baseline: EntryQuaternion, quaternion: EntryQuaternion) {
        val relative = EntryOrientationMath.relativeRotation(baseline, quaternion)
        remember(
            EntryDriftRow(
                elapsedMs = 0L,
                totalDeg = EntryOrientationMath.totalRotationDeg(relative),
                twistDeg = EntryOrientationMath.doorAngleDeltaDeg(relative, axis),
                swingDeg = EntryOrientationMath.swingResidualDeg(relative, axis),
            ),
        )
    }

    private fun remember(row: EntryDriftRow) {
        if (row.totalDeg > maxTotalDeg) maxTotalDeg = row.totalDeg
        if (row.twistDeg > maxTwistDeg) maxTwistDeg = row.twistDeg
        if (row.swingDeg > maxSwingDeg) maxSwingDeg = row.swingDeg
    }

    companion object {
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
