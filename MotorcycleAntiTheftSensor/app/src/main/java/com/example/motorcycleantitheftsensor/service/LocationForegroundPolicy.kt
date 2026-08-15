package com.example.motorcycleantitheftsensor.service

import android.os.Build

data class LocationForegroundDecision(
    val mayUseLocationType: Boolean,
    val degradationReason: String? = null,
)

class LocationForegroundPolicy(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    fun evaluate(
        hasFineLocation: Boolean,
        hasCoarseLocation: Boolean,
        isAppInForeground: Boolean,
        hasBackgroundLocation: Boolean = false,
    ): LocationForegroundDecision {
        val hasPermission = hasFineLocation || hasCoarseLocation
        if (!hasPermission) {
            return LocationForegroundDecision(
                mayUseLocationType = false,
                degradationReason = "Location permission not granted",
            )
        }

        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!isAppInForeground && !hasBackgroundLocation) {
                return LocationForegroundDecision(
                    mayUseLocationType = false,
                    degradationReason = "Background location foreground start restricted",
                )
            }
        }

        return LocationForegroundDecision(
            mayUseLocationType = true,
            degradationReason = null,
        )
    }
}
