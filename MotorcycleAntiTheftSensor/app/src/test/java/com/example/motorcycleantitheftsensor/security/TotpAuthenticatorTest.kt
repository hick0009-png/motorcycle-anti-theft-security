package com.example.motorcycleantitheftsensor.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpAuthenticatorTest {
    @Test
    fun creatingSetupCandidateDoesNotReplaceConfiguredSeed() {
        var configuredSeed: String? = EXISTING_SEED
        val authenticator = authenticator(
            readSeed = { configuredSeed },
            saveSeed = { configuredSeed = it },
        )

        val candidate = authenticator.createSetupCandidate()

        assertEquals(EXISTING_SEED, configuredSeed)
        assertEquals(CANDIDATE_SEED, candidate.secret)
        assertTrue(candidate.uri.contains("secret=$CANDIDATE_SEED"))
    }

    @Test
    fun invalidSetupCodeDoesNotReplaceConfiguredSeed() {
        var configuredSeed: String? = EXISTING_SEED
        val authenticator = authenticator(
            readSeed = { configuredSeed },
            saveSeed = { configuredSeed = it },
        )
        val candidate = authenticator.createSetupCandidate()

        val result = authenticator.verifySetupCode(candidate, "000000")

        assertEquals(TotpAuthenticator.VerificationResult.INVALID_CODE, result)
        assertEquals(EXISTING_SEED, configuredSeed)
    }

    @Test
    fun validSetupCodeDoesNotPersistCandidateDuringVerification() {
        val writes = mutableListOf<String>()
        val authenticator = authenticator(
            readSeed = { EXISTING_SEED },
            saveSeed = { writes += it },
        )
        val candidate = authenticator.createSetupCandidate()

        assertTrue(writes.isEmpty())
        val result = authenticator.verifySetupCode(candidate, CANDIDATE_CODE_AT_59_SECONDS)

        assertEquals(TotpAuthenticator.VerificationResult.SUCCESS, result)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun activatingVerifiedCandidatePersistsItsSeed() {
        val writes = mutableListOf<String>()
        val authenticator = authenticator(
            readSeed = { EXISTING_SEED },
            saveSeed = { writes += it },
        )
        val candidate = authenticator.createSetupCandidate()

        authenticator.activateSetup(candidate)

        assertEquals(listOf(CANDIDATE_SEED), writes)
    }

    @Test
    fun remoteVerificationContinuesToUseConfiguredSeedWhileCandidateIsPending() {
        val authenticator = authenticator(
            readSeed = { EXISTING_SEED },
            saveSeed = {},
        )

        authenticator.createSetupCandidate()

        assertEquals(
            TotpAuthenticator.VerificationResult.SUCCESS,
            authenticator.verifyCode(EXISTING_CODE_AT_59_SECONDS),
        )
    }

    private fun authenticator(
        readSeed: () -> String?,
        saveSeed: (String) -> Unit,
    ): TotpAuthenticator = TotpAuthenticator(
        readSeed = readSeed,
        saveSeed = saveSeed,
        nowMs = { 59_000L },
        fillRandomBytes = { bytes -> RFC_SECRET_BYTES.copyInto(bytes) },
    )

    private companion object {
        const val EXISTING_SEED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val EXISTING_CODE_AT_59_SECONDS = "812658"
        const val CANDIDATE_SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        const val CANDIDATE_CODE_AT_59_SECONDS = "287082"
        val RFC_SECRET_BYTES = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    }
}
