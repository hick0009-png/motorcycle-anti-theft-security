package com.example.motorcycleantitheftsensor.location

import android.location.LocationManager
import com.example.motorcycleantitheftsensor.sensor.LocationRegistration
import com.example.motorcycleantitheftsensor.sensor.LocationRegistrationResult
import com.example.motorcycleantitheftsensor.sensor.LocationStartFailure
import com.example.motorcycleantitheftsensor.sensor.LocationTrackingMode
import com.example.motorcycleantitheftsensor.sensor.LocationTrackingRequest
import com.example.motorcycleantitheftsensor.sensor.LocationUpdatesClient
import com.example.motorcycleantitheftsensor.sensor.MovementLocationTracking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What an on-demand lookup could say about where the phone is. */
sealed interface LocationAnswer {
    /** Measured just now, or recently enough that the tracker still calls it usable. */
    data class Fresh(val fix: TrackedLocationFix) : LocationAnswer

    /** The best the phone remembers, and how long ago it was true. */
    data class LastKnown(val fix: TrackedLocationFix, val ageMs: Long) : LocationAnswer

    data class Unavailable(val reason: LocationStartFailure?) : LocationAnswer
}

/**
 * Answers "where is it now" without disturbing whatever is already watching.
 *
 * The obvious implementation is to call `startArmedTracking` and take the first fix, and it
 * is wrong in a way that would only show up during a theft: that method assigns
 * `currentCallback` unconditionally, so calling it while armed hands the armed tracker's
 * fixes to whoever asked last. The pursuit that is supposed to be following the vehicle would
 * go quiet, and nothing would report that it had.
 *
 * So this registers its own listener, cancels it as soon as it has an answer, and never
 * touches the shared tracker except to read a fix the tracker already has. Reading first is
 * also the cheap path: while the vehicle is armed there is usually a recent fix sitting
 * there, and the radio never has to be woken at all.
 *
 * One lookup at a time. Somebody whose vehicle has just been taken will send the command
 * repeatedly, and each of those must not start its own GPS session on a phone whose remaining
 * battery is the whole budget for finding it.
 */
class OnDemandLocationFinder(
    private val tracking: MovementLocationTracking,
    private val client: LocationUpdatesClient,
    private val elapsedMs: () -> Long,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    private val lookupLock = Mutex()

    suspend fun find(): LocationAnswer = lookupLock.withLock {
        tracking.currentUsableFix(elapsedMs())?.let { fix -> return@withLock LocationAnswer.Fresh(fix) }

        var failure: LocationStartFailure? = null
        var registration: LocationRegistration? = null
        // Cancelled in a finally rather than only on coroutine cancellation, so the listener
        // is released on the path that succeeded too. A radio left on after the answer was
        // given is the failure mode nobody notices until the battery is gone.
        val measured = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val result = client.register(REQUEST) { fix ->
                        if (continuation.isActive) continuation.resume(fix)
                    }
                    when (result) {
                        is LocationRegistrationResult.Started -> registration = result.registration
                        is LocationRegistrationResult.Unavailable -> {
                            failure = result.reason
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }
        } finally {
            runCatching { registration?.cancel() }
        }
        if (measured != null) return@withLock LocationAnswer.Fresh(measured)

        // Nothing new arrived. What the phone already remembers is worth far more than an
        // apology, as long as it is handed over with its age attached.
        val remembered = runCatching { client.lastKnownFixes() }.getOrNull().orEmpty()
            .filter { fix -> fix.latitude.isFinite() && fix.longitude.isFinite() }
            .maxByOrNull { fix -> fix.elapsedRealtimeMs }
        return@withLock if (remembered == null) {
            LocationAnswer.Unavailable(failure)
        } else {
            LocationAnswer.LastKnown(remembered, (elapsedMs() - remembered.elapsedRealtimeMs).coerceAtLeast(0L))
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /**
         * Every provider the phone has, at the fastest rate, for the few seconds this runs.
         * A lookup the owner is waiting on is not the place to save power by asking slowly —
         * the saving is in stopping, which happens as soon as one fix arrives.
         */
        val REQUEST = LocationTrackingRequest(
            mode = LocationTrackingMode.PURSUIT,
            minTimeMs = 0L,
            minDistanceMeters = 0f,
            providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
        )
    }
}
