package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProtectionSettingsGatewayTest {
    @Test
    fun prefixedTokenIsNormalizedBeforeVerificationPersistenceAndPollingRefresh() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
        )

        val result = gateway.replaceBotToken(" BOT123456:ABC ")

        assertTrue(result.applied)
        assertEquals("123456:ABC", operations.savedToken)
        assertEquals(
            listOf("verify:123456:ABC", "save:123456:ABC", "refresh-service"),
            operations.events,
        )
    }

    @Test
    fun resettingPairingClearsOwnersCreatesNewCodeAndRefreshesPolling() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations(
            allowedChatIds = setOf("1001", "1002"),
        )
        val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())

        val result = gateway.resetPairing()

        assertTrue(result.applied)
        assertEquals("Pairing reset; use the new pairing code", result.message)
        assertEquals(
            listOf("save-owners:", "create-pairing", "refresh-service"),
            operations.events,
        )
        assertEquals(emptySet<String>(), operations.allowedOwners)
    }

    @Test
    fun beginningAuthenticatorSetupExposesDetailsWithoutActivatingCandidate() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())

        val details = gateway.beginAuthenticatorSetup()

        assertEquals("CANDIDATE", details?.secret)
        assertEquals("otpauth://candidate", details?.uri)
        assertEquals(listOf("create-authenticator"), operations.events)
    }

    @Test
    fun cancellingAuthenticatorSetupDiscardsCandidate() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())
        gateway.beginAuthenticatorSetup()

        gateway.cancelAuthenticatorSetup()
        val verified = gateway.verifyAuthenticator("123456")

        assertFalse(verified)
        assertEquals(listOf("create-authenticator"), operations.events)
    }

    @Test
    fun successfulAuthenticatorVerificationActivatesPendingCandidate() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())
        gateway.beginAuthenticatorSetup()

        val verified = gateway.verifyAuthenticator("123456")

        assertTrue(verified)
        assertEquals(
            listOf(
                "create-authenticator",
                "verify-authenticator:123456:CANDIDATE",
                "activate-authenticator:CANDIDATE",
            ),
            operations.events,
        )
        assertFalse(gateway.verifyAuthenticator("123456"))
    }

    @Test
    fun cancellationWhileCandidateVerificationIsPausedPreventsActivation() = runTest {
        val verificationStarted = CountDownLatch(1)
        val allowVerification = CountDownLatch(1)
        val operations = FakeAndroidProtectionSettingsOperations(
            verificationStarted = verificationStarted,
            allowVerification = allowVerification,
        )
        val gateway = AndroidProtectionSettingsGateway(operations, PairingCodePolicy())
        gateway.beginAuthenticatorSetup()
        val verification = async(Dispatchers.Default) {
            gateway.verifyAuthenticator("123456")
        }
        assertTrue(verificationStarted.await(2, TimeUnit.SECONDS))

        gateway.cancelAuthenticatorSetup()
        allowVerification.countDown()

        assertFalse(verification.await())
        assertFalse(operations.events.contains("activate-authenticator:CANDIDATE"))
    }
}

private class FakeAndroidProtectionSettingsOperations(
    private val verificationStarted: CountDownLatch? = null,
    private val allowVerification: CountDownLatch? = null,
    allowedChatIds: Set<String> = emptySet(),
) : AndroidProtectionSettingsOperations {
    val events = mutableListOf<String>()
    var savedToken: String? = null
    var allowedOwners = allowedChatIds
        private set

    override fun getAllowedChatIds(): Set<String> = allowedOwners

    override fun saveAllowedChatIds(chatIds: Set<String>) {
        allowedOwners = chatIds
        events += "save-owners:${chatIds.sorted().joinToString()}"
    }

    override fun getPairingCode(): PairingCode? = null

    override fun createPairingCode(policy: PairingCodePolicy): PairingCode {
        events += "create-pairing"
        return policy.generate(0L)
    }

    override fun getBotToken(): String? = savedToken

    override fun getTotpSeed(): String? = null

    override fun getSensitivity(): Int = 5

    override fun getSmsDestination(): String? = null

    override fun getSmsAesKey(): String? = null

    override fun setSensitivity(level: Int) = Unit

    override fun saveBotToken(token: String) {
        savedToken = token
        events += "save:$token"
    }

    override fun saveSmsDestination(destination: String) = Unit

    override fun saveSmsAesKey(aesKey: String) = Unit

    override fun verifyBotToken(token: String, onResult: (Boolean) -> Unit) {
        events += "verify:$token"
        onResult(true)
    }

    override fun refreshControlService() {
        events += "refresh-service"
    }

    override fun createAuthenticatorSetup(): TotpAuthenticator.SetupCandidate {
        events += "create-authenticator"
        return TotpAuthenticator.SetupCandidate(
            secret = "CANDIDATE",
            uri = "otpauth://candidate",
        )
    }

    override fun verifyAuthenticatorSetup(
        candidate: TotpAuthenticator.SetupCandidate,
        code: String,
    ): TotpAuthenticator.VerificationResult {
        events += "verify-authenticator:$code:${candidate.secret}"
        verificationStarted?.countDown()
        if (allowVerification != null) check(allowVerification.await(2, TimeUnit.SECONDS))
        return TotpAuthenticator.VerificationResult.SUCCESS
    }

    override fun activateAuthenticatorSetup(candidate: TotpAuthenticator.SetupCandidate) {
        events += "activate-authenticator:${candidate.secret}"
    }

}
