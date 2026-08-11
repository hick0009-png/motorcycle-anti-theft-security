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
        if (handle.held) handle.release()
        handle.acquire(leaseDurationMs)
        expiresAtElapsedMs = nowElapsedMs + leaseDurationMs
    }

    @Synchronized
    fun release() {
        if (handle.held) handle.release()
        expiresAtElapsedMs = null
    }
}
