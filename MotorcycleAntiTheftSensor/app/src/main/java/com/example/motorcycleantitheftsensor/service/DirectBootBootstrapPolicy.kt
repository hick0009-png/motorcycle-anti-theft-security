package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionMarker

object DirectBootBootstrapPolicy {
    private const val ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"

    fun shouldStart(marker: DirectBootProtectionMarker): Boolean =
        marker.armed && marker.autoRecoveryAfterBoot

    fun isLockedBootAction(action: String?): Boolean = action == ACTION_LOCKED_BOOT_COMPLETED

    fun shouldHandoffToFullRecovery(isUserUnlocked: Boolean): Boolean = isUserUnlocked
}

object DirectBootMovementPolicy {
    private const val MOVEMENT_DELTA_THRESHOLD = 2.0f

    fun isMovement(deltaMagnitude: Float): Boolean = deltaMagnitude >= MOVEMENT_DELTA_THRESHOLD
}
