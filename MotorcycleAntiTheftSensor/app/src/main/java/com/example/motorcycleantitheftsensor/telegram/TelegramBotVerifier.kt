package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.network.TlsPinningClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

internal fun interface TelegramVerificationTransport {
    suspend fun execute(request: Request): TelegramVerificationHttpResponse
}

internal class OkHttpTelegramVerificationTransport(
    pinnedClient: OkHttpClient = TlsPinningClient.client,
    callTimeoutSeconds: Long = 10L,
) : TelegramVerificationTransport {
    private val client = pinnedClient.newBuilder()
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: Request): TelegramVerificationHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val httpResponse = response.use { resp ->
                            TelegramVerificationHttpResponse(
                                code = resp.code,
                                body = resp.body?.string(),
                            )
                        }
                        if (continuation.isActive) {
                            continuation.resume(httpResponse)
                        }
                    } catch (e: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}

internal class TelegramBotVerifier(
    private val transport: TelegramVerificationTransport = OkHttpTelegramVerificationTransport(),
) {
    suspend fun verify(rawToken: String): TelegramBotVerificationResult {
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
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return TelegramBotVerificationResult.ConnectionFailure
        } catch (e: RuntimeException) {
            currentCoroutineContext().ensureActive()
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
