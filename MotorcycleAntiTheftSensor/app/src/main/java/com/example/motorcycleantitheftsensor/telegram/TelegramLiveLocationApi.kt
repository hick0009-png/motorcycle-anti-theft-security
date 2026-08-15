package com.example.motorcycleantitheftsensor.telegram

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

sealed interface TelegramCallResult<out T> {
    data class Success<T>(val value: T) : TelegramCallResult<T>
    data class Retryable(val retryAfterMs: Long? = null) : TelegramCallResult<Nothing>
    data class Terminal(val code: TelegramFailureCode) : TelegramCallResult<Nothing>
}

enum class TelegramFailureCode {
    UNAUTHORIZED,
    FORBIDDEN,
    MESSAGE_UNAVAILABLE,
    INVALID_RESPONSE,
    INVALID_REQUEST,
}

interface TelegramLiveLocationApi {
    suspend fun sendLocation(
        botToken: String,
        chatId: String,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        horizontalAccuracyMeters: Float,
    ): TelegramCallResult<Long>

    suspend fun editMessageLiveLocation(
        botToken: String,
        chatId: String,
        messageId: Long,
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Float,
    ): TelegramCallResult<Unit>

    suspend fun stopMessageLiveLocation(
        botToken: String,
        chatId: String,
        messageId: Long,
    ): TelegramCallResult<Unit>

    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
    ): TelegramCallResult<Long>
}

class TelegramLiveLocationApiImpl(
    private val client: Call.Factory = defaultOkHttpClient,
) : TelegramLiveLocationApi {

    companion object {
        private val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun sendLocation(
        botToken: String,
        chatId: String,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        horizontalAccuracyMeters: Float,
    ): TelegramCallResult<Long> {
        if (botToken.isBlank() || chatId.isBlank()) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        if (!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters < 0f || horizontalAccuracyMeters > 1500f) {
            return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        }
        if (livePeriodSeconds !in 60..86400) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)

        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("live_period", livePeriodSeconds)
            put("horizontal_accuracy", horizontalAccuracyMeters.toDouble())
        }

        return executeRequest(
            url = "https://api.telegram.org/bot$botToken/sendLocation",
            jsonBody = json.toString(),
            parseResult = { obj ->
                val res = obj.optJSONObject("result")
                val messageId = res?.optLong("message_id", -1L) ?: -1L
                if (messageId > 0L) {
                    TelegramCallResult.Success(messageId)
                } else {
                    TelegramCallResult.Terminal(TelegramFailureCode.INVALID_RESPONSE)
                }
            }
        )
    }

    override suspend fun editMessageLiveLocation(
        botToken: String,
        chatId: String,
        messageId: Long,
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Float,
    ): TelegramCallResult<Unit> {
        if (botToken.isBlank() || chatId.isBlank() || messageId <= 0L) {
            return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        }
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        if (!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters < 0f || horizontalAccuracyMeters > 1500f) {
            return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        }

        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("horizontal_accuracy", horizontalAccuracyMeters.toDouble())
        }

        return executeRequest(
            url = "https://api.telegram.org/bot$botToken/editMessageLiveLocation",
            jsonBody = json.toString(),
            parseResult = { TelegramCallResult.Success(Unit) }
        )
    }

    override suspend fun stopMessageLiveLocation(
        botToken: String,
        chatId: String,
        messageId: Long,
    ): TelegramCallResult<Unit> {
        if (botToken.isBlank() || chatId.isBlank() || messageId <= 0L) {
            return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
        }

        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
        }

        return executeRequest(
            url = "https://api.telegram.org/bot$botToken/stopMessageLiveLocation",
            jsonBody = json.toString(),
            parseResult = { TelegramCallResult.Success(Unit) }
        )
    }

    override suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
    ): TelegramCallResult<Long> {
        if (botToken.isBlank() || chatId.isBlank()) return TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)

        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }

        return executeRequest(
            url = "https://api.telegram.org/bot$botToken/sendMessage",
            jsonBody = json.toString(),
            parseResult = { obj ->
                val res = obj.optJSONObject("result")
                val messageId = res?.optLong("message_id", -1L) ?: -1L
                if (messageId > 0L) {
                    TelegramCallResult.Success(messageId)
                } else {
                    TelegramCallResult.Terminal(TelegramFailureCode.INVALID_RESPONSE)
                }
            }
        )
    }

    private suspend fun <T> executeRequest(
        url: String,
        jsonBody: String,
        parseResult: (JSONObject) -> TelegramCallResult<T>,
    ): TelegramCallResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = executeCancellable(request)
            response.use { resp ->
                val code = resp.code
                val bodyString = resp.body?.string().orEmpty()

                when (code) {
                    401 -> TelegramCallResult.Terminal(TelegramFailureCode.UNAUTHORIZED)
                    403 -> TelegramCallResult.Terminal(TelegramFailureCode.FORBIDDEN)
                    429 -> {
                        val retryAfterMs = runCatching {
                            val obj = JSONObject(bodyString)
                            obj.optJSONObject("parameters")?.optLong("retry_after")?.let { it * 1000L }
                        }.getOrNull()
                        TelegramCallResult.Retryable(retryAfterMs)
                    }
                    in 500..599 -> TelegramCallResult.Retryable(null)
                    else -> {
                        try {
                            val obj = JSONObject(bodyString)
                            val ok = obj.optBoolean("ok", false)
                            if (ok) {
                                parseResult(obj)
                            } else {
                                val desc = obj.optString("description", "")
                                when {
                                    desc.contains("message is not modified", ignoreCase = true) -> {
                                        @Suppress("UNCHECKED_CAST")
                                        TelegramCallResult.Success(Unit as T)
                                    }
                                    desc.contains("MESSAGE_ID_INVALID", ignoreCase = true) ||
                                        desc.contains("message to edit not found", ignoreCase = true) ||
                                        desc.contains("chat not found", ignoreCase = true) -> {
                                        TelegramCallResult.Terminal(TelegramFailureCode.MESSAGE_UNAVAILABLE)
                                    }
                                    desc.contains("blocked", ignoreCase = true) ||
                                        desc.contains("deactivated", ignoreCase = true) ||
                                        desc.contains("forbidden", ignoreCase = true) -> {
                                        TelegramCallResult.Terminal(TelegramFailureCode.FORBIDDEN)
                                    }
                                    else -> {
                                        if (code == 400) {
                                            TelegramCallResult.Terminal(TelegramFailureCode.INVALID_REQUEST)
                                        } else {
                                            TelegramCallResult.Terminal(TelegramFailureCode.INVALID_RESPONSE)
                                        }
                                    }
                                }
                            }
                        } catch (_: JSONException) {
                            TelegramCallResult.Terminal(TelegramFailureCode.INVALID_RESPONSE)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            TelegramCallResult.Retryable(null)
        } catch (_: Exception) {
            TelegramCallResult.Terminal(TelegramFailureCode.INVALID_RESPONSE)
        }
    }

    private suspend fun executeCancellable(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response) { _, _, _ ->
                        response.close()
                    }
                } else {
                    response.close()
                }
            }
        })
    }
}
