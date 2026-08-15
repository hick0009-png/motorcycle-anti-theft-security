package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationPolicyTest {
    @Test
    fun serviceNotificationContractIsSilentOngoingAndVersioned() {
        val spec = ForegroundNotificationSpec()
        assertEquals("anti_theft_protection_silent_v2", spec.channelId)
        assertTrue(spec.silent)
        assertTrue(spec.onlyAlertOnce)
        assertTrue(spec.ongoing)
    }

    @Test
    fun identicalNotificationIsNotRepublished() {
        val policy = ForegroundNotificationPolicy()
        val current = ForegroundNotificationFingerprint("Armed — all required protection healthy", 1)

        assertFalse(policy.shouldPublish(current, current))
    }

    @Test
    fun changedTextOrForegroundTypesIsRepublished() {
        val policy = ForegroundNotificationPolicy()
        val initial = ForegroundNotificationFingerprint("Armed — all required protection healthy", 1)
        val textChanged = ForegroundNotificationFingerprint("Disarmed — remote control online", 1)
        val typeChanged = ForegroundNotificationFingerprint("Armed — all required protection healthy", 9)

        assertTrue(policy.shouldPublish(null, initial))
        assertTrue(policy.shouldPublish(initial, textChanged))
        assertTrue(policy.shouldPublish(initial, typeChanged))
    }
}
