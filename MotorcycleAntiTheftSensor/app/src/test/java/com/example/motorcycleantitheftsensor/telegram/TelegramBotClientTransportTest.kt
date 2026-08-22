package com.example.motorcycleantitheftsensor.telegram

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBotClientTransportTest {

    @Test
    fun sendTelegramMessageToApi_sendsPlainTextWithoutParseMode() {
        // Step 1: Write a failing transport test
        // Assert that the sent JSON has chat_id and exact text, but has no parse_mode key.

        var interceptedRequestBody = ""

        val fakeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            interceptedRequestBody = buffer.readUtf8()

            Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body("{\"ok\":true}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val status = "POWER_THERMAL: healthy (accelerometer_magnitude_sensitivity_5)"

        val success = sendTelegramMessageToApi(
            httpClient = testHttpClient,
            botToken = "bot123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11",
            chatId = "9999999",
            text = status
        )

        assertTrue("Expected sendTelegramMessageToApi to return true", success)

        // Parse intercepted body
        val jsonBody = org.json.JSONObject(interceptedRequestBody)

        assertEquals("9999999", jsonBody.getString("chat_id"))
        assertEquals(status, jsonBody.getString("text"))
        assertFalse("JSON body must not contain parse_mode", jsonBody.has("parse_mode"))
    }

    @Test
    fun splitTelegramMessage_splitsLongMessagesProperly() {
        val shortMsg = "Hello Telegram"
        val singleResult = splitTelegramMessage(shortMsg, maxLength = 4096)
        assertEquals(listOf("Hello Telegram"), singleResult)

        // Multi-line message exceeding maxLength
        val line1 = "A".repeat(3000)
        val line2 = "B".repeat(2000)
        val multiLine = "$line1\n$line2"
        val splitResult = splitTelegramMessage(multiLine, maxLength = 4096)
        assertEquals(2, splitResult.size)
        assertEquals(line1, splitResult[0])
        assertEquals(line2, splitResult[1])

        // Single continuous string exceeding maxLength
        val longString = "X".repeat(5000)
        val longStringSplit = splitTelegramMessage(longString, maxLength = 4096)
        assertEquals(2, longStringSplit.size)
        assertEquals(4096, longStringSplit[0].length)
        assertEquals(904, longStringSplit[1].length)
    }

    @Test
    fun sendTelegramMessageToApi_splitsAndSendsAllChunksWhenMessageExceeds4096() {
        val interceptedBodies = mutableListOf<String>()

        val fakeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            interceptedBodies.add(buffer.readUtf8())

            Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body("{\"ok\":true}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(fakeInterceptor)
            .build()

        val longMessage = "Line 1: " + "A".repeat(3000) + "\nLine 2: " + "B".repeat(2000)

        val success = sendTelegramMessageToApi(
            httpClient = testHttpClient,
            botToken = "123456:ABC-DEF",
            chatId = "112233",
            text = longMessage,
        )

        assertTrue(success)
        assertEquals(2, interceptedBodies.size)
        val body1 = org.json.JSONObject(interceptedBodies[0])
        val body2 = org.json.JSONObject(interceptedBodies[1])
        assertEquals("112233", body1.getString("chat_id"))
        assertEquals("112233", body2.getString("chat_id"))
        assertTrue(body1.getString("text").startsWith("Line 1:"))
        assertTrue(body2.getString("text").startsWith("Line 2:"))
    }
}
