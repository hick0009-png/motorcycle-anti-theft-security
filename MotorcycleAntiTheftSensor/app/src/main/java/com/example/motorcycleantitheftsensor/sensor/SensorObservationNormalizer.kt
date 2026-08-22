package com.example.motorcycleantitheftsensor.sensor

import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorDiagnosticCode
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSource
import com.example.motorcycleantitheftsensor.protection.SensorUnit
import kotlin.math.abs
import kotlin.math.sqrt

class SensorObservationNormalizer {

    fun normalize(
        sample: RawSensorSample,
        readiness: SensorReadiness,
        role: SensorRole,
        generationId: Long,
        wallClockMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = try { SystemClock.elapsedRealtime() } catch (_: Throwable) { 0L },
    ): SensorObservation {
        val source = sample.source
        val values = sample.values

        // Basic sanity check: ensure all float values are finite
        if (values.any { it.isNaN() || it.isInfinite() }) {
            return SensorObservation(
                kind = mapKind(source),
                source = source,
                capability = source.capability,
                role = role,
                generationId = generationId,
                unit = mapUnit(source),
                eventElapsedMs = elapsedMs,
                wallClockMs = wallClockMs,
                normalizedValue = 0.0,
                baselineDelta = 0.0,
                valid = false,
                diagnostic = "Non-finite sensor values encountered",
                diagnosticCode = SensorDiagnosticCode.INVALID_VECTOR,
            )
        }

        val baseline = when (readiness) {
            is SensorReadiness.Ready -> readiness.baseline
            else -> null
        }

        var normalizedValue = 0.0
        var baselineDelta = 0.0
        val unit = mapUnit(source)

        when (source) {
            SensorSource.SIGNIFICANT_MOTION -> {
                normalizedValue = 1.0
                baselineDelta = 1.0
            }
            SensorSource.ACCELEROMETER, SensorSource.LINEAR_ACCELERATION -> {
                val mag = if (values.size >= 3) {
                    sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
                } else values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = mag

                val baseMag = if (baseline != null && baseline.size >= 3) {
                    sqrt((baseline[0] * baseline[0] + baseline[1] * baseline[1] + baseline[2] * baseline[2]))
                } else baseline?.firstOrNull() ?: 0.0
                baselineDelta = abs(mag - baseMag)
            }
            SensorSource.GYROSCOPE -> {
                val angularRate = if (values.size >= 3) {
                    sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
                } else values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = angularRate
                val baseAngularRate = if (baseline != null && baseline.size >= 3) {
                    sqrt((baseline[0] * baseline[0] + baseline[1] * baseline[1] + baseline[2] * baseline[2]))
                } else baseline?.firstOrNull() ?: 0.0
                baselineDelta = abs(angularRate - baseAngularRate)
            }
            SensorSource.ROTATION_VECTOR, SensorSource.GAME_ROTATION_VECTOR, SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> {
                // Vector / quaternion representation
                val mag = if (values.size >= 3) {
                    sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
                } else values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = mag

                if (baseline != null && baseline.size >= 3) {
                    // Approximate angle difference in degrees: 2 * arccos(|q1 . q2|) or Euclidean vector delta
                    var dot = 0.0
                    for (i in 0 until minOf(values.size, baseline.size)) {
                        dot += values[i] * baseline[i]
                    }
                    val diffDeg = (1.0 - abs(dot).coerceIn(0.0, 1.0)) * 180.0
                    baselineDelta = diffDeg
                } else {
                    baselineDelta = 0.0
                }
            }
            SensorSource.MAGNETIC_FIELD -> {
                val fieldStrength = if (values.size >= 3) {
                    sqrt((values[0] * values[0] + values[1] * values[1] + values[2] * values[2]).toDouble())
                } else values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = fieldStrength

                val baseStrength = if (baseline != null && baseline.size >= 3) {
                    sqrt((baseline[0] * baseline[0] + baseline[1] * baseline[1] + baseline[2] * baseline[2]))
                } else baseline?.firstOrNull() ?: 0.0
                baselineDelta = abs(fieldStrength - baseStrength)
            }
            SensorSource.AMBIENT_LIGHT -> {
                val lux = values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = lux
                val baseLux = baseline?.firstOrNull() ?: 0.0
                baselineDelta = if (baseLux > 0.0) lux / baseLux else if (lux > 0.0) lux else 1.0
            }
            SensorSource.PROXIMITY -> {
                val distance = values.firstOrNull()?.toDouble() ?: 0.0
                normalizedValue = distance
                val baseDist = baseline?.firstOrNull() ?: 0.0
                baselineDelta = abs(distance - baseDist)
            }
        }

        return SensorObservation(
            kind = mapKind(source),
            source = source,
            capability = source.capability,
            role = role,
            generationId = generationId,
            unit = unit,
            eventElapsedMs = elapsedMs,
            wallClockMs = wallClockMs,
            normalizedValue = normalizedValue,
            baselineDelta = baselineDelta,
            valid = readiness is SensorReadiness.Ready,
            diagnostic = if (readiness is SensorReadiness.Calibrating) "Sensor calibrating" else null,
        )
    }

    private fun mapKind(source: SensorSource): SensorKind {
        return when (source.capability) {
            SensorCapability.LIGHT -> SensorKind.LIGHT
            else -> SensorKind.VIBRATION
        }
    }

    private fun mapUnit(source: SensorSource): SensorUnit {
        return when (source) {
            SensorSource.ACCELEROMETER, SensorSource.LINEAR_ACCELERATION -> SensorUnit.METERS_PER_SECOND_SQUARED
            SensorSource.SIGNIFICANT_MOTION -> SensorUnit.TRIGGER
            SensorSource.GYROSCOPE -> SensorUnit.RADIANS_PER_SECOND
            SensorSource.ROTATION_VECTOR, SensorSource.GAME_ROTATION_VECTOR, SensorSource.GEOMAGNETIC_ROTATION_VECTOR -> SensorUnit.DEGREES
            SensorSource.MAGNETIC_FIELD -> SensorUnit.MICROTESLA
            SensorSource.AMBIENT_LIGHT -> SensorUnit.LUX_RATIO
            SensorSource.PROXIMITY -> SensorUnit.NORMALIZED_STATE
        }
    }
}
