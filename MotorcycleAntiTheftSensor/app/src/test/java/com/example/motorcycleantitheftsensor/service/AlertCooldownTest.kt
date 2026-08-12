package com.example.motorcycleantitheftsensor.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertCooldownTest {
    private val cooldown = AlertCooldown(cooldownMs = 30_000L)

    @Test
    fun suppressesRepeatedAlertOfSameTypeWithinCooldown() {
        assertTrue(cooldown.shouldDispatch("VIBRATION", nowMs = 1_000L))
        assertFalse(cooldown.shouldDispatch("VIBRATION", nowMs = 30_999L))
        assertTrue(cooldown.shouldDispatch("VIBRATION", nowMs = 31_000L))
    }

    @Test
    fun permitsDifferentAlertTypesDuringCooldown() {
        assertTrue(cooldown.shouldDispatch("VIBRATION", nowMs = 1_000L))
        assertTrue(cooldown.shouldDispatch("AUDIO_PEAK", nowMs = 1_001L))
    }
}
