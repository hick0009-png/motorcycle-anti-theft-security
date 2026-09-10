package com.example.motorcycleantitheftsensor.protection

/**
 * What one phone's own measurement found out about itself.
 *
 * The door watch alerts when a frozen reference angle is exceeded, and the reported
 * orientation of a still phone walks on its own at a rate that belongs to the chipset and
 * its fusion. On the phone this was written against that rate is 0.076 degrees an hour,
 * which puts a fifteen-degree threshold eight days away and makes drift a non-problem. On
 * another phone it could be twenty times that and put the threshold inside a working day.
 *
 * There is no list of phones that can answer this, and there never will be: the only honest
 * source is the phone in the owner's hand, measured while it sits still. So the app measures
 * its own, and this is what it measured.
 *
 * @param sourceLabel [EntryOrientationSource.label] of the sensor that was measured. A
 *   measurement of one source says nothing about another, and a phone can change source.
 * @param degPerHour the peak angle reached, divided by the hours it took to reach it. Peak
 *   rather than endpoint because drift wanders rather than marching: what trips the gate is
 *   the furthest the angle ever got, not where it happened to be at the end.
 */
data class EntryDriftMeasurement(
    val sourceLabel: String,
    val degPerHour: Double,
    val measuredMs: Long,
    val measuredAtWallMs: Long,
)

/** How long this phone's door watch can be believed, given what it measured about itself. */
sealed interface EntryDriftVerdict {
    /** Nothing measured, measured too briefly, or measured on a source no longer in use. */
    data object NotMeasured : EntryDriftVerdict

    /** Drift cannot reach the alert angle within any session an owner would arm. */
    data class Trustworthy(val hoursToThreshold: Double) : EntryDriftVerdict

    /** Drift reaches the alert angle inside a long session. The owner is told the number. */
    data class Limited(val hoursToThreshold: Double) : EntryDriftVerdict

    /** Drift reaches the alert angle so fast that no absence is covered. */
    data class Unusable(val hoursToThreshold: Double) : EntryDriftVerdict
}

/**
 * Turns a measured drift rate into what the owner is allowed to be told.
 *
 * The thresholds are about how people actually arm a door watch, not about the sensor: a
 * session lasts a night or a working day, so a phone that holds for a full day has the
 * margin to be called trustworthy, and one that cannot hold two hours cannot cover even an
 * errand. Between those, the watch works and the owner is owed the number.
 */
object EntryDriftBudgetPolicy {

    /**
     * Below this, a measurement is not evidence. A few minutes of a still phone catches a
     * gross fault, but drift is measured in degrees per hour and a short window turns
     * ordinary sample noise into an alarming rate.
     */
    const val MIN_USEFUL_MEASUREMENT_MS = 5L * 60_000L

    /** A full day of margin over the longest session an owner would realistically arm. */
    const val TRUSTWORTHY_HOURS = 24.0

    /** Below this the watch cannot cover leaving the house at all. */
    const val MINIMUM_USEFUL_HOURS = 2.0

    /** Hours of armed session before drift alone would reach [alertAngleDeg]. */
    fun hoursToThreshold(degPerHour: Double, alertAngleDeg: Int): Double {
        if (degPerHour <= 0.0) return Double.MAX_VALUE
        return alertAngleDeg.toDouble() / degPerHour
    }

    /**
     * @param currentSource what the phone would arm on now. A measurement taken on another
     *   source is discarded rather than trusted: the sources do not drift alike, which is
     *   the entire reason the measurement records which one it read.
     */
    fun verdict(
        measurement: EntryDriftMeasurement?,
        alertAngleDeg: Int,
        currentSource: EntryOrientationSource?,
    ): EntryDriftVerdict {
        if (measurement == null) return EntryDriftVerdict.NotMeasured
        if (measurement.measuredMs < MIN_USEFUL_MEASUREMENT_MS) return EntryDriftVerdict.NotMeasured
        if (currentSource != null && measurement.sourceLabel != currentSource.label) {
            return EntryDriftVerdict.NotMeasured
        }
        val hours = hoursToThreshold(measurement.degPerHour, alertAngleDeg)
        return when {
            hours >= TRUSTWORTHY_HOURS -> EntryDriftVerdict.Trustworthy(hours)
            hours >= MINIMUM_USEFUL_HOURS -> EntryDriftVerdict.Limited(hours)
            else -> EntryDriftVerdict.Unusable(hours)
        }
    }

    /** Whole hours the owner can be told to trust, always rounded down. */
    fun trustedHours(verdict: EntryDriftVerdict): Int? = when (verdict) {
        is EntryDriftVerdict.Limited -> verdict.hoursToThreshold.toInt()
        is EntryDriftVerdict.Unusable -> verdict.hoursToThreshold.toInt()
        else -> null
    }
}
