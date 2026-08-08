package com.example.motorcycleantitheftsensor.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation

/**
 * SEN-02: LightIntrusionDetector
 * Monitors Ambient Light sensor (Sensor.TYPE_LIGHT) placed inside motorcycle seat / ECU compartment.
 * Triggers an instant alarm when light level exceeds threshold (default 10 Lux), detecting seat opening or ECU box tampering.
 */
class LightIntrusionDetector(
    context: Context,
    private val onObservation: (SensorObservation) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var isListening = false
    fun startListening(): Boolean {
        if (isListening) return true
        val sensor = lightSensor ?: return false
        isListening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        return isListening
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_LIGHT) return

        val currentLux = event.values[0]
        if (!currentLux.isFinite()) return
        onObservation(
            SensorObservation(
                kind = SensorKind.LIGHT,
                eventElapsedMs = event.timestamp / NANOS_PER_MILLISECOND,
                wallClockMs = System.currentTimeMillis(),
                normalizedValue = currentLux.toDouble(),
                baselineDelta = 0.0,
                valid = true,
                diagnostic = "ambient_lux",
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
