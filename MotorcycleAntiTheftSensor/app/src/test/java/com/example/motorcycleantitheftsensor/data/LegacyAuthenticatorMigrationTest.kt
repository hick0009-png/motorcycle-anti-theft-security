package com.example.motorcycleantitheftsensor.data

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAuthenticatorMigrationTest {
    @Test
    fun removesOnlyLegacySeedAndIsIdempotent() {
        val values = mutableMapOf(
            "enc_totp_seed" to "legacy-test-seed",
            "enc_telegram_bot_token" to "keep-token",
            "enc_allowed_chat_ids" to "keep-owner",
            "sensor_sensitivity_level" to "keep-sensitivity",
        )
        val migration = LegacyAuthenticatorMigration(
            containsLegacySeed = { values.containsKey("enc_totp_seed") },
            removeLegacySeed = { values.remove("enc_totp_seed") != null },
        )

        assertEquals(LegacyAuthenticatorMigrationResult.REMOVED, migration.run())
        assertEquals(LegacyAuthenticatorMigrationResult.NOT_PRESENT, migration.run())
        assertEquals("keep-token", values["enc_telegram_bot_token"])
        assertEquals("keep-owner", values["enc_allowed_chat_ids"])
        assertEquals("keep-sensitivity", values["sensor_sensitivity_level"])
    }

    @Test
    fun returnsNotPresentWhenSeedAbsent() {
        val migration = LegacyAuthenticatorMigration(
            containsLegacySeed = { false },
            removeLegacySeed = { true },
        )
        assertEquals(LegacyAuthenticatorMigrationResult.NOT_PRESENT, migration.run())
    }

    @Test
    fun returnsFailedWhenRemoveFails() {
        val migration = LegacyAuthenticatorMigration(
            containsLegacySeed = { true },
            removeLegacySeed = { false },
        )
        assertEquals(LegacyAuthenticatorMigrationResult.FAILED, migration.run())
    }

    @Test
    fun returnsFailedWhenContainsThrows() {
        val migration = LegacyAuthenticatorMigration(
            containsLegacySeed = { throw IllegalStateException("encrypted preferences unavailable") },
            removeLegacySeed = { true },
        )

        assertEquals(LegacyAuthenticatorMigrationResult.FAILED, migration.run())
    }

    @Test
    fun returnsFailedWhenRemoveThrows() {
        val migration = LegacyAuthenticatorMigration(
            containsLegacySeed = { true },
            removeLegacySeed = { throw SecurityException("keystore read failed") },
        )

        assertEquals(LegacyAuthenticatorMigrationResult.FAILED, migration.run())
    }

    @Test(expected = CancellationException::class)
    fun propagatesCancellation() {
        LegacyAuthenticatorMigration(
            containsLegacySeed = { throw CancellationException("service stopping") },
            removeLegacySeed = { true },
        ).run()
    }
}
