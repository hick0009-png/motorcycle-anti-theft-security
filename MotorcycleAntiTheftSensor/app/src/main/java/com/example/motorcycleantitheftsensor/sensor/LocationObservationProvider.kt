package com.example.motorcycleantitheftsensor.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.protection.SensorKind
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import java.util.Locale

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeMs: Long,
    val accuracyMeters: Float,
)

sealed interface LocationEvidence {
    data class Available(
        val ageMs: Long,
        val accuracyMeters: Float,
    ) : LocationEvidence

    data class Unavailable(val reason: String) : LocationEvidence
}

class LocationEvidencePolicy(
    private val maxAgeMs: Long = 30_000L,
    private val maxAccuracyMeters: Float = 100f,
) {
    init {
        require(maxAgeMs >= 0L) { "maxAgeMs must not be negative" }
        require(maxAccuracyMeters >= 0f) { "maxAccuracyMeters must not be negative" }
    }

    fun evaluate(
        fix: LocationFix?,
        nowElapsedMs: Long,
    ): LocationEvidence {
        if (fix == null) return LocationEvidence.Unavailable("no recent fix")
        val ageMs = nowElapsedMs - fix.elapsedRealtimeMs
        return when {
            ageMs < 0L -> LocationEvidence.Unavailable("invalid fix time")
            ageMs > maxAgeMs -> LocationEvidence.Unavailable("stale fix")
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0f ||
                fix.accuracyMeters > maxAccuracyMeters -> LocationEvidence.Unavailable("inaccurate fix")
            else -> LocationEvidence.Available(
                ageMs = ageMs,
                accuracyMeters = fix.accuracyMeters,
            )
        }
    }
}

class LocationObservationProvider(
    context: Context,
    private val policy: LocationEvidencePolicy = LocationEvidencePolicy(),
) {
    private val applicationContext = context.applicationContext
    private val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun currentObservation(): SensorObservation {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val nowWallClockMs = System.currentTimeMillis()
        if (!hasLocationPermission()) {
            return unavailableObservation(
                nowElapsedMs = nowElapsedMs,
                nowWallClockMs = nowWallClockMs,
                reason = "location permission unavailable",
            )
        }

        val fix = latestFix()
        return when (val evidence = policy.evaluate(fix, nowElapsedMs)) {
            is LocationEvidence.Available -> SensorObservation(
                kind = SensorKind.LOCATION,
                eventElapsedMs = nowElapsedMs,
                wallClockMs = nowWallClockMs,
                normalizedValue = 1.0,
                baselineDelta = 0.0,
                valid = true,
                diagnostic = String.format(
                    Locale.US,
                    "fix lat=%.6f lon=%.6f age_ms=%d accuracy_m=%.1f",
                    fix!!.latitude,
                    fix.longitude,
                    evidence.ageMs,
                    evidence.accuracyMeters,
                ),
            )

            is LocationEvidence.Unavailable -> unavailableObservation(
                nowElapsedMs = nowElapsedMs,
                nowWallClockMs = nowWallClockMs,
                reason = evidence.reason,
            )
        }
    }

    private fun hasLocationPermission(): Boolean = applicationContext.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED || applicationContext.checkSelfPermission(
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun latestFix(): LocationFix? = locationManager.getProviders(true)
        .mapNotNull(locationManager::getLastKnownLocation)
        .maxByOrNull(Location::getElapsedRealtimeNanos)
        ?.let { location ->
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                elapsedRealtimeMs = location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                accuracyMeters = location.accuracy,
            )
        }

    private fun unavailableObservation(
        nowElapsedMs: Long,
        nowWallClockMs: Long,
        reason: String,
    ): SensorObservation = SensorObservation(
        kind = SensorKind.LOCATION,
        eventElapsedMs = nowElapsedMs,
        wallClockMs = nowWallClockMs,
        normalizedValue = 0.0,
        baselineDelta = 0.0,
        valid = true,
        diagnostic = reason,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
