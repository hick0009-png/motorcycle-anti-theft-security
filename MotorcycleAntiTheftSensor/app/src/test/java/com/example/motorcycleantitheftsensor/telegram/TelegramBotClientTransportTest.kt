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
}
