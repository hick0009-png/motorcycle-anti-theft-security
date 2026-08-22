package com.example.motorcycleantitheftsensor.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.example.motorcycleantitheftsensor.protection.SensorSamplingProfile
import com.example.motorcycleantitheftsensor.protection.SensorSource

class SignificantMotionAdapter(
    private val sensorManager: SensorManager?,
) : SensorSourceAdapter {

    override val source: SensorSource = SensorSource.SIGNIFICANT_MOTION

    @Volatile
    private var running = false
    private var callback: ((RawSensorSample) -> Unit)? = null
    private var sensor: Sensor? = null

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (!running || event == null) return
            val cb = callback ?: return
            val sample = RawSensorSample(
                source = SensorSource.SIGNIFICANT_MOTION,
                timestampNs = event.timestamp,
                values = floatArrayOf(1.0f),
                accuracy = 3,
            )
            cb(sample)

            // Significant motion is one-shot: automatically re-request if still running
            if (running && sensor != null && sensorManager != null) {
                try {
                    sensorManager.requestTriggerSensor(this, sensor)
                } catch (_: Exception) {}
            }
        }
    }

    override val isRunning: Boolean
        get() = running

    override fun start(samplingProfile: SensorSamplingProfile, onSample: (RawSensorSample) -> Unit): Boolean {
        if (running) return true
        if (sensorManager == null) return false

        val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return false
        this.sensor = motionSensor
        this.callback = onSample

        val success = try {
            sensorManager.requestTriggerSensor(triggerListener, motionSensor)
        } catch (_: Exception) {
            false
        }

        running = success
        return success
    }

    override fun stop() {
        if (!running) return
        running = false
        callback = null
        if (sensor != null && sensorManager != null) {
            try {
                sensorManager.cancelTriggerSensor(triggerListener, sensor)
            } catch (_: Exception) {}
        }
    }
}
