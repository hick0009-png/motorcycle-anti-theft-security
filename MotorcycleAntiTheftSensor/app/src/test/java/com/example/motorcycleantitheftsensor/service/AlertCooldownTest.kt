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

    @Test
    fun resetClearsCooldownImmediately() {
        assertTrue(cooldown.shouldDispatch("VIBRATION", nowMs = 1_000L))
        assertFalse(cooldown.shouldDispatch("VIBRATION", nowMs = 2_000L))

        cooldown.reset()
        assertTrue("After reset, same alert type must be allowed immediately", cooldown.shouldDispatch("VIBRATION", nowMs = 2_000L))
    }

    @Test
    fun backwardClockJumpAllowsDispatch() {
        assertTrue(cooldown.shouldDispatch("VIBRATION", nowMs = 10_000L))
        // Clock jumps backward to 5,000L
        assertTrue("Backward clock jump should not lock out alerts", cooldown.shouldDispatch("VIBRATION", nowMs = 5_000L))
    }
}
