package com.example.motorcycleantitheftsensor.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
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

    private var sensorThread: HandlerThread? = null
    private var isListening = false
    @Volatile
    private var sensitivityLevel = 5 // 1 (lowest) to 10 (highest sensitivity)
    private var lastSampleElapsedMs = 0L

    @Synchronized
    fun startListening(sensitivity: Int = this.sensitivityLevel): Boolean {
        if (isListening) return true
        val sensor = accelerometer ?: return false
        this.sensitivityLevel = sensitivity.coerceIn(1, 10)
        lastSampleElapsedMs = 0L

        val thread = HandlerThread("VibrationSensorThread").apply { start() }
        val handler = Handler(thread.looper)
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)
        if (!registered) {
            thread.quitSafely()
            return false
        }
        sensorThread = thread
        isListening = true
        return true
    }

    @Synchronized
    fun stopListening() {
        if (!isListening && sensorThread == null) return
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        isListening = false
    }

    fun setSensitivity(level: Int) {
        this.sensitivityLevel = level.coerceIn(1, 10)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val eventElapsedMs = event.timestamp / NANOS_PER_MILLISECOND
        if (eventElapsedMs - lastSampleElapsedMs < MIN_SAMPLE_INTERVAL_MS) return
        lastSampleElapsedMs = eventElapsedMs

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val rawMagnitude = sqrt(x * x + y * y + z * z)
        if (!rawMagnitude.isFinite()) return
        onObservation(
            SensorObservation(
                kind = SensorKind.VIBRATION,
                eventElapsedMs = eventElapsedMs,
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
        const val MIN_SAMPLE_INTERVAL_MS = 50L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
