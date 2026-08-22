package com.example.motorcycleantitheftsensor.telephony

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmsFallbackManagerTest {
    @Test
    fun sendDoesNotReportSuccessUntilSentCallbackConfirmsEveryPart() = runTest {
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = dispatcher,
            nowMs = { 1_000L },
            sendTimeoutMs = 30_000L,
        )

        val result = async {
            manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence")
        }
        runCurrent()

        assertFalse(result.isCompleted)
        dispatcher.complete(true)
        assertTrue(result.await())
    }

    @Test
    fun dispatcherExceptionProducesFailedOutcome() = runTest {
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = SmsDispatcher { _, _, _ -> throw SecurityException("permission denied") },
            nowMs = { 1_000L },
            sendTimeoutMs = 30_000L,
        )

        assertFalse(
            manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence"),
        )
    }

    @Test
    fun missingSentCallbackTimesOutAsFailed() = runTest {
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = SmsDispatcher { _, _, _ -> {} },
            nowMs = { 1_000L },
            sendTimeoutMs = 100L,
        )
        val result = async {
            manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence")
        }
        runCurrent()
        assertFalse(result.isCompleted)

        advanceTimeBy(101L)
        runCurrent()
        try {
            assertTrue(result.isCompleted)
            assertFalse(result.await())
        } finally {
            result.cancel()
        }
    }

    @Test
    fun sendsWithinCooldownPeriodAreRejected() = runTest {
        var currentTime = 1_000L
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = dispatcher,
            nowMs = { currentTime },
            sendTimeoutMs = 1_000L,
            minIntervalMs = 60_000L,
        )

        val first = async {
            manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence")
        }
        runCurrent()
        dispatcher.complete(true)
        assertTrue(first.await())

        // Try sending again after only 30 seconds (cooldown is 60s)
        currentTime = 31_000L
        val second = manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence")
        assertFalse("Second SMS within 60s cooldown must be rejected", second)

        // Try sending after 61 seconds (cooldown elapsed)
        currentTime = 62_000L
        val third = async {
            manager.sendEncryptedSmsAlert("+15555550123", "SECURITY_INCIDENT", "evidence")
        }
        runCurrent()
        dispatcher.complete(true)
        assertTrue("Third SMS after cooldown elapsed must succeed", third.await())
    }

    @Test
    fun encryptedPayloadContainsAlertTypeAndTimestampWithoutCoordinates() = runTest {
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = dispatcher,
            nowMs = { 1_787_245_200_000L },
            sendTimeoutMs = 30_000L,
        )

        val result = async {
            manager.sendEncryptedSmsAlert("+15555550123", "VIBRATION")
        }
        runCurrent()
        dispatcher.complete(true)
        assertTrue(result.await())

        val encryptedBody = requireNotNull(dispatcher.lastMessage)
        val decrypted = EncryptedSmsCodec.decryptSmsPayload(encryptedBody, "test-only-aes-key")
        org.junit.Assert.assertEquals("ALERT:VIBRATION|TIME:1787245200000", decrypted)
        assertFalse(decrypted!!.contains("LOC:"))
        assertFalse(decrypted.contains("lat"))
    }

    @Test
    fun concurrentCallsSafelyRespectMutexAndCooldown() = runTest {
        var currentTime = 1_000L
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { "test-only-aes-key" },
            dispatcher = dispatcher,
            nowMs = { currentTime },
            sendTimeoutMs = 1_000L,
            minIntervalMs = 60_000L,
        )

        val first = async { manager.sendEncryptedSmsAlert("+15555550123", "ALERT_1") }
        val second = async { manager.sendEncryptedSmsAlert("+15555550123", "ALERT_2") }
        runCurrent()

        dispatcher.complete(true)
        val res1 = first.await()
        val res2 = second.await()

        assertTrue("One call must succeed and one must be rejected due to cooldown mutex", res1 xor res2)
    }
}

private class FakeSmsDispatcher : SmsDispatcher {
    private var callback: ((Boolean) -> Unit)? = null
    var lastMessage: String? = null

    override fun sendMultipart(
        destinationNumber: String,
        message: String,
        onSent: (Boolean) -> Unit,
    ): () -> Unit {
        lastMessage = message
        callback = onSent
        return { callback = null }
    }

    fun complete(sent: Boolean) {
        requireNotNull(callback)(sent)
    }
}
