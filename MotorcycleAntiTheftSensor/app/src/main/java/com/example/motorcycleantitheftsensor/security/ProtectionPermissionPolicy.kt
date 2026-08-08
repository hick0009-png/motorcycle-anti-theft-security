package com.example.motorcycleantitheftsensor.security

data class ProtectionReadiness(
    val blockers: Set<String>,
    val degradations: Set<String>,
) {
    val canArm: Boolean get() = blockers.isEmpty()
}

object ProtectionPermissionPolicy {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun readiness(
        missingPermissions: Set<String>,
        sdkInt: Int,
    ): ProtectionReadiness {
        val blockers = missingPermissions
            .intersect(requiredPermissions(sdkInt))
            .mapTo(mutableSetOf(), ::permissionLabel)
        val degradations = missingPermissions
            .intersect(optionalPermissions())
            .mapTo(mutableSetOf()) { permission -> "${permissionLabel(permission)} unavailable" }
        return ProtectionReadiness(
            blockers = blockers,
            degradations = degradations,
        )
    }

    fun requiredPermissions(sdkInt: Int): Set<String> = buildSet {
        if (sdkInt >= 33) add(POST_NOTIFICATIONS)
    }

    fun optionalPermissions(): Set<String> = setOf(RECORD_AUDIO)

    private fun permissionLabel(permission: String): String = permission.substringAfterLast('.')
}
