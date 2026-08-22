package com.example.motorcycleantitheftsensor.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.example.motorcycleantitheftsensor.protection.SensorSamplingProfile
import com.example.motorcycleantitheftsensor.protection.SensorSource

class ContinuousSensorAdapter(
    override val source: SensorSource,
    private val sensorManager: SensorManager?,
    private val handler: Handler?,
    private val requestedSamplingPeriodUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
) : SensorSourceAdapter, SensorEventListener {

    @Volatile
    private var running = false
    private var callback: ((RawSensorSample) -> Unit)? = null

    override val isRunning: Boolean
        get() = running

    override fun start(samplingProfile: SensorSamplingProfile, onSample: (RawSensorSample) -> Unit): Boolean {
        if (running) return true
        if (sensorManager == null) return false

        val androidType = SensorTypeMap.androidType(source)
        val sensor = sensorManager.getDefaultSensor(androidType) ?: return false

        this.callback = onSample

        val periodUs = when (samplingProfile) {
            SensorSamplingProfile.BATTERY_SAVER -> 200_000 // 5 Hz
            SensorSamplingProfile.BALANCED -> 100_000 // 10 Hz
            SensorSamplingProfile.RESPONSIVE -> 20_000 // 50 Hz
        }.coerceAtLeast(sensor.minDelay)

        val success = if (handler != null) {
            sensorManager.registerListener(this, sensor, periodUs, handler)
        } else {
            sensorManager.registerListener(this, sensor, periodUs)
        }

        running = success
        return success
    }

    override fun stop() {
        if (!running) return
        running = false
        callback = null
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!running || event == null) return
        val cb = callback ?: return
        val valuesCopy = FloatArray(event.values.size)
        System.arraycopy(event.values, 0, valuesCopy, 0, event.values.size)
        val sample = RawSensorSample(
            source = source,
            timestampNs = event.timestamp,
            values = valuesCopy,
            accuracy = event.accuracy,
        )
        cb(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
