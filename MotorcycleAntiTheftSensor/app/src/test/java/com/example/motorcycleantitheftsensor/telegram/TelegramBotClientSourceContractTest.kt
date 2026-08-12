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
    fun tokenVerificationDelegatesWithoutLoggingSensitiveResponseDetails() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()
        val resultMethodStart = source.indexOf("internal fun verifyBotTokenResult")
        val methodEnd = source.indexOf(
            "fun sendTestAlertToOwners",
            startIndex = resultMethodStart,
        )
        assertTrue(
            resultMethodStart >= 0 &&
                methodEnd > resultMethodStart,
        )

        val resultPath = source.substring(resultMethodStart, methodEnd)
        assertTrue(resultPath.contains("botVerifier.verify(cleanToken)"))
        assertFalse(resultPath.contains("printStackTrace"))
        assertFalse(resultPath.contains("response.body"))
        assertFalse(resultPath.contains("Log."))
        assertFalse(Regex("""Log\.\w+\([^\n]*\$(cleanToken|token)""").containsMatchIn(resultPath))
    }
}
