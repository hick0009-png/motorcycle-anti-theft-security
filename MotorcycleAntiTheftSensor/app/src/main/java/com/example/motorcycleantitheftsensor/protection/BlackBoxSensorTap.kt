package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Counts what the sensors delivered, from inside the path they were already delivering on.
 *
 * Nothing here registers a listener. Android hands every client of a sensor the fastest rate
 * any one of them asked for, so a black box that registered its own listener at a rate it
 * liked would quietly raise the rate of the watch itself — the guard set to five hertz would
 * start running at fifty, drawing ten times the power, with no error anywhere to explain it.
 * The samples this tap sees are the samples the detection path was handed anyway.
 *
 * Everything below runs on the sensor thread, which is the same thread the detection logic
 * runs on. So it allocates nothing, locks nothing, builds no string and touches no disk: it
 * adds a handful of arithmetic to work that was already happening. A minute's worth is drained
 * by the writer thread into one row.
 *
 * Values are copied out of the sample rather than kept. `RawSensorSample` holds its `values`
 * by reference and the platform reuses those arrays, so anything held would be rewritten
 * under us by the next event.
 */
class BlackBoxSensorTap {

    private var samples: Int = 0
    private var accelerationMaxSq: Double = 0.0
    private var accelerationSumSq: Double = 0.0
    private var accelerationCount: Int = 0
    private var rotationMaxDeg: Double = 0.0
    private var rotationSource: SensorSource? = null
    private var hasRotationBaseline: Boolean = false
    private var baselineW: Float = 0f
    private var baselineX: Float = 0f
    private var baselineY: Float = 0f
    private var baselineZ: Float = 0f
    private var luxMax: Double = -1.0
    private var accuracyMin: Int = Int.MAX_VALUE

    /**
     * Published last on the sensor thread and read first by the drain, which is what makes
     * everything written before it visible to the other thread. A lock would be correct and
     * is not available here: this thread holds the detection logic, and a thread waiting on a
     * lock is a phone that is not watching. The cost of the weaker guarantee is that a sample
     * landing exactly as a minute is drained may be counted in the neighbouring minute. That
     * is acceptable in a statistic and would not be in a ledger.
     */
    @Volatile
    private var published: Int = 0

    fun onSample(source: SensorSource, values: FloatArray, accuracy: Int) {
        samples += 1
        if (accuracy < accuracyMin) accuracyMin = accuracy
        when (source) {
            SensorSource.ACCELEROMETER,
            SensorSource.LINEAR_ACCELERATION,
            -> recordAcceleration(values)

            SensorSource.ROTATION_VECTOR,
            SensorSource.GAME_ROTATION_VECTOR,
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR,
            -> recordRotation(source, values)

            SensorSource.AMBIENT_LIGHT -> recordLux(values)

            // The gyroscope reports a rate, not an angle, and the rotation column is degrees
            // of turn. A number in the wrong unit under the right heading is worse than a
            // blank, because a blank cannot be believed by mistake.
            SensorSource.GYROSCOPE,
            SensorSource.MAGNETIC_FIELD,
            SensorSource.PROXIMITY,
            SensorSource.SIGNIFICANT_MOTION,
            -> Unit
        }
        published += 1
    }

    /**
     * Takes the minute and starts the next one. Called from the writer thread only.
     *
     * The rotation baseline is dropped with it: each minute's turn is measured from where the
     * phone was at the start of that minute, so a slow drift over hours does not accumulate
     * into a number that looks like someone moving the bike.
     */
    fun drain(): BlackBoxSensorSummary {
        // Read first, and before any plain field: this volatile read is the other half of the
        // edge described above, and without it the fields below could be arbitrarily stale.
        if (published == 0) return BlackBoxSensorSummary()
        val summary = BlackBoxSensorSummary(
            samples = samples.takeIf { count -> count > 0 },
            accelerationMaxG = accelerationMaxSq.takeIf { accelerationCount > 0 }
                ?.let { maxSq -> sqrt(maxSq) / GRAVITY },
            accelerationRmsG = accelerationCount.takeIf { count -> count > 0 }
                ?.let { count -> sqrt(accelerationSumSq / count) / GRAVITY },
            rotationMaxDeg = rotationMaxDeg.takeIf { hasRotationBaseline },
            lux = luxMax.takeIf { max -> max >= 0.0 },
            accuracyMin = accuracyMin.takeIf { min -> min != Int.MAX_VALUE },
        )
        reset()
        return summary
    }

    private fun reset() {
        samples = 0
        accelerationMaxSq = 0.0
        accelerationSumSq = 0.0
        accelerationCount = 0
        rotationMaxDeg = 0.0
        rotationSource = null
        hasRotationBaseline = false
        luxMax = -1.0
        accuracyMin = Int.MAX_VALUE
        published = 0
    }

    private fun recordAcceleration(values: FloatArray) {
        if (values.size < 3) return
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val magnitudeSq = x * x + y * y + z * z
        if (magnitudeSq > accelerationMaxSq) accelerationMaxSq = magnitudeSq
        accelerationSumSq += magnitudeSq
        accelerationCount += 1
    }

    /**
     * Follows the same source the door watch would trust, in the same order.
     *
     * More than one of these can be registered at once, and their quaternions do not describe
     * the same thing — a game rotation reading measured against a geomagnetic baseline is a
     * turn that never happened, and the column would report the phone swinging on a night it
     * sat still. So one source holds the column, and a better one arriving takes it over and
     * starts its own baseline rather than inheriting a stranger's.
     */
    private fun recordRotation(source: SensorSource, values: FloatArray) {
        if (values.size < 4) return
        val holder = rotationSource
        if (holder != null && source != holder) {
            if (rotationRank(source) >= rotationRank(holder)) return
            hasRotationBaseline = false
            rotationMaxDeg = 0.0
        }
        rotationSource = source
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = values[3]
        if (!hasRotationBaseline) {
            baselineX = x
            baselineY = y
            baselineZ = z
            baselineW = w
            hasRotationBaseline = true
            return
        }
        val dot = abs(
            baselineW.toDouble() * w + baselineX.toDouble() * x +
                baselineY.toDouble() * y + baselineZ.toDouble() * z,
        )
        val degrees = Math.toDegrees(2.0 * acos(min(1.0, dot)))
        if (degrees > rotationMaxDeg) rotationMaxDeg = degrees
    }

    /** Best first, and deliberately the order in `EntryOrientationSourcePolicy.PREFERENCE`. */
    private fun rotationRank(source: SensorSource): Int = when (source) {
        SensorSource.GAME_ROTATION_VECTOR -> 0
        SensorSource.ROTATION_VECTOR -> 1
        else -> 2
    }

    private fun recordLux(values: FloatArray) {
        if (values.isEmpty()) return
        val lux = values[0].toDouble()
        if (lux > luxMax) luxMax = lux
    }

    private companion object {
        const val GRAVITY = 9.80665
    }
}
