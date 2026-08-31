package com.example.motorcycleantitheftsensor.service

import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionMarker
import com.example.motorcycleantitheftsensor.protection.DirectBootProtectionStore
import com.example.motorcycleantitheftsensor.protection.PresentationTextCatalog
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

    @Test
    fun directBootCopyIsNeutralThaiWithoutLegacyBrandOrSecrets() {
        val copy = listOf(
            PresentationTextCatalog.NOTIFICATION_TITLE,
            PresentationTextCatalog.DIRECT_BOOT_CHANNEL_NAME,
            PresentationTextCatalog.DIRECT_BOOT_WAITING_BODY,
            PresentationTextCatalog.DIRECT_BOOT_MOVEMENT_BODY,
        )
        val bannedSecretMarkers = listOf(
            "Motorcycle Guard",
            "token",
            "chat_id",
            "chatId",
            "totp",
            "pairing",
            "aes",
            "secret",
            "encrypted incident",
        )
        copy.forEach { text ->
            bannedSecretMarkers.forEach { marker ->
                assertFalse(
                    "banned '$marker' leaked into '$text'",
                    text.contains(marker, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun directBootCopyNeverClaimsProtectionAlreadyResumed() {
        val bodies = listOf(
            PresentationTextCatalog.DIRECT_BOOT_WAITING_BODY,
            PresentationTextCatalog.DIRECT_BOOT_MOVEMENT_BODY,
        )
        val prematureClaims = listOf(
            "resume",
            "resumed",
            "กลับมาทำงานแล้ว",
            "ป้องกันต่อแล้ว",
            "กู้คืนสำเร็จแล้ว",
        )
        bodies.forEach { body ->
            prematureClaims.forEach { claim ->
                assertFalse(
                    "body must not claim protection resumed: '$body'",
                    body.contains(claim, ignoreCase = true),
                )
            }
        }
        assertTrue(PresentationTextCatalog.DIRECT_BOOT_WAITING_BODY.contains("รอ"))
        assertTrue(PresentationTextCatalog.DIRECT_BOOT_MOVEMENT_BODY.contains("ปลดล็อก"))
    }
}
