package com.example.motorcycleantitheftsensor.ui

import com.example.motorcycleantitheftsensor.security.PairingCode
import com.example.motorcycleantitheftsensor.security.PairingCodePolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidProtectionSettingsGatewayTest {
    @Test
    fun prefixedTokenIsNormalizedBeforeVerificationPersistenceAndPollingRefresh() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.verificationResultToReturn = com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult.Verified("motor_bot", "123")
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
        )

        val result = gateway.replaceBotToken(" BOT123456:ABC ")

        assertTrue(result.applied)
        assertEquals("Bot @motor_bot verified and token updated", result.message)
        assertEquals("123456:ABC", operations.savedToken)
        assertEquals(
            listOf("verify:123456:ABC", "save:123456:ABC", "refresh-service"),
            operations.events,
        )
    }

    @Test
    fun rejectedTokenPersistsNothingAndDoesNotRefresh() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.savedToken = "OLD_TOKEN"
        operations.verificationResultToReturn = com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult.Rejected
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
        )

        val result = gateway.replaceBotToken("123456:INVALID")

        assertFalse(result.applied)
        assertEquals("Bot token could not be verified", result.message)
        assertEquals("OLD_TOKEN", operations.savedToken) // unchanged
        assertEquals(listOf("verify:123456:INVALID"), operations.events)
    }

    @Test
    fun connectionFailureRetainsExistingTokenAndDoesNotRefresh() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.savedToken = "OLD_TOKEN"
        operations.verificationResultToReturn = com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult.ConnectionFailure
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
        )

        val result = gateway.replaceBotToken("123456:NET_FAIL")

        assertFalse(result.applied)
        assertEquals("Telegram connection could not be established", result.message)
        assertEquals("OLD_TOKEN", operations.savedToken) // unchanged
        assertEquals(listOf("verify:123456:NET_FAIL"), operations.events)
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
    fun verificationWithoutResultTimesOutAndRetainsExistingToken() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.savedToken = "OLD_TOKEN"
        operations.verificationHang = true
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
            verificationTimeoutMs = 50L,
        )

        val result = gateway.replaceBotToken("123456:HANG")

        assertFalse(result.applied)
        assertEquals("Telegram connection could not be established", result.message)
        assertEquals("OLD_TOKEN", operations.savedToken)
    }

    @Test
    fun timeoutDoesNotRefreshPolling() = runTest {
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.verificationHang = true
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
            verificationTimeoutMs = 50L,
        )

        gateway.replaceBotToken("123456:HANG")

        assertFalse(operations.events.contains("refresh-service"))
    }

    @Test
    fun gatewayCancellationPropagates() = runTest {
        val cancelledSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val operations = FakeAndroidProtectionSettingsOperations()
        operations.verificationAction = {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelledSignal.complete(Unit)
            }
        }
        val gateway = AndroidProtectionSettingsGateway(
            operations = operations,
            pairingCodePolicy = PairingCodePolicy(),
            verificationTimeoutMs = 10_000L,
        )

        val job = launch {
            gateway.replaceBotToken("123456:HANG")
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(cancelledSignal.isCompleted)
    }
}

private class FakeAndroidProtectionSettingsOperations(
    allowedChatIds: Set<String> = emptySet(),
) : AndroidProtectionSettingsOperations {
    val events = mutableListOf<String>()
    var savedToken: String? = null
    var allowedOwners = allowedChatIds
        private set
    var verificationResultToReturn: com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult = 
        com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult.Verified("testbot", "123")
    var verificationHang: Boolean = false
    var verificationAction: (suspend () -> com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult)? = null

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

    override fun getSensitivity(): Int = 5

    override fun getSmsDestination(): String? = null


    override fun setSensitivity(level: Int) = Unit

    override fun saveBotToken(token: String) {
        savedToken = token
        events += "save:$token"
    }

    override fun saveSmsDestination(destination: String) = Unit

    override fun ensureSmsAesKey() = Unit

    override suspend fun verifyBotToken(token: String): com.example.motorcycleantitheftsensor.telegram.TelegramBotVerificationResult {
        events += "verify:$token"
        verificationAction?.let { return it() }
        if (verificationHang) {
            kotlinx.coroutines.awaitCancellation()
        }
        return verificationResultToReturn
    }

    override fun refreshControlService() {
        events += "refresh-service"
    }
}
