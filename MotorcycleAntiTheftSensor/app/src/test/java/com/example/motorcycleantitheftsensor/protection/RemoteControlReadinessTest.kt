package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlReadinessTest {
    @Test
    fun missingAuthenticationConfigurationProducesOnlyNonSecretBlockers() {
        val readiness = RemoteControlReadiness(
            botTokenConfigured = false,
            ownerPaired = false,
            totpConfigured = false,
        )

        val blockers = readiness.blockers()

        assertEquals(
            setOf(
                "TELEGRAM bot token not configured",
                "TELEGRAM owner not paired",
                "TOTP not configured",
            ),
            blockers,
        )
        assertTrue(blockers.none { it.contains(":") || it.any(Char::isDigit) })
    }

    @Test
    fun completeAuthenticationConfigurationHasNoReadinessBlocker() {
        val readiness = RemoteControlReadiness(
            botTokenConfigured = true,
            ownerPaired = true,
            totpConfigured = true,
        )

        assertTrue(readiness.blockers().isEmpty())
    }
}
