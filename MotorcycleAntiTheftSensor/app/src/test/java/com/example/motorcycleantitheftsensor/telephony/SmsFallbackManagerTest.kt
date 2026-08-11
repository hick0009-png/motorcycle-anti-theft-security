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

}

private class FakeSmsDispatcher : SmsDispatcher {
    private var callback: ((Boolean) -> Unit)? = null

    override fun sendMultipart(
        destinationNumber: String,
        message: String,
        onSent: (Boolean) -> Unit,
    ): () -> Unit {
        callback = onSent
        return { callback = null }
    }

    fun complete(sent: Boolean) {
        requireNotNull(callback)(sent)
    }
}
