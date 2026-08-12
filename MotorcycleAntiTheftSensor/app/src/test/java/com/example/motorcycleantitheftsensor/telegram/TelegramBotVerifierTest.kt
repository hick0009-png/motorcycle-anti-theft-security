package com.example.motorcycleantitheftsensor.telegram

import java.io.IOException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotVerifierTest {
    @Test
    fun validTelegramResponseReturnsVerifiedIdentity() {
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
    fun authenticatedTelegramRejectionIsNotReportedAsTransportFailure() {
        val verifier = TelegramBotVerifier {
            TelegramVerificationHttpResponse(401, """{"ok":false,"error_code":401}""")
        }

        assertEquals(
            TelegramBotVerificationResult.Rejected,
            verifier.verify("123456:TEST_ONLY"),
        )
    }

    @Test
    fun tlsFailureReturnsTokenFreeConnectionFailure() {
        val verifier = TelegramBotVerifier {
            throw SSLPeerUnverifiedException("pin mismatch for test host")
        }

        val result = verifier.verify("123456:TEST_ONLY")

        assertEquals(TelegramBotVerificationResult.ConnectionFailure, result)
        assertTrue(result.toString().contains("123456:TEST_ONLY").not())
    }

    @Test
    fun ioFailureReturnsConnectionFailure() {
        val verifier = TelegramBotVerifier { throw IOException("offline") }

        assertEquals(
            TelegramBotVerificationResult.ConnectionFailure,
            verifier.verify("123456:TEST_ONLY"),
        )
    }
}
