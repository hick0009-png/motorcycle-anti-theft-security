package com.example.motorcycleantitheftsensor.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.example.motorcycleantitheftsensor.location.LocationFixArbiter
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
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

enum class LocationTrackingMode { ARMED, PURSUIT }

data class LocationTrackingRequest(
    val mode: LocationTrackingMode,
    val minTimeMs: Long,
    val minDistanceMeters: Float,
    val providers: List<String>,
)

fun interface LocationRegistration {
    fun cancel()
}

enum class LocationStartFailure {
    PERMISSION_DENIED,
    NO_PROVIDERS_AVAILABLE,
    REGISTRATION_FAILED,
}

sealed interface LocationRegistrationResult {
    data class Started(val registration: LocationRegistration) : LocationRegistrationResult
    data class Unavailable(val reason: LocationStartFailure) : LocationRegistrationResult
}

interface LocationUpdatesClient {
    fun register(
        request: LocationTrackingRequest,
        onFix: (TrackedLocationFix) -> Unit,
    ): LocationRegistrationResult

    fun lastKnownFixes(): List<TrackedLocationFix>
}

interface MovementLocationTracking {
    fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean
    fun enterPursuitMode(): Boolean
    fun exitPursuitMode(): Boolean
    fun stopTracking()
    fun isTracking(): Boolean
    fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix?
}

