package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionContinuityPolicyTest {
    @Test
    fun ownerStoppedIntentNeverRestartsDetectors() {
        val result = ProtectionContinuityPolicy.eligibility(
            ProtectionContinuityIntent(
                desiredService = DesiredService.STOPPED_BY_OWNER,
                desiredProtection = DesiredProtection.DISARMED,
                autoRecoveryAfterBoot = true,
            ),
            RecoveryTrigger.ANDROID_BOOT,
        )

        assertFalse(result.restartDetectors)
        assertEquals(RecoveryPhase.NOT_RECOVERED_OWNER_STOPPED, result.phase)
    }

    @Test
    fun disarmedIntentNeverRestartsDetectors() {
        val result = ProtectionContinuityPolicy.eligibility(
            ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.DISARMED,
                autoRecoveryAfterBoot = true,
            ),
            RecoveryTrigger.PROCESS_RECREATION,
        )

        assertFalse(result.restartDetectors)
        assertEquals(RecoveryPhase.NOT_RECOVERED_DISARMED, result.phase)
    }

    @Test
    fun disabledBootRecoveryDoesNotRestoreDetectorsAfterAndroidBoot() {
        val result = ProtectionContinuityPolicy.eligibility(
            ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.ARMED,
                autoRecoveryAfterBoot = false,
                armedSessionId = "session-1",
            ),
            RecoveryTrigger.ANDROID_BOOT,
        )

        assertFalse(result.restartDetectors)
        assertEquals(DesiredProtection.DISARMED, result.resultingDesiredProtection)
        assertEquals(RecoveryPhase.NOT_RECOVERED_BOOT_DISABLED, result.phase)
    }

    @Test
    fun disabledBootRecoveryStillAllowsSameBootProcessRecovery() {
        val result = ProtectionContinuityPolicy.eligibility(
            ProtectionContinuityIntent(
                desiredService = DesiredService.RUNNING,
                desiredProtection = DesiredProtection.ARMED,
                autoRecoveryAfterBoot = false,
                armedSessionId = "session-1",
            ),
            RecoveryTrigger.PROCESS_RECREATION,
        )

        assertTrue(result.restartDetectors)
        assertEquals(RecoveryPhase.RECOVERY_PENDING, result.phase)
    }
}
