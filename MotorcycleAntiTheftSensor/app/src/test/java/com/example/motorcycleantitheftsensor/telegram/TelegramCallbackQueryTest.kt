package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.protection.ProtectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A tapped button takes the same path a typed command does — the same authorization, the
 * same queue, the same handler — and these pin that it does.
 */
class TelegramCallbackQueryTest {

    private lateinit var prefs: EncryptedPrefsManager

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        prefs = mock(EncryptedPrefsManager::class.java)
        whenever(prefs.getBotToken()).thenReturn("bot123456:TEST")
        whenever(prefs.commitLastTelegramUpdateId(any())).thenReturn(true)
    }

    private fun pairedOwner() {
        whenever(prefs.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(prefs.isChatIdAllowed("111")).thenReturn(true)
    }

    @Test
    fun aTappedModeButtonReachesTheHandlerAsTheTypedCommand() {
        pairedOwner()
        val executor = RecordingExecutor()

        val run = poll(listOf(callbackJson(1, "111", InlineAction.STATUS_ENTRY.data)), executor)

        assertEquals(
            RemoteCommand.parse("/status ประตู"),
            executor.received.poll(2, TimeUnit.SECONDS),
        )
        // The spinner is stopped, and without a banner: the report is the real answer.
        val answered = run.answered.poll(2, TimeUnit.SECONDS)
        assertNotNull(answered)
        assertNull(answered!!.text)
    }

    @Test
    fun everyModeButtonReachesTheHandlerAsItsOwnMode() {
        for (profile in ProtectionProfile.entries) {
            setup()
            pairedOwner()
            val executor = RecordingExecutor()

            poll(listOf(callbackJson(1, "111", InlineAction.forProfile(profile).data)), executor)

            val command = executor.received.poll(2, TimeUnit.SECONDS)
            assertEquals(
                "button for $profile",
                profile,
                (command as? RemoteCommand.StatusForMode)?.profile,
            )
        }
    }

    /**
     * The whole authorization question for buttons. A stranger who somehow has a message
     * with our buttons on it must get nothing, exactly as a stranger typing the command
     * gets nothing.
     */
    @Test
    fun aTapFromAChatThatIsNotTheOwnersNeverReachesTheHandler() {
        whenever(prefs.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(prefs.isChatIdAllowed("222")).thenReturn(false)
        val executor = RecordingExecutor()

        val run = poll(listOf(callbackJson(1, "222", InlineAction.STATUS.data)), executor)

        assertNull(executor.received.poll(1, TimeUnit.SECONDS))
        // Refused in the button's own banner, not as a message: a refusal posted into the
        // chat is a message the owner never asked for.
        assertTrue(run.sent.isEmpty())
        val answered = run.answered.poll(2, TimeUnit.SECONDS)
        assertNotNull(answered)
        assertNotNull(answered!!.text)
    }

    /** A button from a build that knew a token this one does not. */
    @Test
    fun anUnknownTokenIsAcknowledgedAndOtherwiseIgnored() {
        pairedOwner()
        val executor = RecordingExecutor()

        val run = poll(listOf(callbackJson(1, "111", "zz")), executor)

        assertNull(executor.received.poll(1, TimeUnit.SECONDS))
        assertTrue(run.sent.isEmpty())
        assertNotNull(run.answered.poll(2, TimeUnit.SECONDS))
    }

    /** An update that is neither a message nor a callback must not stall the offset. */
    @Test
    fun anUpdateOfNeitherKindIsSkipped() {
        pairedOwner()
        val executor = RecordingExecutor()

        val run = poll(listOf("""{"update_id": 1, "edited_message": {}}"""), executor)

        assertNull(executor.received.poll(1, TimeUnit.SECONDS))
        assertTrue(run.sent.isEmpty())
        assertNull(run.answered.poll(500, TimeUnit.MILLISECONDS))
    }

    /** The report is where navigating from buttons makes sense, so that is where they ride. */
    @Test
    fun onlyTheStatusReportsCarryTheKeyboard() {
        pairedOwner()
        val executor = RecordingExecutor()

        val withKeyboard = poll(listOf(messageJson(1, "111", "/status")), executor).sent
        assertEquals(1, withKeyboard.size)
        assertNotNull(withKeyboard.single().replyMarkup)

        setup()
        pairedOwner()
        val withoutKeyboard = poll(listOf(messageJson(1, "111", "/disarm")), RecordingExecutor()).sent
        assertEquals(1, withoutKeyboard.size)
        assertNull(withoutKeyboard.single().replyMarkup)
    }

    // -----------------------------------------------------------------

    private class RecordingExecutor : TelegramCommandExecutor {
        val received = LinkedBlockingQueue<RemoteCommand>()

        override suspend fun handle(
            commandId: String,
            command: RemoteCommand,
            reply: suspend (String) -> Unit,
        ) {
            received.offer(command)
            reply("handled")
        }
    }

    private data class Sent(val chatId: String, val text: String, val replyMarkup: String?)

    private data class Answered(val queryId: String, val text: String?)

    private class Run(
        val sent: List<Sent>,
        val answered: LinkedBlockingQueue<Answered>,
    )

    private fun poll(updates: List<String>, executor: TelegramCommandExecutor): Run {
        val sent = mutableListOf<Sent>()
        val answered = LinkedBlockingQueue<Answered>()
        val latch = CountDownLatch(1)

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            when {
                url.contains("getUpdates") -> {
                    val countBefore = latch.count
                    latch.countDown()
                    if (countBefore == 0L) {
                        Thread.sleep(100)
                        return@Interceptor ok(request, """{"ok":true,"result":[]}""")
                    }
                    ok(request, """{"ok":true,"result":[${updates.joinToString(",")}]}""")
                }
                url.contains("answerCallbackQuery") -> {
                    val json = org.json.JSONObject(bodyOf(request))
                    answered.offer(
                        Answered(
                            queryId = json.getString("callback_query_id"),
                            text = if (json.has("text")) json.getString("text") else null,
                        ),
                    )
                    ok(request, """{"ok":true,"result":true}""")
                }
                url.contains("sendMessage") -> {
                    val json = org.json.JSONObject(bodyOf(request))
                    synchronized(sent) {
                        sent.add(
                            Sent(
                                chatId = json.getString("chat_id"),
                                text = json.getString("text"),
                                replyMarkup = if (json.has("reply_markup")) {
                                    json.getJSONObject("reply_markup").toString()
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                    ok(request, """{"ok":true}""")
                }
                else -> ok(request, "{}")
            }
        }

        val client = TelegramBotClient(
            prefsManager = prefs,
            commandHandler = executor,
            httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )
        client.startPolling()
        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(500)
        client.stopPolling()
        return Run(synchronized(sent) { sent.toList() }, answered)
    }

    private fun bodyOf(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun ok(request: okhttp3.Request, body: String): Response = Response.Builder()
        .code(200)
        .message("OK")
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun callbackJson(updateId: Long, chatId: String, data: String): String = """
        {
          "update_id": $updateId,
          "callback_query": {
            "id": "cb-$updateId",
            "from": { "id": $chatId },
            "message": { "chat": { "id": "$chatId" } },
            "data": "$data"
          }
        }
    """.trimIndent()

    private fun messageJson(updateId: Long, chatId: String, text: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "chat": { "id": "$chatId" },
            "text": "$text"
          }
        }
    """.trimIndent()
}