class AndroidLocationUpdatesClient(
    private val context: Context,
    private val mainLooper: Looper = Looper.getMainLooper(),
) : LocationUpdatesClient {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override fun register(
        request: LocationTrackingRequest,
        onFix: (TrackedLocationFix) -> Unit,
    ): LocationRegistrationResult {
        if (!hasLocationPermission()) {
            return LocationRegistrationResult.Unavailable(LocationStartFailure.PERMISSION_DENIED)
        }

        // Only register allowed providers from request (which must only be GPS and NETWORK)
        val requestedProviders = request.providers.filter { provider ->
            provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER
        }

        val enabledProviders = requestedProviders.filter { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }

        if (enabledProviders.isEmpty()) {
            return LocationRegistrationResult.Unavailable(LocationStartFailure.NO_PROVIDERS_AVAILABLE)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(
                    TrackedLocationFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
                        wallClockMs = System.currentTimeMillis(),
                        accuracyMeters = location.accuracy,
                    )
                )
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val successfulProviders = mutableListOf<String>()
        for (provider in enabledProviders) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    request.minTimeMs,
                    request.minDistanceMeters,
                    listener,
                    mainLooper,
                )
                successfulProviders += provider
            } catch (_: Exception) {
                // Ignore failure for individual provider registration
            }
        }

        if (successfulProviders.isEmpty()) {
            return LocationRegistrationResult.Unavailable(LocationStartFailure.REGISTRATION_FAILED)
        }

        val registration = LocationRegistration {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
                // Ignore removal failure
            }
        }

        return LocationRegistrationResult.Started(registration)
    }

    @SuppressLint("MissingPermission")
    override fun lastKnownFixes(): List<TrackedLocationFix> {
        if (!hasLocationPermission()) return emptyList()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.filter { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)?.let { location ->
                    TrackedLocationFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        elapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L,
                        wallClockMs = System.currentTimeMillis(),
                        accuracyMeters = location.accuracy,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun hasLocationPermission(): Boolean = context.checkSelfPermission(
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

class LocationObservationProvider(
    private val client: LocationUpdatesClient,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : MovementLocationTracking {

    constructor(context: Context) : this(
        AndroidLocationUpdatesClient(context.applicationContext),
        SystemClock::elapsedRealtime,
        System::currentTimeMillis,
    )

    private val lock = Any()
    private val arbiter = LocationFixArbiter(maxAccuracyMeters = 100f)
    private var trackingMode = TrackingMode.STOPPED
    private var currentCallback: ((TrackedLocationFix) -> Unit)? = null
    private var currentRegistration: LocationRegistration? = null
    private var lastDiagnostic = "stopped"
    private var registrationGeneration: Long = 0L
    private var activeGeneration: Long = 0L

    private enum class TrackingMode { STOPPED, ARMED, PURSUIT }

    private val armedRequest = LocationTrackingRequest(
        mode = LocationTrackingMode.ARMED,
        minTimeMs = 10_000L,
        minDistanceMeters = 5f,
        providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
    )

    private val pursuitRequest = LocationTrackingRequest(
        mode = LocationTrackingMode.PURSUIT,
        minTimeMs = 5_000L,
        minDistanceMeters = 5f,
        providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
    )

    private fun acceptFix(generation: Long, fix: TrackedLocationFix) {
        val nowElapsedMs = elapsedClock()
        val callback = synchronized(lock) {
            if (trackingMode == TrackingMode.STOPPED || generation != activeGeneration) return
            if (!arbiter.accept(fix, nowElapsedMs)) return
            lastDiagnostic = "fix age_ms=${nowElapsedMs - fix.elapsedRealtimeMs} accuracy_m=${fix.accuracyMeters}"
            currentCallback
        }
        callback?.invoke(fix)
    }

    override fun isTracking(): Boolean = synchronized(lock) {
        trackingMode != TrackingMode.STOPPED && currentRegistration != null
    }

    override fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean {
        val gen = synchronized(lock) {
            currentCallback = onFix
            if (trackingMode == TrackingMode.ARMED && currentRegistration != null) {
                return true
            }
            ++registrationGeneration
        }

        val result = client.register(armedRequest) { fix ->
            acceptFix(gen, fix)
        }

        var toCancel: LocationRegistration? = null
        val success = synchronized(lock) {
            if (gen != registrationGeneration) {
                if (result is LocationRegistrationResult.Started) {
                    toCancel = result.registration
                }
                false
            } else {
                when (result) {
                    is LocationRegistrationResult.Started -> {
                        toCancel = currentRegistration
                        currentRegistration = result.registration
                        activeGeneration = gen
                        trackingMode = TrackingMode.ARMED
                        lastDiagnostic = "tracking armed"
                        true
                    }
                    is LocationRegistrationResult.Unavailable -> {
                        if (currentRegistration == null) {
                            trackingMode = TrackingMode.STOPPED
                        }
                        lastDiagnostic = "provider/permission failure"
                        false
                    }
                }
            }
        }
        toCancel?.cancel()
        return success
    }

    override fun enterPursuitMode(): Boolean {
        val gen = synchronized(lock) {
            if (trackingMode == TrackingMode.STOPPED) {
                return false
            }
            if (trackingMode == TrackingMode.PURSUIT && currentRegistration != null) {
                return true
            }
            ++registrationGeneration
        }

        val result = client.register(pursuitRequest) { fix ->
            acceptFix(gen, fix)
        }

        var toCancel: LocationRegistration? = null
        val success = synchronized(lock) {
            if (gen != registrationGeneration || trackingMode == TrackingMode.STOPPED) {
                if (result is LocationRegistrationResult.Started) {
                    toCancel = result.registration
                }
                false
            } else {
                when (result) {
                    is LocationRegistrationResult.Started -> {
                        toCancel = currentRegistration
                        currentRegistration = result.registration
                        activeGeneration = gen
                        trackingMode = TrackingMode.PURSUIT
                        lastDiagnostic = "tracking pursuit"
                        true
                    }
                    is LocationRegistrationResult.Unavailable -> {
                        lastDiagnostic = "provider/permission failure"
                        false
                    }
                }
            }
        }
        toCancel?.cancel()
        return success
    }

    override fun exitPursuitMode(): Boolean {
        val gen = synchronized(lock) {
            if (trackingMode != TrackingMode.PURSUIT) {
                return trackingMode == TrackingMode.ARMED && currentRegistration != null
            }
            ++registrationGeneration
        }

        val result = client.register(armedRequest) { fix ->
            acceptFix(gen, fix)
        }

        var toCancel: LocationRegistration? = null
        val success = synchronized(lock) {
            if (gen != registrationGeneration || trackingMode == TrackingMode.STOPPED) {
                if (result is LocationRegistrationResult.Started) {
                    toCancel = result.registration
                }
                false
            } else {
                when (result) {
                    is LocationRegistrationResult.Started -> {
                        toCancel = currentRegistration
                        currentRegistration = result.registration
                        activeGeneration = gen
                        trackingMode = TrackingMode.ARMED
                        lastDiagnostic = "tracking armed"
                        true
                    }
                    is LocationRegistrationResult.Unavailable -> {
                        lastDiagnostic = "provider/permission failure"
                        false
                    }
                }
            }
        }
        toCancel?.cancel()
        return success
    }

    override fun stopTracking() {
        val toCancel = synchronized(lock) {
            trackingMode = TrackingMode.STOPPED
            currentCallback = null
            arbiter.reset()
            registrationGeneration++
            activeGeneration = 0L
            lastDiagnostic = "stopped"
            val reg = currentRegistration
            currentRegistration = null
            reg
        }
        toCancel?.cancel()
    }

    override fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix? {
        synchronized(lock) {
            val fix = arbiter.currentFix() ?: return null
            if (fix.elapsedRealtimeMs > nowElapsedMs) return null
            if (nowElapsedMs - fix.elapsedRealtimeMs > 30_000L) return null
            if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0f || fix.accuracyMeters > 100f) return null
            if (!fix.latitude.isFinite() || fix.latitude !in -90.0..90.0) return null
            if (!fix.longitude.isFinite() || fix.longitude !in -180.0..180.0) return null
            return fix
        }
    }

    fun currentObservation(): SensorObservation {
        val nowElapsedMs = elapsedClock()
        val nowWallClockMs = wallClock()
        synchronized(lock) {
            val fix = arbiter.currentFix()
            if (fix == null) {
                return SensorObservation(
                    kind = SensorKind.LOCATION,
                    eventElapsedMs = nowElapsedMs,
                    wallClockMs = nowWallClockMs,
                    normalizedValue = 0.0,
                    baselineDelta = 0.0,
                    valid = true,
                    diagnostic = lastDiagnostic
                )
            }
            val ageMs = nowElapsedMs - fix.elapsedRealtimeMs
            if (ageMs > 30_000L || fix.accuracyMeters > 100f || !fix.latitude.isFinite() || fix.elapsedRealtimeMs > nowElapsedMs) {
                return SensorObservation(
                    kind = SensorKind.LOCATION,
                    eventElapsedMs = nowElapsedMs,
                    wallClockMs = nowWallClockMs,
                    normalizedValue = 0.0,
                    baselineDelta = 0.0,
                    valid = true,
                    diagnostic = "$lastDiagnostic (stale/inaccurate)"
                )
            }
            return SensorObservation(
                kind = SensorKind.LOCATION,
                eventElapsedMs = nowElapsedMs,
                wallClockMs = nowWallClockMs,
                normalizedValue = 1.0,
                baselineDelta = 0.0,
                valid = true,
                diagnostic = String.format(Locale.US, "fix age_ms=%d accuracy_m=%.1f", ageMs, fix.accuracyMeters)
            )
        }
    }
}
