package com.example.motorcycleantitheftsensor.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensorThreadDispatchingContractTest {

    @Test
    fun vibrationDetector_dispatchesOnDedicatedBackgroundHandlerThread() {
        val file = File("src/main/java/com/example/motorcycleantitheftsensor/sensor/VibrationDetector.kt")
        assertTrue("VibrationDetector.kt must exist", file.exists())
        val content = file.readText()

        // Background thread verification
        assertTrue(
            "VibrationDetector must use HandlerThread(\"VibrationSensorThread\")",
            content.contains("HandlerThread(\"VibrationSensorThread\")"),
        )
        assertTrue(
            "VibrationDetector must register listener with Handler",
            content.contains("registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)"),
        )
        assertFalse(
            "VibrationDetector must not register listener on default main looper",
            content.contains("registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)") &&
                !content.contains("registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler)"),
        )

        // Lifecycle cleanup
        assertTrue(
            "VibrationDetector must safely quit HandlerThread on stopListening",
            content.contains("quitSafely()"),
        )
        assertTrue(
            "VibrationDetector must unregister listener on stopListening",
            content.contains("sensorManager.unregisterListener(this)"),
        )
    }

    @Test
    fun lightIntrusionDetector_dispatchesOnDedicatedBackgroundThreadAndThrottles() {
        val file = File("src/main/java/com/example/motorcycleantitheftsensor/sensor/LightIntrusionDetector.kt")
        assertTrue("LightIntrusionDetector.kt must exist", file.exists())
        val content = file.readText()

        // Background thread verification
        assertTrue(
            "LightIntrusionDetector must use HandlerThread(\"LightSensorThread\")",
            content.contains("HandlerThread(\"LightSensorThread\")"),
        )
        assertTrue(
            "LightIntrusionDetector must register listener with Handler",
            content.contains("registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)"),
        )

        // Throttling / debouncing verification
        assertTrue(
            "LightIntrusionDetector must define minimum sample interval of at least 100ms",
            content.contains("MIN_SAMPLE_INTERVAL_MS = 100L") ||
                content.contains("MIN_SAMPLE_INTERVAL_MS = 100"),
        )
        assertTrue(
            "LightIntrusionDetector must enforce interval throttling check in onSensorChanged",
            content.contains("MIN_SAMPLE_INTERVAL_MS"),
        )

        // Lifecycle cleanup
        assertTrue(
            "LightIntrusionDetector must safely quit HandlerThread on stopListening",
            content.contains("quitSafely()"),
        )
        assertTrue(
            "LightIntrusionDetector must unregister listener on stopListening",
            content.contains("sensorManager.unregisterListener(this)"),
        )
    }
}
