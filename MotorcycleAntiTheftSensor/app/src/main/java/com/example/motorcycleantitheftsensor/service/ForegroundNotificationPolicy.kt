package com.example.motorcycleantitheftsensor.service

data class ForegroundNotificationFingerprint(
    val text: String,
    val foregroundTypes: Int,
)

data class ForegroundNotificationSpec(
    val channelId: String = "anti_theft_protection_silent_v2",
    val silent: Boolean = true,
    val onlyAlertOnce: Boolean = true,
    val ongoing: Boolean = true,
)

class ForegroundNotificationPolicy {
    fun shouldPublish(
        previous: ForegroundNotificationFingerprint?,
        next: ForegroundNotificationFingerprint,
    ): Boolean = previous != next
}
