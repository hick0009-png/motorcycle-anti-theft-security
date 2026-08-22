package com.example.motorcycleantitheftsensor.service

interface WakeLockHandle {
    val held: Boolean

    fun acquire(timeoutMs: Long)

    fun release()
}

class RenewableWakeLock(
    private val handle: WakeLockHandle,
    private val leaseDurationMs: Long,
    private val renewBeforeExpiryMs: Long,
) {
    private var expiresAtElapsedMs: Long? = null

    init {
        require(leaseDurationMs > 0L) { "leaseDurationMs must be positive" }
        require(renewBeforeExpiryMs in 0 until leaseDurationMs) {
            "renewBeforeExpiryMs must be within the lease"
        }
    }

    @Synchronized
    fun ensureLease(nowElapsedMs: Long) {
        val expiresAt = expiresAtElapsedMs
        val renewalDue = expiresAt == null ||
            !handle.held ||
            nowElapsedMs >= expiresAt - renewBeforeExpiryMs
        if (!renewalDue) return
        handle.acquire(leaseDurationMs)
        expiresAtElapsedMs = nowElapsedMs + leaseDurationMs
    }

    @Synchronized
    fun release() {
        try {
            if (handle.held) handle.release()
        } catch (_: Exception) {}
        expiresAtElapsedMs = null
    }
}
