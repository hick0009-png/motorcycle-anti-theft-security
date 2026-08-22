package com.example.motorcycleantitheftsensor.sensor

import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.SensorSource
import java.util.concurrent.ConcurrentHashMap

sealed interface SensorReadiness {
    data class Calibrating(val progress: Float) : SensorReadiness
    data class Ready(val baseline: DoubleArray, val noiseEnvelope: Double) : SensorReadiness {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Ready
            if (!baseline.contentEquals(other.baseline)) return false
            if (noiseEnvelope != other.noiseEnvelope) return false
            return true
        }

        override fun hashCode(): Int {
            var result = baseline.contentHashCode()
            result = 31 * result + noiseEnvelope.hashCode()
            return result
        }
    }
    data class Failed(val reason: String) : SensorReadiness
}

class SensorCalibrationManager(
    private val calibrationDurationMs: Long = 10_000L,
    private val maxSamplesPerSource: Int = 512,
) {
    private val sourceGenerations = ConcurrentHashMap<SensorSource, Long>()
    private val sourceStartTimesMs = ConcurrentHashMap<SensorSource, Long>()
    private val targetSources = ConcurrentHashMap.newKeySet<SensorSource>()
    private val sampleStore = ConcurrentHashMap<SensorSource, MutableList<FloatArray>>()
    private val readinessCache = ConcurrentHashMap<SensorSource, SensorReadiness>()

    private fun currentMonotonicTimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    fun startCalibration(
        generationId: Long,
        sources: Set<SensorSource>,
        nowMs: Long = currentMonotonicTimeMs(),
    ) {
        sources.forEach { source ->
            startCalibrationForSource(source, generationId, nowMs)
        }
    }

    fun startCalibrationForSource(
        source: SensorSource,
        generationId: Long,
        nowMs: Long = currentMonotonicTimeMs(),
    ) {
        sourceGenerations[source] = generationId
        sourceStartTimesMs[source] = nowMs
        targetSources.add(source)
        sampleStore[source] = mutableListOf()
        readinessCache.remove(source)
    }

    fun recordSample(generationId: Long, sample: RawSensorSample) {
        recordSampleForSource(sample.source, generationId, sample)
    }

    fun recordSampleForSource(source: SensorSource, generationId: Long, sample: RawSensorSample) {
        val activeGen = sourceGenerations[source] ?: return
        if (generationId != activeGen) return
        val list = sampleStore[source] ?: return
        synchronized(list) {
            if (list.size < maxSamplesPerSource) {
                list.add(sample.values)
            }
        }
    }

    fun getReadiness(
        source: SensorSource,
        generationId: Long,
        nowMs: Long = currentMonotonicTimeMs(),
    ): SensorReadiness {
        val activeGen = sourceGenerations[source]
        if (activeGen == null || generationId != activeGen || !targetSources.contains(source)) {
            return SensorReadiness.Failed("Invalid generation or source not configured for calibration")
        }

        readinessCache[source]?.let { cached ->
            return cached
        }

        if (source == SensorSource.SIGNIFICANT_MOTION) {
            val ready = SensorReadiness.Ready(baseline = doubleArrayOf(0.0), noiseEnvelope = 0.0)
            readinessCache[source] = ready
            return ready
        }

        val startTime = sourceStartTimesMs[source] ?: nowMs
        val elapsed = nowMs - startTime
        if (elapsed < calibrationDurationMs) {
            val progress = (elapsed.toFloat() / calibrationDurationMs).coerceIn(0f, 1f)
            return SensorReadiness.Calibrating(progress)
        }

        val list = sampleStore[source]
        val samples = synchronized(list ?: return SensorReadiness.Failed("No samples gathered")) {
            list.toList()
        }

        if (samples.isEmpty()) {
            val failed = SensorReadiness.Failed("Zero samples gathered during calibration window")
            readinessCache[source] = failed
            return failed
        }

        val dim = samples.first().size
        val sums = DoubleArray(dim)
        samples.forEach { sample ->
            for (i in 0 until minOf(dim, sample.size)) {
                sums[i] += sample[i].toDouble()
            }
        }
        val baseline = DoubleArray(dim) { i -> sums[i] / samples.size }

        var maxDeltaSq = 0.0
        samples.forEach { sample ->
            var deltaSq = 0.0
            for (i in 0 until minOf(dim, sample.size)) {
                val d = sample[i].toDouble() - baseline[i]
                deltaSq += d * d
            }
            if (deltaSq > maxDeltaSq) maxDeltaSq = deltaSq
        }
        val noiseEnvelope = kotlin.math.sqrt(maxDeltaSq)

        val ready = SensorReadiness.Ready(baseline = baseline, noiseEnvelope = noiseEnvelope)
        readinessCache[source] = ready
        sampleStore.remove(source)
        return ready
    }

    fun invalidateGeneration() {
        sourceGenerations.clear()
        sourceStartTimesMs.clear()
        targetSources.clear()
        sampleStore.clear()
        readinessCache.clear()
    }
}
