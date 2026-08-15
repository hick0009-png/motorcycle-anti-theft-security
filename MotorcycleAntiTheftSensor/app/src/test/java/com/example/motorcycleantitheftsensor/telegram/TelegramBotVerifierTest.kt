package com.example.motorcycleantitheftsensor.telegram

import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotVerifierTest {
    @Test
    fun validTelegramResponseReturnsVerifiedIdentity() = runTest {
        val verifier = TelegramBotVerifier {
            TelegramVerificationHttpResponse(
                code = 200,
                body = """{"ok":true,"result":{"id":42,"username":"guard_bot"}}""",
            )
        }

        assertEquals(
            TelegramBotVerificationResult.Verified("guard_bot", "42"),
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun authenticatedTelegramRejectionIsNotReportedAsTransportFailure() = runTest {
        val verifier = TelegramBotVerifier {
            TelegramVerificationHttpResponse(401, """{"ok":false,"error_code":401}""")
        }

        assertEquals(
            TelegramBotVerificationResult.Rejected,
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun tlsFailureReturnsTokenFreeConnectionFailure() = runTest {
        val verifier = TelegramBotVerifier {
            throw SSLPeerUnverifiedException("pin mismatch for test host")
        }

        val result = verifier.verify("123456:TEST_ONLY")

        assertEquals(TelegramBotVerificationResult.ConnectionFailure, result)
        assertTrue(result.toString().contains("123456:TEST_ONLY").not())
    }

    @Test
    fun ioFailureReturnsConnectionFailure() = runTest {
        val verifier = TelegramBotVerifier { throw IOException("offline") }

        assertEquals(
            TelegramBotVerificationResult.ConnectionFailure,
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun runtimeTransportFailureReturnsTokenFreeConnectionFailure() = runTest {
        val verifier = TelegramBotVerifier {
            throw IllegalStateException("synthetic failure")
        }

        val result = verifier.verify("123456:TEST_ONLY")

        assertEquals(TelegramBotVerificationResult.ConnectionFailure, result)
        assertFalse(result.toString().contains("123456:TEST_ONLY"))
    }

    @Test
    fun cancellingVerificationCancelsTheTransport() = runTest {
        val cancelledSignal = CompletableDeferred<Unit>()
        val verifier = TelegramBotVerifier {
            try {
                awaitCancellation()
            } finally {
                cancelledSignal.complete(Unit)
            }
        }

        val job = launch {
            verifier.verify("123456:TEST_ONLY")
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(cancelledSignal.isCompleted)
    }

    @Test
    fun verifiedRejectedAndConnectionFailureRemainDistinct() {
        val verified = TelegramBotVerificationResult.Verified("bot", "123")
        val rejected = TelegramBotVerificationResult.Rejected
        val connectionFailure = TelegramBotVerificationResult.ConnectionFailure

        assertTrue(verified != rejected)
        assertTrue(rejected != connectionFailure)
        assertTrue(verified != connectionFailure)
    }
}
