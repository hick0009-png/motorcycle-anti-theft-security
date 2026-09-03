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
    private val deviceKey = EncryptedSmsCodec.generateKeyBase64()
    private val alert = "ประตูเปิด 25° จากตำแหน่งปิด"

    @Test
    fun sendDoesNotReportSuccessUntilSentCallbackConfirmsEveryPart() = runTest {
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
            dispatcher = dispatcher,
            nowMs = { 1_000L },
            sendTimeoutMs = 30_000L,
        )

        val result = async { manager.sendEncryptedSmsAlert("+15555550123", alert) }
        runCurrent()

        assertFalse(result.isCompleted)
        dispatcher.complete(true)
        assertTrue(result.await())
    }

    @Test
    fun dispatcherExceptionProducesFailedOutcome() = runTest {
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
            dispatcher = SmsDispatcher { _, _, _ -> throw SecurityException("permission denied") },
            nowMs = { 1_000L },
            sendTimeoutMs = 30_000L,
        )

        assertFalse(manager.sendEncryptedSmsAlert("+15555550123", alert))
    }

    @Test
    fun missingSentCallbackTimesOutAsFailed() = runTest {
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
            dispatcher = SmsDispatcher { _, _, _ -> {} },
            nowMs = { 1_000L },
            sendTimeoutMs = 100L,
        )
        val result = async { manager.sendEncryptedSmsAlert("+15555550123", alert) }
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
            smsKeyProvider = { deviceKey },
            dispatcher = dispatcher,
            nowMs = { currentTime },
            sendTimeoutMs = 1_000L,
            minIntervalMs = 60_000L,
        )

        val first = async { manager.sendEncryptedSmsAlert("+15555550123", alert) }
        runCurrent()
        dispatcher.complete(true)
        assertTrue(first.await())

        // Try sending again after only 30 seconds (cooldown is 60s)
        currentTime = 31_000L
        val second = manager.sendEncryptedSmsAlert("+15555550123", alert)
        assertFalse("Second SMS within 60s cooldown must be rejected", second)

        // Try sending after 61 seconds (cooldown elapsed)
        currentTime = 62_000L
        val third = async { manager.sendEncryptedSmsAlert("+15555550123", alert) }
        runCurrent()
        dispatcher.complete(true)
        assertTrue("Third SMS after cooldown elapsed must succeed", third.await())
    }

    @Test
    fun encryptedPayloadCarriesTheIncidentCopyAndTimestamp() = runTest {
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
            dispatcher = dispatcher,
            nowMs = { 1_787_245_200_000L },
            sendTimeoutMs = 30_000L,
        )

        val message = "ตรวจพบแรงกระแทกที่ประตู\n📍 พิกัด: 13.75630,100.50180 (~8m)"
        val result = async { manager.sendEncryptedSmsAlert("+15555550123", message) }
        runCurrent()
        dispatcher.complete(true)
        assertTrue(result.await())

        val encryptedBody = requireNotNull(dispatcher.lastMessage)
        assertFalse("Alert copy must never travel in the clear", encryptedBody.contains("พิกัด"))

        val decrypted = requireNotNull(EncryptedSmsCodec.decryptSmsPayload(encryptedBody, deviceKey))
        org.junit.Assert.assertEquals("$message\nTIME:1787245200000", decrypted)
    }

    @Test
    fun oversizedMessageIsTruncatedOnACharacterBoundary() = runTest {
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
            dispatcher = dispatcher,
            nowMs = { 1_787_245_200_000L },
            sendTimeoutMs = 30_000L,
            maxPayloadBytes = 60,
        )

        // Each Thai character is 3 UTF-8 bytes, so this runs to five times the budget.
        val message = "ประตูเปิด".repeat(12)
        val result = async { manager.sendEncryptedSmsAlert("+15555550123", message) }
        runCurrent()
        dispatcher.complete(true)
        assertTrue(result.await())

        val decrypted = requireNotNull(
            EncryptedSmsCodec.decryptSmsPayload(requireNotNull(dispatcher.lastMessage), deviceKey)
        )
        val body = decrypted.substringBefore("\nTIME:")
        assertTrue("Truncation must be visible to the reader", body.endsWith("…"))
        assertTrue(
            "Body must respect the byte budget, was ${body.toByteArray(Charsets.UTF_8).size}",
            body.toByteArray(Charsets.UTF_8).size <= 60,
        )
        assertFalse("A half-cut Thai character decodes to U+FFFD", body.contains('�'))
        assertTrue("The surviving prefix must be real text", message.startsWith(body.dropLast(1)))
    }

    @Test
    fun aPassphraseIsRejectedRatherThanStretchedIntoAWeakKey() = runTest {
        val manager = SmsFallbackManager(
            smsKeyProvider = { "moto1234" },
            dispatcher = SmsDispatcher { _, _, _ -> error("must never reach the radio") },
            nowMs = { 1_000L },
            sendTimeoutMs = 30_000L,
        )

        assertFalse(manager.sendEncryptedSmsAlert("+15555550123", alert))
    }

    @Test
    fun concurrentCallsSafelyRespectMutexAndCooldown() = runTest {
        var currentTime = 1_000L
        val dispatcher = FakeSmsDispatcher()
        val manager = SmsFallbackManager(
            smsKeyProvider = { deviceKey },
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
