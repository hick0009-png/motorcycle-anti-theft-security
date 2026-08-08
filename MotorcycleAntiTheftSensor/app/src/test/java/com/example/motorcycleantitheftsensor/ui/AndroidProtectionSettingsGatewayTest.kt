package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import com.example.motorcycleantitheftsensor.security.TotpAuthenticator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProtectionSettingsGatewayTest {
    @Test
    fun prefixedTokenIsNormalizedBeforeVerificationPersistenceAndServiceStart() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
        )

        val result = gateway.replaceBotToken(" BOT123456:ABC ")

        assertTrue(result.applied)
        assertEquals("123456:ABC", operations.savedToken)
        assertEquals(
            listOf("verify:123456:ABC", "save:123456:ABC", "start-service"),
            operations.events,
        )
    }
}

private class FakeAndroidProtectionSettingsOperations : AndroidProtectionSettingsOperations {
    val events = mutableListOf<String>()
    var savedToken: String? = null

    override fun getAllowedChatIds(): Set<String> = emptySet()

    override fun getPairingCode(): PairingCode? = null

    override fun createPairingCode(policy: PairingCodePolicy): PairingCode = policy.generate(0L)

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

    override fun startControlService() {
        events += "start-service"
    }

    override fun setupAuthenticator(): String = error("Not used")

    override fun verifyAuthenticator(code: String): TotpAuthenticator.VerificationResult = error("Not used")

}
