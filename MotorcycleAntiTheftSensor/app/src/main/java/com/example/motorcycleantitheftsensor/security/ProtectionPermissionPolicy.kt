package com.example.motorcycleantitheftsensor.security

data class ProtectionReadiness(
    val blockers: Set<String>,
    val degradations: Set<String>,
) {
    val canArm: Boolean get() = blockers.isEmpty()
}

object ProtectionPermissionPolicy {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val SEND_SMS = "android.permission.SEND_SMS"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun readiness(
        missingPermissions: Set<String>,
        sdkInt: Int,
    ): ProtectionReadiness {
        val blockers = missingPermissions
            .intersect(requiredPermissions(sdkInt))
            .mapTo(mutableSetOf(), ::permissionLabel)
        val degradations = missingPermissions
            .intersect(optionalPermissions() - LOCATION_PERMISSIONS)
            .mapTo(mutableSetOf()) { permission -> "${permissionLabel(permission)} unavailable" }
        if (missingPermissions.containsAll(LOCATION_PERMISSIONS)) {
            degradations += "LOCATION permission unavailable"
        }
        return ProtectionReadiness(
            blockers = blockers,
            degradations = degradations,
        )
    }

    fun requiredPermissions(sdkInt: Int): Set<String> = buildSet {
        if (sdkInt >= 33) add(POST_NOTIFICATIONS)
    }

    fun optionalPermissions(): Set<String> = setOf(
        RECORD_AUDIO,
        SEND_SMS,
        ACCESS_FINE_LOCATION,
        ACCESS_COARSE_LOCATION,
    )

    private fun permissionLabel(permission: String): String = permission.substringAfterLast('.')

    private val LOCATION_PERMISSIONS = setOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
}
