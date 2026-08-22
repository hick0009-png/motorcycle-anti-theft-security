package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionMarker
import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootBootstrapPolicyTest {

    @Test
    fun startsBootstrapOnlyForArmedOwnerIntentWithBootRecoveryEnabled() {
        assertTrue(
            DirectBootBootstrapPolicy.shouldStart(
                DirectBootProtectionMarker(
                    armed = true,
                    autoRecoveryAfterBoot = true,
                ),
            ),
        )
    }

    @Test
    fun neverStartsBootstrapForDisarmedOrBootDisabledIntent() {
        assertFalse(
            DirectBootBootstrapPolicy.shouldStart(
                DirectBootProtectionMarker(
                    armed = false,
                    autoRecoveryAfterBoot = true,
                ),
            ),
        )
        assertFalse(
            DirectBootBootstrapPolicy.shouldStart(
                DirectBootProtectionMarker(
                    armed = true,
                    autoRecoveryAfterBoot = false,
                ),
            ),
        )
    }

    @Test
    fun acceptsOnlyLockedBootForPreUnlockBootstrap() {
        assertTrue(DirectBootBootstrapPolicy.isLockedBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(DirectBootBootstrapPolicy.isLockedBootAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(DirectBootBootstrapPolicy.isLockedBootAction("android.intent.action.USER_UNLOCKED"))
    }

    @Test
    fun movementPolicyRequiresADeviationFromTheBootBaseline() {
        assertFalse(DirectBootMovementPolicy.isMovement(deltaMagnitude = 1.9f))
        assertTrue(DirectBootMovementPolicy.isMovement(deltaMagnitude = 2.0f))
    }

    @Test
    fun handoffWaitsUntilCredentialStorageIsUnlocked() {
        assertFalse(DirectBootBootstrapPolicy.shouldHandoffToFullRecovery(isUserUnlocked = false))
        assertTrue(DirectBootBootstrapPolicy.shouldHandoffToFullRecovery(isUserUnlocked = true))
    }

    @Test
    fun directBootMarkerContainsNoProfileOrSecretFields() {
        assertEquals(
            setOf("armed", "auto_recovery_after_boot"),
            DirectBootProtectionStore.persistedKeysForTest(),
        )
    }
}
