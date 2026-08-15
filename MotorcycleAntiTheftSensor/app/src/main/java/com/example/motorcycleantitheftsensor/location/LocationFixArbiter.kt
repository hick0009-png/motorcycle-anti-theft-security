package com.example.motorcycleantitheftsensor.location

class LocationFixArbiter(
    private val maxAccuracyMeters: Float = 100f,
) {
    private var lastAcceptedFix: TrackedLocationFix? = null

    @Synchronized
    fun accept(candidate: TrackedLocationFix, nowElapsedMs: Long): Boolean {
        if (!candidate.latitude.isFinite() || candidate.latitude !in -90.0..90.0) return false
        if (!candidate.longitude.isFinite() || candidate.longitude !in -180.0..180.0) return false
        if (!candidate.accuracyMeters.isFinite() || candidate.accuracyMeters < 0f || candidate.accuracyMeters > maxAccuracyMeters) return false
        if (candidate.elapsedRealtimeMs > nowElapsedMs) return false

        val current = lastAcceptedFix
        if (current == null) {
            lastAcceptedFix = candidate
            return true
        }

        val elapsedDelta = candidate.elapsedRealtimeMs - current.elapsedRealtimeMs
        if (elapsedDelta < 0L) {
            return false
        }

        if (elapsedDelta == 0L) {
            if (candidate.accuracyMeters < current.accuracyMeters) {
                lastAcceptedFix = candidate
                return true
            }
            return false
        }

        // elapsedDelta > 0
        if (elapsedDelta < 1000L) {
            // Within same 1-second bucket, prefer lower accuracy radius
            if (candidate.accuracyMeters <= current.accuracyMeters) {
                lastAcceptedFix = candidate
                return true
            }
            return false
        }

        // New second bucket
        lastAcceptedFix = candidate
        return true
    }

    @Synchronized
    fun reset() {
        lastAcceptedFix = null
    }

    @Synchronized
    fun currentFix(): TrackedLocationFix? = lastAcceptedFix
}
