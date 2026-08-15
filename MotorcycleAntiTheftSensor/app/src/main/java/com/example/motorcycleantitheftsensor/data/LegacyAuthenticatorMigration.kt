package com.example.motorcycleantitheftsensor.data

import kotlin.coroutines.cancellation.CancellationException

internal enum class LegacyAuthenticatorMigrationResult {
    NOT_PRESENT,
    REMOVED,
    FAILED,
}

internal class LegacyAuthenticatorMigration(
    private val containsLegacySeed: () -> Boolean,
    private val removeLegacySeed: () -> Boolean,
) {
    fun run(): LegacyAuthenticatorMigrationResult = try {
        when {
            !containsLegacySeed() -> LegacyAuthenticatorMigrationResult.NOT_PRESENT
            removeLegacySeed() -> LegacyAuthenticatorMigrationResult.REMOVED
            else -> LegacyAuthenticatorMigrationResult.FAILED
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LegacyAuthenticatorMigrationResult.FAILED
    }
}

internal fun EncryptedPrefsManager.removeLegacyAuthenticatorState(): LegacyAuthenticatorMigrationResult =
    LegacyAuthenticatorMigration(
        containsLegacySeed = { containsLegacyAuthenticatorSeed() },
        removeLegacySeed = { removeLegacyAuthenticatorSeed() },
    ).run()
