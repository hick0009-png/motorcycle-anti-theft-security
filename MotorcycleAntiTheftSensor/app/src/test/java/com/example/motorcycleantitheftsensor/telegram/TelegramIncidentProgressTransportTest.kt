package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TelegramIncidentProgressTransportTest {
    @Test
    fun progressUpdateEditsTheOpeningMessageForEachOwner() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            bodies += buffer.readUtf8()
            Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":true,"result":{"message_id":42}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getBotToken()).thenReturn("test-token")
        `when`(prefs.getAllowedChatIds()).thenReturn(setOf("123", "456"))
        val transport = TelegramIncidentProgressTransport(client, prefs, onTelegramContact = {})

        assertTrue(transport.open("incident-1", "opened"))
        assertTrue(transport.update("incident-1", "still active"))

        assertEquals(4, requests.size)
        assertTrue(requests.take(2).all { it.url.toString().endsWith("/sendMessage") })
        assertTrue(requests.drop(2).all { it.url.toString().endsWith("/editMessageText") })
        val edited = bodies.drop(2).map(::JSONObject)
        assertTrue(edited.all { it.getLong("message_id") == 42L })
        assertTrue(edited.all { it.getString("text") == "still active" })
    }
}
