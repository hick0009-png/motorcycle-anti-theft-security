package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenewableWakeLockTest {
    @Test
    fun leaseRenewsBeforeExpiryAndReleasesOnShutdown() {
        val handle = FakeWakeLockHandle()
        val wakeLock = RenewableWakeLock(
            handle = handle,
            leaseDurationMs = 60_000L,
            renewBeforeExpiryMs = 10_000L,
        )

        wakeLock.ensureLease(nowElapsedMs = 1_000L)
        wakeLock.ensureLease(nowElapsedMs = 50_999L)
        assertEquals(listOf(60_000L), handle.acquisitions)

        wakeLock.ensureLease(nowElapsedMs = 51_000L)
        assertEquals(listOf(60_000L, 60_000L), handle.acquisitions)

        wakeLock.release()
        assertFalse(handle.held)
    }
}

private class FakeWakeLockHandle : WakeLockHandle {
    override var held: Boolean = false
    val acquisitions = mutableListOf<Long>()

    override fun acquire(timeoutMs: Long) {
        acquisitions += timeoutMs
        held = true
    }

    override fun release() {
        held = false
    }
}
