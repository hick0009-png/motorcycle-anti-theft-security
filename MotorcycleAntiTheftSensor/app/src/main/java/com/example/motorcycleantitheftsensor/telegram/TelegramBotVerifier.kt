package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import java.io.IOException
import okhttp3.Request
import org.json.JSONObject

internal sealed interface TelegramBotVerificationResult {
    data class Verified(val username: String, val botId: String) : TelegramBotVerificationResult
    data object Rejected : TelegramBotVerificationResult
    data object ConnectionFailure : TelegramBotVerificationResult
}

internal data class TelegramVerificationHttpResponse(
    val code: Int,
    val body: String?,
)

internal class TelegramBotVerifier(
    private val execute: (Request) -> TelegramVerificationHttpResponse = { request ->
        TlsPinningClient.client.newCall(request).execute().use { response ->
            TelegramVerificationHttpResponse(response.code, response.body?.string())
        }
    },
) {
    fun verify(rawToken: String): TelegramBotVerificationResult {
        val token = normalizeTelegramBotToken(rawToken)
        if (token.isBlank()) return TelegramBotVerificationResult.Rejected
        val request = try {
            Request.Builder()
                .url("https://api.telegram.org/bot$token/getMe")
                .build()
        } catch (_: IllegalArgumentException) {
            return TelegramBotVerificationResult.Rejected
        }
        val response = try {
            execute(request)
        } catch (_: IOException) {
            return TelegramBotVerificationResult.ConnectionFailure
        }
        if (response.code !in 200..299) return TelegramBotVerificationResult.Rejected
        val json = try {
            JSONObject(response.body ?: return TelegramBotVerificationResult.Rejected)
        } catch (_: RuntimeException) {
            return TelegramBotVerificationResult.Rejected
        }
        if (!json.optBoolean("ok", false)) return TelegramBotVerificationResult.Rejected
        val result = json.optJSONObject("result") ?: return TelegramBotVerificationResult.Rejected
        val username = result.optString("username").trim()
        val botId = result.optLong("id", 0L)
        if (username.isBlank() || botId <= 0L) return TelegramBotVerificationResult.Rejected
        return TelegramBotVerificationResult.Verified(username, botId.toString())
    }
}
