package com.example.motorcycleantitheftsensor.security

data class ProtectionReadiness(val missingPermissions: Set<String>) {
    val canArm: Boolean get() = missingPermissions.isEmpty()
}

object ProtectionPermissionPolicy {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    fun readiness(missingPermissions: Set<String>): ProtectionReadiness =
        ProtectionReadiness(missingPermissions)

    fun requiredPermissions(sdkInt: Int): Set<String> = buildSet {
        add(RECORD_AUDIO)
        if (sdkInt >= 33) add(POST_NOTIFICATIONS)
    }
}
