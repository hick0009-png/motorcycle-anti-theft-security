package com.example.motorcycleantitheftsensor.location

import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class LocationPresentationFactory(
    private val labelResolver: LocationLabelResolver,
    private val labelTimeoutMs: Long = 1_500L,
) {
    suspend fun create(fix: TrackedLocationFix): LocationPresentation {
        val label = try {
            withTimeoutOrNull(labelTimeoutMs) {
                labelResolver.resolve(fix)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val mapsUrl = String.format(
            Locale.US,
            "https://maps.google.com/?q=%.6f,%.6f",
            fix.latitude,
            fix.longitude
        )
        val roundedAccuracy = ceil(fix.accuracyMeters.toDouble()).toInt().coerceAtLeast(1)

        return LocationPresentation(
            labelTh = label,
            mapsUrl = mapsUrl,
            accuracyMeters = roundedAccuracy,
        )
    }
}
