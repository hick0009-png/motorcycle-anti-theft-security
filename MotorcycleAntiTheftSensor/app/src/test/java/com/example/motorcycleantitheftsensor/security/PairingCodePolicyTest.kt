package com.example.motorcycleantitheftsensor.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodePolicyTest {
    private val policy = PairingCodePolicy()

    @Test
    fun matchingUnexpiredCodeIsAccepted() {
        assertEquals(
            PairingResult.Accepted,
            policy.validate("123456", PairingCode("123456", 2_000L), 1_000L)
        )
    }

    @Test
    fun expiredOrMismatchedCodeIsRejected() {
        assertEquals(
            PairingResult.Expired,
            policy.validate("123456", PairingCode("123456", 999L), 1_000L)
        )
        assertEquals(
            PairingResult.Rejected,
            policy.validate("654321", PairingCode("123456", 2_000L), 1_000L)
        )
    }

    @Test
    fun expirationUsesTheSameInclusiveBoundaryAsValidation() {
        val pairingCode = PairingCode("123456", 1_000L)

        assertEquals(false, policy.isExpired(pairingCode, nowMs = 999L))
        assertEquals(true, policy.isExpired(pairingCode, nowMs = 1_000L))
    }
}
