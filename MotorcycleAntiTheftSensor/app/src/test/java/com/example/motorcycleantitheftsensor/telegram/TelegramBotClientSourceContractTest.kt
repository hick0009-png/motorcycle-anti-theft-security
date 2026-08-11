package com.example.motorcycleantitheftsensor.telegram

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotClientSourceContractTest {
    @Test
    fun pollingAndSendFailuresNeverPrintOrLogRawExceptions() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()

        assertFalse(source.contains("printStackTrace"))
        assertFalse(
            Regex("""Log\.\w+\([^)]*,\s*(e|error|exception)\s*\)""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun tokenVerificationFailurePathDoesNotPrintRawExceptions() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()
        val methodStart = source.indexOf("fun verifyBotToken")
        val methodEnd = source.indexOf("fun sendTestAlertToOwners", startIndex = methodStart)
        assertTrue(methodStart >= 0 && methodEnd > methodStart)

        val verificationPath = source.substring(methodStart, methodEnd)
        assertFalse(verificationPath.contains("printStackTrace"))
        assertFalse(
            Regex("""Log\.\w+\([^)]*,\s*(e|exception)\s*\)""")
                .containsMatchIn(verificationPath),
        )
    }
}
