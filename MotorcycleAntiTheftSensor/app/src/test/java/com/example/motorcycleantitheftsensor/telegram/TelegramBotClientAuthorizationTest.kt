package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.security.PairingResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class TelegramBotClientAuthorizationTest {

    private lateinit var mockPrefsManager: EncryptedPrefsManager

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockPrefsManager = mock(EncryptedPrefsManager::class.java)
        whenever(mockPrefsManager.getBotToken()).thenReturn("bot123456:TEST")
        whenever(mockPrefsManager.commitLastTelegramUpdateId(any())).thenReturn(true)
    }

    @Test
    fun pairingProcessRejectsWrongCode() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(emptySet())
        whenever(mockPrefsManager.claimPairingCode(any(), any(), any(), any())).thenAnswer {
            PairingResult.Rejected
        }

        val sentMessages = runPollingWithUpdates(
            listOf(
                updateJson(1, "111", "/pair WRONGCODE")
            )
        )
        assertTrue("Expected rejection message", sentMessages.any { it.chatId == "111" && it.text.contains("⚠️ รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ") })
    }

    @Test
    fun pairingProcessRejectsExpiredCode() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(emptySet())
        whenever(mockPrefsManager.claimPairingCode(any(), any(), any(), any())).thenReturn(PairingResult.Expired)

        val sentMessages = runPollingWithUpdates(
            listOf(
                updateJson(1, "111", "/pair EXPIRED")
            )
        )
        assertTrue("Expected expired message", sentMessages.any { it.chatId == "111" && it.text.contains("⚠️ รหัสจับคู่ไม่ถูกต้องหรือหมดอายุ") })
    }

    @Test
    fun pairingProcessAcceptsFreshCode() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(emptySet())
        whenever(mockPrefsManager.claimPairingCode(any(), any(), any(), any())).thenReturn(PairingResult.Accepted)

        val sentMessages = runPollingWithUpdates(
            listOf(
                updateJson(1, "111", "/pair GOODCODE")
            )
        )
        assertTrue("Expected success message", sentMessages.any { it.chatId == "111" && it.text.contains("✅ จับคู่ Telegram สำเร็จ") })
    }

    @Test
    fun alreadyPairedIgnoresPairingCommandAndReturnsUnknownCommand() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(mockPrefsManager.isChatIdAllowed("111")).thenReturn(true)

        val sentMessages = runPollingWithUpdates(
            listOf(
                updateJson(1, "111", "/pair ANOTHERCODE")
            )
        )
        assertTrue("Expected unknown command", sentMessages.any { it.chatId == "111" && it.text.contains("ไม่พบคำสั่ง") })
    }

    @Test
    fun unpairedSenderGetsUnauthorized() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(mockPrefsManager.isChatIdAllowed("222")).thenReturn(false)

        val sentMessages = runPollingWithUpdates(
            listOf(
                updateJson(1, "222", "/status") // 222 tries to use it
            )
        )
        assertTrue("Expected unauthorized command", sentMessages.any { it.chatId == "222" && it.text.contains("ไม่ได้รับอนุญาตให้สั่งงาน") })
    }

    @Test
    fun pairedOwnerDisarmReachesExecutorWithoutTotp() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(mockPrefsManager.isChatIdAllowed("111")).thenReturn(true)
        val executor = RecordingCommandExecutor()

        runPollingWithUpdates(
            updates = listOf(updateJson(1, "111", "/disarm")),
            commandExecutor = executor,
        )

        assertEquals(RemoteCommand.Disarm, executor.received.poll(2, TimeUnit.SECONDS))
    }

    @Test
    fun unpairedDisarmNeverReachesExecutor() {
        whenever(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("111"))
        whenever(mockPrefsManager.isChatIdAllowed("222")).thenReturn(false)
        val executor = RecordingCommandExecutor()

        runPollingWithUpdates(
            updates = listOf(updateJson(1, "222", "/disarm")),
            commandExecutor = executor,
        )

        assertTrue(executor.received.isEmpty())
    }

    private class RecordingCommandExecutor : TelegramCommandExecutor {
        val received = java.util.concurrent.LinkedBlockingQueue<RemoteCommand>()

        override suspend fun handle(
            commandId: String,
            command: RemoteCommand,
            reply: suspend (String) -> Unit,
        ) {
            received.offer(command)
            reply("handled")
        }
    }

    data class SentMsg(val chatId: String, val text: String)

    private fun runPollingWithUpdates(
        updates: List<String>,
        commandExecutor: TelegramCommandExecutor? = null,
    ): List<SentMsg> {
        val sentMessages = mutableListOf<SentMsg>()
        val latch = CountDownLatch(1) // Wait for getUpdates to be called

        val fakeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("getUpdates")) {
                val updatesArray = updates.joinToString(",")
                val json = """{"ok":true,"result":[$updatesArray]}"""
                val countBefore = latch.count
                latch.countDown()

                // Return an empty array on subsequent calls to avoid infinite loops
                if (countBefore == 0L) {
                   Thread.sleep(100)
                   return@Interceptor buildResponse(request, """{"ok":true,"result":[]}""")
                }

                buildResponse(request, json)
            } else if (url.contains("sendMessage")) {
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                val jsonBody = org.json.JSONObject(bodyStr)
                sentMessages.add(SentMsg(jsonBody.getString("chat_id"), jsonBody.getString("text")))
                buildResponse(request, """{"ok":true}""")
            } else {
                buildResponse(request, "{}")
            }
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val client = TelegramBotClient(
            prefsManager = mockPrefsManager,
            commandHandler = commandExecutor,
            httpClient = testHttpClient,
        )
        client.startPolling()

        latch.await(2, TimeUnit.SECONDS)
        // give a tiny bit of time for message processing
        Thread.sleep(500)
        client.stopPolling()

        return sentMessages
    }

    private fun buildResponse(request: okhttp3.Request, bodyJson: String): Response {
        return Response.Builder()
            .code(200)
            .message("OK")
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .body(bodyJson.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun updateJson(updateId: Long, chatId: String, text: String): String {
        return """
            {
              "update_id": $updateId,
              "message": {
                "chat": {
                  "id": "$chatId"
                },
                "text": "$text"
              }
            }
        """.trimIndent()
    }
}
