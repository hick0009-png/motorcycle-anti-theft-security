package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorSamplingProfile
import com.example.motorcycleantitheftsensor.protection.SensorSource

data class RawSensorSample(
    val source: SensorSource,
    val timestampNs: Long,
    val values: FloatArray,
    val accuracy: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawSensorSample

        if (source != other.source) return false
        if (timestampNs != other.timestampNs) return false
        if (!values.contentEquals(other.values)) return false
        if (accuracy != other.accuracy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + values.contentHashCode()
        result = 31 * result + accuracy
        return result
    }
}

interface SensorSourceAdapter {
    val source: SensorSource
    val isRunning: Boolean
    fun start(samplingProfile: SensorSamplingProfile, onSample: (RawSensorSample) -> Unit): Boolean
    fun stop()
}
