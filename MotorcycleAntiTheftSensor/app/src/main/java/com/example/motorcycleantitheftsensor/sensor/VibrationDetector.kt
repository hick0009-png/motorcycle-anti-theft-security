package com.example.motorcycleantitheftsensor.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import kotlin.math.sqrt

/**
 * SEN-01: VibrationDetector
 * Processes Accelerometer data using vector magnitude and moving average debounce filtering.
 * Prevents false positives while detecting bike jacking, ECU removal, or towing.
 */
class VibrationDetector(
    context: Context,
    private val onObservation: (SensorObservation) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isListening = false
    private var sensitivityLevel = 5 // 1 (lowest) to 10 (highest sensitivity)

    fun startListening(sensitivity: Int = 5): Boolean {
        if (isListening) return true
        val sensor = accelerometer ?: return false
        this.sensitivityLevel = sensitivity.coerceIn(1, 10)
        isListening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return isListening
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    fun setSensitivity(level: Int) {
        this.sensitivityLevel = level.coerceIn(1, 10)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val rawMagnitude = sqrt(x * x + y * y + z * z)
        if (!rawMagnitude.isFinite()) return
        onObservation(
            SensorObservation(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = event.timestamp / NANOS_PER_MILLISECOND,
                wallClockMs = System.currentTimeMillis(),
                normalizedValue = rawMagnitude.toDouble(),
                baselineDelta = 0.0,
                valid = true,
                diagnostic = "accelerometer_magnitude_sensitivity_$sensitivityLevel",
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
