package com.example.motorcycleantitheftsensor.sensor

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build

/**
 * Data class representing hardware sensor discovery report.
 */
data class SensorStatusItem(
    val code: String,          // e.g. "ACCEL", "GYRO", "LIGHT", "MIC", "BARO", "GPS", "BIOMETRIC"
    val displayName: String,   // e.g. "Accelerometer"
    val isAvailable: Boolean,
    val vendorName: String = "N/A",
    val powerMa: Float = 0f,
    val maxRange: Float = 0f,
    val typeId: Int = -1
)

/**
 * SEN-05: SensorScanner
 * Dynamically queries Android SensorManager, LocationManager, and PackageManager
 * to discover all physical sensors present on the device.
 */
object SensorScanner {

    fun scanHardwareSensors(context: Context): List<SensorStatusItem> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val packageManager = context.packageManager

        val resultList = mutableListOf<SensorStatusItem>()

        // 1. Accelerometer (Vibration & Motion)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        resultList.add(
            SensorStatusItem(
                code = "ACCEL",
                displayName = "Accelerometer (Vibration)",
                isAvailable = accel != null,
                vendorName = accel?.vendor ?: "N/A",
                powerMa = accel?.power ?: 0f,
                maxRange = accel?.maximumRange ?: 0f,
                typeId = Sensor.TYPE_ACCELEROMETER
            )
        )

        // 2. Gyroscope (Tilt & Rotation)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        resultList.add(
            SensorStatusItem(
                code = "GYRO",
                displayName = "Gyroscope (Rotation)",
                isAvailable = gyro != null,
                vendorName = gyro?.vendor ?: "N/A",
                powerMa = gyro?.power ?: 0f,
                maxRange = gyro?.maximumRange ?: 0f,
                typeId = Sensor.TYPE_GYROSCOPE
            )
        )

        // 3. Ambient Light Sensor (Under-Seat Light)
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        resultList.add(
            SensorStatusItem(
                code = "LIGHT",
                displayName = "Ambient Light (Under-Seat)",
                isAvailable = light != null,
                vendorName = light?.vendor ?: "N/A",
                powerMa = light?.power ?: 0f,
                maxRange = light?.maximumRange ?: 0f,
                typeId = Sensor.TYPE_LIGHT
            )
        )

        // 4. Microphone (Audio Noise Meter)
        val hasMicFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val isMicAvailable = hasMicFeature && minBufferSize > 0
        resultList.add(
            SensorStatusItem(
                code = "MIC",
                displayName = "Microphone (Noise Level)",
                isAvailable = isMicAvailable,
                vendorName = if (isMicAvailable) "Android Audio Subsystem" else "N/A",
                powerMa = 0.5f,
                maxRange = 120f
            )
        )

        // 5. Barometer (Pressure & Height)
        val baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        resultList.add(
            SensorStatusItem(
                code = "BARO",
                displayName = "Barometer (Atmospheric Height)",
                isAvailable = baro != null,
                vendorName = baro?.vendor ?: "N/A",
                powerMa = baro?.power ?: 0f,
                maxRange = baro?.maximumRange ?: 0f,
                typeId = Sensor.TYPE_PRESSURE
            )
        )

        // 6. GPS / GNSS Location Provider
        val hasGpsFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        val isGpsProviderEnabled = try { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        resultList.add(
            SensorStatusItem(
                code = "GPS",
                displayName = "GPS / GNSS Location",
                isAvailable = hasGpsFeature || isGpsProviderEnabled,
                vendorName = if (hasGpsFeature) "Hardware GPS Modem" else "N/A",
                powerMa = 15.0f,
                maxRange = 40000000f
            )
        )

        // 7. Biometrics (Fingerprint / Face Unlock)
        val hasFingerprint = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val hasFace = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)
        } else {
            false
        }
        val isBiometricAvailable = hasFingerprint || hasFace
        resultList.add(
            SensorStatusItem(
                code = "BIOMETRIC",
                displayName = "Fingerprint / Face Unlock",
                isAvailable = isBiometricAvailable,
                vendorName = if (isBiometricAvailable) "Android Hardware Biometrics" else "N/A",
                powerMa = 0.2f
            )
        )

        return resultList
    }
}
