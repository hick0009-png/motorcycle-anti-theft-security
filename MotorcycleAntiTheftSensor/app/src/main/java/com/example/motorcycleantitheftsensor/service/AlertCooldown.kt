package com.example.motorcycleantitheftsensor.service

class AlertCooldown(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private val lastSentAtMsByType = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldDispatch(alertType: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSentAtMs = lastSentAtMsByType[alertType]
        if (lastSentAtMs != null && nowMs - lastSentAtMs < cooldownMs) return false

        lastSentAtMsByType[alertType] = nowMs
        return true
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
