package com.example.motorcycleantitheftsensor.telegram

import java.io.File
import org.junit.Assert.assertEquals
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
        assertFalse(source.contains("botToken.take("))
        assertFalse(source.contains("getUpdates response body"))
        assertFalse(source.contains("sendMessage response:"))
        assertFalse(source.contains("\"parse_mode\", \"Markdown\""))
        assertFalse(
            Regex("""Log\.\w+\([^)]*,\s*(e|error|exception)\s*\)""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun theLocationRequestCrumbSitsBehindTheDuplicateGate() {
        // The update id is committed only after a command finishes, so while a location fix
        // is being taken Telegram keeps redelivering the same message. Recorded where the
        // update arrives, one `/where` from the owner produced four rows on the test phone —
        // a count of deliveries wearing the shape of a count of requests, in the one row an
        // owner would read to find out whether somebody else had asked.
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()

        val claim = source.indexOf("if (!claimCommand(commandId)) return")
        val crumb = source.indexOf("breadcrumb(BreadcrumbEvent.WHERE")
        assertTrue("the crumb is written", crumb > 0)
        assertTrue("and only once the command has been claimed", crumb > claim)
        assertEquals(
            "recorded once per command, never once per delivery",
            1,
            Regex(Regex.escape("breadcrumb(BreadcrumbEvent.WHERE")).findAll(source).count(),
        )
    }

    @Test
    fun aRefusedCommandIsRecordedWithoutRecordingWhoSentIt() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()
        val denied = source.indexOf("breadcrumb(BreadcrumbEvent.DENIED")
        assertTrue(denied > 0)
        // Whatever else changes here, the crumb takes no arguments that could carry a chat id.
        assertTrue(source.contains("breadcrumb(BreadcrumbEvent.DENIED, emptyList())"))
    }

    @Test
    fun tokenVerificationDelegatesWithoutLoggingSensitiveResponseDetails() {
        val source = File(
            "src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt",
        ).readText()
        val resultMethodStart = source.indexOf("internal suspend fun verifyBotTokenResult")
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
