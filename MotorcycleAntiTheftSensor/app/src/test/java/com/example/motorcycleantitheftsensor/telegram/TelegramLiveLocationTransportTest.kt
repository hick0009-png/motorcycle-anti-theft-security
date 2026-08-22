package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.location.LiveLocationHandle
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramLiveLocationTransportTest {
    private lateinit var httpClient: OkHttpClient
    private lateinit var mockPrefsManager: EncryptedPrefsManager
    private lateinit var transport: TelegramLiveLocationTransportImpl
    private val requests = mutableListOf<Request>()
    private val requestBodies = mutableListOf<String>()
    private var simulateFailure = false

    @Before
    fun setup() {
        requests.clear()
        requestBodies.clear()
        simulateFailure = false
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            requestBodies.add(buffer.readUtf8())

            if (simulateFailure) {
                return@Interceptor Response.Builder()
                    .code(500)
                    .message("Server Error")
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }

            val responseString = """{"ok":true,"result":{"message_id":42}}"""
            Response.Builder()
                .code(200)
                .message("OK")
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .body(responseString.toResponseBody("application/json".toMediaType()))
                .build()
        }
        httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        mockPrefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(mockPrefsManager.getBotToken()).thenReturn("mock-token")
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("123", "456"))
        transport = TelegramLiveLocationTransportImpl(httpClient, mockPrefsManager)
    }

    @Test
    fun startForOwnersWithTwoOwnersProducesTwoStartRequestsAndReturnsHandles() = runBlocking {
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("123", "456"))

        val fix = TrackedLocationFix(13.7563, 100.5018, 1000L, 1000L, 10f)
        val handles = transport.startForOwners(fix, 900)

        assertEquals(2, handles.size)
        assertEquals("123", handles[0].chatId)
        assertEquals(42L, handles[0].messageId)
        assertEquals("456", handles[1].chatId)
        assertEquals(42L, handles[1].messageId)

        assertEquals(2, requests.size)
        assertEquals(2, requestBodies.size)

        val json1 = JSONObject(requestBodies[0])
        val json2 = JSONObject(requestBodies[1])

        assertEquals("123", json1.getString("chat_id"))
        assertEquals(13.7563, json1.getDouble("latitude"), 0.0001)
        assertEquals(100.5018, json1.getDouble("longitude"), 0.0001)
        assertEquals(900, json1.getInt("live_period"))
        assertEquals(10.0, json1.getDouble("horizontal_accuracy"), 0.1)

        assertEquals("456", json2.getString("chat_id"))
        assertEquals(900, json2.getInt("live_period"))
    }

    @Test
    fun emptyOwnerListMakesZeroNetworkCalls() = runBlocking {
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(emptySet())

        val fix = TrackedLocationFix(0.0, 0.0, 1000L, 1000L, 10f)
        val handles = transport.startForOwners(fix, 900)

        assertTrue(handles.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun updateSendsEditMessageLiveLocationWithCorrectFields() = runBlocking {
        val handle = LiveLocationHandle("123", 42L)
        val fix = TrackedLocationFix(13.7565, 100.5020, 2000L, 2000L, 15f)

        val result = transport.update(handle, fix)

        assertTrue(result is TelegramCallResult.Success)
        assertEquals(1, requests.size)
        val json = JSONObject(requestBodies[0])
        assertEquals("123", json.getString("chat_id"))
        assertEquals(42L, json.getLong("message_id"))
        assertEquals(13.7565, json.getDouble("latitude"), 0.0001)
        assertEquals(100.5020, json.getDouble("longitude"), 0.0001)
        assertEquals(15.0, json.getDouble("horizontal_accuracy"), 0.1)
    }

    @Test
    fun updateForRevokedOwnerReturnsForbiddenWithoutNetworkIo() = runBlocking {
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("456")) // "123" removed

        val handle = LiveLocationHandle("123", 42L)
        val fix = TrackedLocationFix(13.7565, 100.5020, 2000L, 2000L, 15f)

        val result = transport.update(handle, fix)

        assertTrue(result is TelegramCallResult.Terminal)
        assertEquals(TelegramFailureCode.FORBIDDEN, (result as TelegramCallResult.Terminal).code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun stopSendsStopMessageLiveLocationWithCorrectFields() = runBlocking {
        val handle = LiveLocationHandle("123", 42L)

        val result = transport.stop(handle)

        assertTrue(result is TelegramCallResult.Success)
        assertEquals(1, requests.size)
        val json = JSONObject(requestBodies[0])
        assertEquals("123", json.getString("chat_id"))
        assertEquals(42L, json.getLong("message_id"))
    }

    @Test
    fun httpFailureReturnsRetryable() = runBlocking {
        simulateFailure = true
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("123"))

        val fix = TrackedLocationFix(0.0, 0.0, 1000L, 1000L, 10f)
        val handles = transport.startForOwners(fix, 900)
        assertTrue(handles.isEmpty())

        val handle = LiveLocationHandle("123", 42L)
        val updateResult = transport.update(handle, fix)
        assertTrue(updateResult is TelegramCallResult.Retryable)

        val stopResult = transport.stop(handle)
        assertTrue(stopResult is TelegramCallResult.Retryable)

        val alertSuccess = transport.alertOwners("Hello")
        assertFalse(alertSuccess)
    }

    @Test
    fun alertOwnersSendsTextMessagesToAllOwners() = runBlocking {
        `when`(mockPrefsManager.getAllowedChatIds()).thenReturn(setOf("123", "456"))

        val success = transport.alertOwners("Alert!")

        assertTrue(success)
        assertEquals(2, requests.size)
        val json1 = JSONObject(requestBodies[0])
        val json2 = JSONObject(requestBodies[1])
        assertEquals("123", json1.getString("chat_id"))
        assertEquals("Alert!", json1.getString("text"))
        assertEquals("456", json2.getString("chat_id"))
        assertEquals("Alert!", json2.getString("text"))
    }

    @Test
    fun rapidConsecutiveUpdatesAreThrottledWithoutNetworkIo() = runBlocking {
        val handle = LiveLocationHandle("123", 42L)
        val fix1 = TrackedLocationFix(13.7565, 100.5020, 2000L, 2000L, 15f)
        val fix2 = TrackedLocationFix(13.7566, 100.5021, 2001L, 2001L, 15f)

        val result1 = transport.update(handle, fix1)
        assertTrue(result1 is TelegramCallResult.Success)
        assertEquals(1, requests.size)

        // Immediate second call within MIN_UPDATE_INTERVAL_MS should return Success without emitting HTTP request
        val result2 = transport.update(handle, fix2)
        assertTrue(result2 is TelegramCallResult.Success)
        assertEquals("Second update within rate limit window must not make network call", 1, requests.size)
    }
}
