package com.example.motorcycleantitheftsensor.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TelegramLiveLocationApiTest {

    private fun createApi(interceptor: Interceptor): TelegramLiveLocationApi {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return TelegramLiveLocationApiImpl(client)
    }

    @Test
    fun sendLocationReturnsSuccessWhenMessageIdPositive() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(200)
                .message("OK")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":true,"result":{"message_id":999}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Success)
        assertEquals(999L, (result as TelegramCallResult.Success).value)
    }

    @Test
    fun sendLocationReturnsTerminalInvalidResponseWhenMessageIdMissingOrZero() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(200)
                .message("OK")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":true,"result":{}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Terminal)
        assertEquals(TelegramFailureCode.INVALID_RESPONSE, (result as TelegramCallResult.Terminal).code)
    }

    @Test
    fun http401ReturnsUnauthorized() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(401)
                .message("Unauthorized")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Terminal)
        assertEquals(TelegramFailureCode.UNAUTHORIZED, (result as TelegramCallResult.Terminal).code)
    }

    @Test
    fun http429ReturnsRetryableWithServerDelay() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(429)
                .message("Too Many Requests")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 8","parameters":{"retry_after":8}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Retryable)
        assertEquals(8000L, (result as TelegramCallResult.Retryable).retryAfterMs)
    }

    @Test
    fun http500ReturnsRetryableWithoutDelay() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(500)
                .message("Internal Server Error")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Retryable)
        assertEquals(null, (result as TelegramCallResult.Retryable).retryAfterMs)
    }

    @Test
    fun ioExceptionReturnsRetryable() = runBlocking {
        val api = createApi { _ ->
            throw IOException("Connection dropped")
        }

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Retryable)
    }

    @Test
    fun editMessageNotModifiedTreatedAsSuccess() = runBlocking {
        val api = createApi { chain ->
            Response.Builder()
                .code(400)
                .message("Bad Request")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":false,"description":"Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val result = api.editMessageLiveLocation("token", "123", 42L, 13.75, 100.5, 10f)
        assertTrue(result is TelegramCallResult.Success)
    }

    @Test
    fun parameterValidationRejectsBeforeNetworkIo() = runBlocking {
        var networkCalls = 0
        val api = createApi { chain ->
            networkCalls++
            Response.Builder()
                .code(200)
                .message("OK")
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .body("""{"ok":true,"result":{"message_id":1}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val badLat = api.sendLocation("token", "123", 95.0, 100.0, 900, 10f)
        assertTrue(badLat is TelegramCallResult.Terminal)
        assertEquals(TelegramFailureCode.INVALID_REQUEST, (badLat as TelegramCallResult.Terminal).code)

        val badAcc = api.sendLocation("token", "123", 13.0, 100.0, 900, -1f)
        assertTrue(badAcc is TelegramCallResult.Terminal)

        val badLivePeriod = api.sendLocation("token", "123", 13.0, 100.0, 10, 10f)
        assertTrue(badLivePeriod is TelegramCallResult.Terminal)

        val badMessageId = api.editMessageLiveLocation("token", "123", 0L, 13.0, 100.0, 10f)
        assertTrue(badMessageId is TelegramCallResult.Terminal)

        assertEquals(0, networkCalls)
    }

    @Test
    fun coroutineCancellationCancelsCall() = runBlocking {
        var callCancelled = false
        val fakeCall = object : okhttp3.Call {
            private var isCanceled = false
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://api.telegram.org/bottoken/sendLocation").build()
            override fun execute(): Response = throw UnsupportedOperationException()
            override fun enqueue(responseCallback: okhttp3.Callback) {
                // Do not respond immediately, simulate pending request
            }
            override fun cancel() {
                isCanceled = true
                callCancelled = true
            }
            override fun isExecuted(): Boolean = true
            override fun isCanceled(): Boolean = isCanceled
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun clone(): okhttp3.Call = this
        }

        val fakeFactory = okhttp3.Call.Factory { fakeCall }
        val api = TelegramLiveLocationApiImpl(fakeFactory)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val job = scope.launch {
            api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        }
        kotlinx.coroutines.delay(50)
        job.cancel()
        job.join()

        assertTrue(callCancelled)
    }

    @Test
    fun responseArrivingAfterCancellationIsClosed() = runBlocking {
        var responseClosed = false
        lateinit var savedCallback: okhttp3.Callback
        val fakeCall = object : okhttp3.Call {
            private var isCanceled = false
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://api.telegram.org/bottoken/sendLocation").build()
            override fun execute(): Response = throw UnsupportedOperationException()
            override fun enqueue(responseCallback: okhttp3.Callback) {
                savedCallback = responseCallback
            }
            override fun cancel() {
                isCanceled = true
            }
            override fun isExecuted(): Boolean = true
            override fun isCanceled(): Boolean = isCanceled
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun clone(): okhttp3.Call = this
        }

        val fakeFactory = okhttp3.Call.Factory { fakeCall }
        val api = TelegramLiveLocationApiImpl(fakeFactory)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val job = scope.launch {
            api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        }
        kotlinx.coroutines.delay(50)
        job.cancel()
        job.join()

        val responseBody = object : okhttp3.ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 10L
            override fun source() = okio.Buffer().writeUtf8("""{"ok":true}""")
            override fun close() {
                responseClosed = true
                super.close()
            }
        }
        val response = Response.Builder()
            .code(200)
            .message("OK")
            .request(fakeCall.request())
            .protocol(Protocol.HTTP_1_1)
            .body(responseBody)
            .build()

        savedCallback.onResponse(fakeCall, response)
        assertTrue(responseClosed)
    }

    @Test
    fun unexpectedNonCancellationFailureReturnsSanitizedTypedFailure() = runBlocking {
        val fakeCall = object : okhttp3.Call {
            override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://api.telegram.org/bottoken/sendLocation").build()
            override fun execute(): Response = throw UnsupportedOperationException()
            override fun enqueue(responseCallback: okhttp3.Callback) {
                throw RuntimeException("Simulated unexpected crash inside transport enqueue")
            }
            override fun cancel() {}
            override fun isExecuted(): Boolean = false
            override fun isCanceled(): Boolean = false
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun clone(): okhttp3.Call = this
        }

        val fakeFactory = okhttp3.Call.Factory { fakeCall }
        val api = TelegramLiveLocationApiImpl(fakeFactory)

        val result = api.sendLocation("token", "123", 13.75, 100.5, 900, 10f)
        assertTrue(result is TelegramCallResult.Terminal)
        assertEquals(TelegramFailureCode.INVALID_RESPONSE, (result as TelegramCallResult.Terminal).code)
    }
}
