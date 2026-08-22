package com.example.motorcycleantitheftsensor.sensor.audio

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AudioFrameFeatures(
    val rmsDbfs: Double,
    val peak: Double,
    val clippingRatio: Double,
    val atElapsedMs: Long,
)

data class AudioBaseline(
    val medianDbfs: Double,
    val p95Dbfs: Double,
    val madDb: Double,
    val stable: Boolean,
)

class RobustAudioCalibrator(
    private val maxFrameHistory: Int = 300,
) {
    private val frameHistory = ArrayDeque<AudioFrameFeatures>()
    private var currentBaseline: AudioBaseline? = null
    private var startElapsedMs: Long? = null

    private val rmsBuffer = DoubleArray(maxFrameHistory)
    private val devBuffer = DoubleArray(maxFrameHistory)

    fun extractFeatures(samples: ShortArray, readSize: Int, atElapsedMs: Long): AudioFrameFeatures {
        val validSize = readSize.coerceIn(0, samples.size)
        if (validSize == 0) {
            return AudioFrameFeatures(
                rmsDbfs = -120.0,
                peak = 0.0,
                clippingRatio = 0.0,
                atElapsedMs = atElapsedMs,
            )
        }

        var sumSq = 0.0
        var maxPeak = 0
        var clippedCount = 0

        for (i in 0 until validSize) {
            val s = samples[i].toInt()
            val absS = abs(s)
            if (absS > maxPeak) maxPeak = absS
            if (absS >= 32760) clippedCount++
            val norm = s / 32768.0
            sumSq += norm * norm
        }

        val rms = sqrt(sumSq / validSize.toDouble())
        val rmsDbfs = 20.0 * log10(max(rms, 1e-6))
        val peak = maxPeak / 32768.0
        val clippingRatio = clippedCount.toDouble() / validSize.toDouble()

        return AudioFrameFeatures(
            rmsDbfs = rmsDbfs,
            peak = peak,
            clippingRatio = clippingRatio,
            atElapsedMs = atElapsedMs,
        )
    }

    @Synchronized
    fun feedFrame(features: AudioFrameFeatures) {
        if (startElapsedMs == null) {
            startElapsedMs = features.atElapsedMs
        }
        if (frameHistory.size >= maxFrameHistory) {
            frameHistory.removeFirst()
        }
        frameHistory.addLast(features)
    }

    @Synchronized
    fun evaluateCalibration(nowElapsedMs: Long): AudioBaseline? {
        val count = frameHistory.size
        if (count == 0) return null

        val start = startElapsedMs ?: frameHistory.first().atElapsedMs
        val elapsedSinceStart = nowElapsedMs - start

        var totalClipping = 0.0
        var idx = 0
        for (frame in frameHistory) {
            rmsBuffer[idx] = frame.rmsDbfs
            totalClipping += frame.clippingRatio
            idx++
        }

        Arrays.sort(rmsBuffer, 0, count)

        val median = computePercentile(rmsBuffer, count, 50.0)
        val p95 = computePercentile(rmsBuffer, count, 95.0)

        for (i in 0 until count) {
            devBuffer[i] = abs(rmsBuffer[i] - median)
        }
        Arrays.sort(devBuffer, 0, count)
        val mad = computePercentile(devBuffer, count, 50.0)

        val avgClipping = totalClipping / count

        val isStable = count >= 80 &&
            avgClipping < 0.01 &&
            (p95 - median) <= 12.0 &&
            mad <= 4.0

        val baseline = AudioBaseline(
            medianDbfs = median,
            p95Dbfs = p95,
            madDb = mad,
            stable = isStable,
        )
        currentBaseline = baseline
        return baseline
    }

    @Synchronized
    fun adaptWindow(nowElapsedMs: Long, isActivityFrozen: Boolean): AudioBaseline? {
        val existing = currentBaseline ?: evaluateCalibration(nowElapsedMs) ?: return null
        if (isActivityFrozen) {
            return existing
        }

        val count = frameHistory.size
        if (count == 0) return existing

        var idx = 0
        for (frame in frameHistory) {
            rmsBuffer[idx++] = frame.rmsDbfs
        }
        Arrays.sort(rmsBuffer, 0, count)

        val windowMedian = computePercentile(rmsBuffer, count, 50.0)
        val targetMedian = existing.medianDbfs * 0.90 + windowMedian * 0.10
        val delta = (targetMedian - existing.medianDbfs).coerceIn(-1.5, 1.5)
        val newMedian = existing.medianDbfs + delta

        val windowP95 = computePercentile(rmsBuffer, count, 95.0)

        for (i in 0 until count) {
            devBuffer[i] = abs(rmsBuffer[i] - newMedian)
        }
        Arrays.sort(devBuffer, 0, count)
        val newMad = computePercentile(devBuffer, count, 50.0)

        val adapted = AudioBaseline(
            medianDbfs = newMedian,
            p95Dbfs = max(newMedian, existing.p95Dbfs + delta),
            madDb = newMad,
            stable = existing.stable,
        )
        currentBaseline = adapted
        return adapted
    }

    @Synchronized
    fun isDegraded(nowElapsedMs: Long): Boolean {
        val start = startElapsedMs ?: return false
        val baseline = currentBaseline ?: evaluateCalibration(nowElapsedMs)
        return (nowElapsedMs - start >= 30_000L) && (baseline == null || !baseline.stable)
    }

    @Synchronized
    fun reset() {
        frameHistory.clear()
        currentBaseline = null
        startElapsedMs = null
    }

    private fun computePercentile(sorted: DoubleArray, count: Int, percentile: Double): Double {
        if (count == 0) return 0.0
        val rank = (percentile / 100.0) * (count - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = min(lowerIndex + 1, count - 1)
        val fraction = rank - lowerIndex
        return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex])
    }
}
