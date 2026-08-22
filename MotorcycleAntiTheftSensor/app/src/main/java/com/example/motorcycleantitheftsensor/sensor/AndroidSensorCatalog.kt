package com.example.motorcycleantitheftsensor.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import com.example.motorcycleantitheftsensor.protection.SensorSource

object SensorTypeMap {
    fun androidType(source: SensorSource): Int {
        return when (source) {
            SensorSource.SIGNIFICANT_MOTION -> Sensor.TYPE_SIGNIFICANT_MOTION
            SensorSource.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            SensorSource.LINEAR_ACCELERATION -> Sensor.TYPE_LINEAR_ACCELERATION
            SensorSource.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
            SensorSource.ROTATION_VECTOR -> Sensor.TYPE_ROTATION_VECTOR
            SensorSource.GAME_ROTATION_VECTOR -> Sensor.TYPE_GAME_ROTATION_VECTOR
            SensorSource.MAGNETIC_FIELD -> Sensor.TYPE_MAGNETIC_FIELD
            SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            SensorSource.AMBIENT_LIGHT -> Sensor.TYPE_LIGHT
            SensorSource.PROXIMITY -> Sensor.TYPE_PROXIMITY
        }
    }
}

data class SensorDescriptor(
    val source: SensorSource,
    val androidType: Int,
    val name: String,
    val vendor: String,
    val reportingMode: Int,
    val isWakeUp: Boolean,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val maximumRange: Float,
    val resolution: Float,
    val powerMa: Float,
    val isAvailable: Boolean,
)

interface SensorCatalog {
    fun descriptors(): Map<SensorSource, SensorDescriptor>
    fun descriptor(source: SensorSource): SensorDescriptor
    fun isAvailable(source: SensorSource): Boolean
}

class AndroidSensorCatalog(
    private val sensorManager: SensorManager?
) : SensorCatalog {

    private val cachedDescriptors: Map<SensorSource, SensorDescriptor> by lazy {
        SensorSource.entries.associateWith { source ->
            val androidType = SensorTypeMap.androidType(source)
            val defaultSensor = try {
                sensorManager?.getDefaultSensor(androidType)
            } catch (_: Exception) {
                null
            }

            if (defaultSensor != null) {
                SensorDescriptor(
                    source = source,
                    androidType = androidType,
                    name = defaultSensor.name ?: "Unknown",
                    vendor = defaultSensor.vendor ?: "Unknown",
                    reportingMode = defaultSensor.reportingMode,
                    isWakeUp = defaultSensor.isWakeUpSensor,
                    minDelayUs = defaultSensor.minDelay,
                    maxDelayUs = defaultSensor.maxDelay,
                    maximumRange = defaultSensor.maximumRange,
                    resolution = defaultSensor.resolution,
                    powerMa = defaultSensor.power,
                    isAvailable = true,
                )
            } else {
                SensorDescriptor(
                    source = source,
                    androidType = androidType,
                    name = "Unavailable",
                    vendor = "None",
                    reportingMode = -1,
                    isWakeUp = false,
                    minDelayUs = 0,
                    maxDelayUs = 0,
                    maximumRange = 0f,
                    resolution = 0f,
                    powerMa = 0f,
                    isAvailable = false,
                )
            }
        }
    }

    override fun descriptors(): Map<SensorSource, SensorDescriptor> = cachedDescriptors

    override fun descriptor(source: SensorSource): SensorDescriptor =
        cachedDescriptors[source] ?: SensorDescriptor(
            source = source,
            androidType = SensorTypeMap.androidType(source),
            name = "Unavailable",
            vendor = "None",
            reportingMode = -1,
            isWakeUp = false,
            minDelayUs = 0,
            maxDelayUs = 0,
            maximumRange = 0f,
            resolution = 0f,
            powerMa = 0f,
            isAvailable = false,
        )

    override fun isAvailable(source: SensorSource): Boolean = descriptor(source).isAvailable
}
