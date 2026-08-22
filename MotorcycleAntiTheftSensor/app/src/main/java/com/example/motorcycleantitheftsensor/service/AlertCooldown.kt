package com.example.motorcycleantitheftsensor.service

class AlertCooldown(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private val lastSentAtMsByType = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldDispatch(alertType: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSentAtMs = lastSentAtMsByType[alertType]
        if (lastSentAtMs != null) {
            val delta = nowMs - lastSentAtMs
            if (delta in 0 until cooldownMs) {
                return false
            }
        }

        lastSentAtMsByType[alertType] = nowMs
        return true
    }

    @Synchronized
    fun reset(alertType: String? = null) {
        if (alertType != null) {
            lastSentAtMsByType.remove(alertType)
        } else {
            lastSentAtMsByType.clear()
        }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
