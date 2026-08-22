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

    private var sensorThread: HandlerThread? = null
    private var isListening = false
    private var lastSampleElapsedMs = 0L

    @Synchronized
    fun startListening(): Boolean {
        if (isListening) return true
        val sensor = lightSensor ?: return false
        lastSampleElapsedMs = 0L

        val thread = HandlerThread("LightSensorThread").apply { start() }
        val handler = Handler(thread.looper)
        val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
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

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_LIGHT) return

        val eventElapsedMs = event.timestamp / NANOS_PER_MILLISECOND
        if (eventElapsedMs - lastSampleElapsedMs < MIN_SAMPLE_INTERVAL_MS) return
        lastSampleElapsedMs = eventElapsedMs

        val currentLux = event.values[0]
        if (!currentLux.isFinite()) return
        onObservation(
            SensorObservation(
                kind = SensorKind.LIGHT,
                eventElapsedMs = eventElapsedMs,
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
        const val MIN_SAMPLE_INTERVAL_MS = 100L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
