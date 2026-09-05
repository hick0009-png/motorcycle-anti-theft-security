package com.example.motorcycleantitheftsensor.location

import kotlin.math.*

data class TrackedLocationFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeMs: Long,
    val wallClockMs: Long,
    val accuracyMeters: Float,
)

data class ParkingAnchor(
    val fix: TrackedLocationFix,
    val armedSessionId: String
)

sealed interface MovementDecision {
    object AwaitingUsableFix : MovementDecision
    object InsideAnchor : MovementDecision
    data class Candidate(val consecutiveOutsideFixes: Int, val distanceMeters: Double) : MovementDecision
    data class Confirmed(val fix: TrackedLocationFix, val distanceMeters: Double) : MovementDecision
}

class MovementDisplacementPolicy {
    companion object {
        const val MAX_FIX_AGE_MS = 30_000L
        const val MAX_FIX_ACCURACY_METERS = 100f
        const val BASE_DISPLACEMENT_METERS = 100.0
        const val ACCURACY_MARGIN_METERS = 30.0
        const val REQUIRED_OUTSIDE_FIXES = 2
        const val MIN_CONFIRMATION_SEPARATION_MS = 15_000L
        const val MAX_CONFIRMATION_SEPARATION_MS = 60_000L

        /**
         * How far this pair of fixes must disagree before the vehicle counts as moved.
         *
         * The floor is what makes it honest: two fixes each good to fifty metres cannot
         * demonstrate a hundred-metre move, so the threshold rises to meet them. Exposed
         * because the status report has to state the number it is really being judged
         * against, and a report that recomputed the rule for itself would eventually quote
         * a threshold the detector does not use.
         */
        fun displacementThresholdMeters(
            anchorAccuracyMeters: Float,
            fixAccuracyMeters: Float,
        ): Double = max(
            BASE_DISPLACEMENT_METERS,
            (anchorAccuracyMeters + fixAccuracyMeters + ACCURACY_MARGIN_METERS).toDouble(),
        )

        fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val clampedA = a.coerceIn(0.0, 1.0)
            val c = 2 * atan2(sqrt(clampedA), sqrt(1.0 - clampedA))
            return r * c
        }
    }

    private val lock = Any()
    private var currentAnchor: ParkingAnchor? = null
    private var outsideCandidateTimestampMs: Long? = null
    private var outsideFixCount = 0

    fun isValidStoredFix(fix: TrackedLocationFix): Boolean =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
            fix.accuracyMeters.isFinite() &&
            fix.accuracyMeters in 0f..MAX_FIX_ACCURACY_METERS

    fun isUsableFix(fix: TrackedLocationFix, nowElapsedMs: Long): Boolean =
        isValidStoredFix(fix) &&
            fix.elapsedRealtimeMs <= nowElapsedMs &&
            nowElapsedMs - fix.elapsedRealtimeMs <= MAX_FIX_AGE_MS

    fun reset(anchor: ParkingAnchor) {
        synchronized(lock) {
            currentAnchor = anchor
            outsideCandidateTimestampMs = null
            outsideFixCount = 0
        }
    }

    fun clear() {
        synchronized(lock) {
            currentAnchor = null
            outsideCandidateTimestampMs = null
            outsideFixCount = 0
        }
    }

    fun evaluate(fix: TrackedLocationFix, nowElapsedMs: Long): MovementDecision {
        synchronized(lock) {
            val anchor = currentAnchor ?: return MovementDecision.AwaitingUsableFix

            if (!isUsableFix(fix, nowElapsedMs)) {
                // An unusable fix neither confirms movement nor replaces the last good candidate.
                return if (outsideFixCount > 0) {
                    MovementDecision.Candidate(outsideFixCount, 0.0)
                } else {
                    MovementDecision.AwaitingUsableFix
                }
            }

            val distance = calculateHaversineDistance(
                anchor.fix.latitude, anchor.fix.longitude,
                fix.latitude, fix.longitude
            )

            val threshold = displacementThresholdMeters(
                anchor.fix.accuracyMeters,
                fix.accuracyMeters,
            )

            if (distance <= threshold) {
                outsideCandidateTimestampMs = null
                outsideFixCount = 0
                return MovementDecision.InsideAnchor
            }

            // Outside the threshold
            if (outsideFixCount == 0 || outsideCandidateTimestampMs == null) {
                outsideFixCount = 1
                outsideCandidateTimestampMs = fix.elapsedRealtimeMs
                return MovementDecision.Candidate(outsideFixCount, distance)
            }

            // We have a previous candidate
            val timeSinceFirstCandidate = fix.elapsedRealtimeMs - outsideCandidateTimestampMs!!
            if (timeSinceFirstCandidate < 0L || timeSinceFirstCandidate > MAX_CONFIRMATION_SEPARATION_MS) {
                outsideFixCount = 1
                outsideCandidateTimestampMs = fix.elapsedRealtimeMs
                return MovementDecision.Candidate(outsideFixCount, distance)
            }
            if (timeSinceFirstCandidate >= MIN_CONFIRMATION_SEPARATION_MS) {
                outsideFixCount++
                return MovementDecision.Confirmed(fix, distance)
            } else {
                return MovementDecision.Candidate(outsideFixCount, distance)
            }
        }
    }
}
