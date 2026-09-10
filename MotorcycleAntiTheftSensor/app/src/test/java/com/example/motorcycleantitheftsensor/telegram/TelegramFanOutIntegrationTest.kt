package com.example.motorcycleantitheftsensor.telegram

import com.example.motorcycleantitheftsensor.data.EncryptedPrefsManager
import com.example.motorcycleantitheftsensor.location.LiveLocationHandle
import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.location.MovementTrackingState
import com.example.motorcycleantitheftsensor.location.MovementTrackingStore
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import com.example.motorcycleantitheftsensor.protection.DefaultLivePursuitCoordinator
import com.example.motorcycleantitheftsensor.protection.GuidanceCode
import com.example.motorcycleantitheftsensor.protection.ProtectionState
import com.example.motorcycleantitheftsensor.protection.ProtectionStateTelegramNotifier
import com.example.motorcycleantitheftsensor.protection.PursuitExpiryHandle
import com.example.motorcycleantitheftsensor.protection.PursuitExpiryScheduler
import com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalog
import com.example.motorcycleantitheftsensor.sensor.MovementLocationTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TelegramFanOutIntegrationTest {

    private lateinit var mockPrefs: EncryptedPrefsManager
    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setup() {
        mockPrefs = mock(EncryptedPrefsManager::class.java)
        `when`(mockPrefs.getBotToken()).thenReturn("bot123456:TestTokenKey")
        `when`(mockPrefs.getAllowedChatIds()).thenReturn(setOf("1001"))
        `when`(mockPrefs.isChatIdAllowed("1001")).thenReturn(true)
    }

    @Test
    fun armCommandReplyDeduplicatesStateNotification() {
        val interceptedRequests = mutableListOf<JSONObject>()

        val fakeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            interceptedRequests.add(JSONObject(buffer.readUtf8()))

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

        val client = TelegramBotClient(
            prefsManager = mockPrefs,
            httpClient = testHttpClient,
        )

        val armingMsg = UserGuidanceCatalog.content(GuidanceCode.ARMING).telegramTh!!

        // 1. Simulate Command Reply for /arm
        client.sendTelegramMessage("1001", armingMsg)

        // Wait for single-thread executor to process command reply
        Thread.sleep(150)
        assertEquals(1, interceptedRequests.size)
        assertEquals("1001", interceptedRequests[0].getString("chat_id"))
        assertEquals(armingMsg, interceptedRequests[0].getString("text"))

        // 2. Simulate ProtectionStateTelegramNotifier state change (DISARMED_ONLINE -> ARMING)
        val notifier = ProtectionStateTelegramNotifier()
        val messages = notifier.messagesFor(ProtectionState.DISARMED_ONLINE, ProtectionState.ARMING)
        assertEquals(1, messages.size)
        assertEquals(armingMsg, messages.first())

        // Fire state notification via sendTelegramAlert
        runBlocking {
            client.sendTelegramAlert(messages.first())
        }

        // Assert no duplicate message delivered to Telegram transport (count remains 1)
        assertEquals("State notification must be deduplicated when command reply was sent", 1, interceptedRequests.size)
    }

    @Test
    fun disarmCommandReplyDeduplicatesStateNotification() {
        val interceptedRequests = mutableListOf<JSONObject>()

        val fakeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            interceptedRequests.add(JSONObject(buffer.readUtf8()))

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

        val client = TelegramBotClient(
            prefsManager = mockPrefs,
            httpClient = testHttpClient,
        )

        val disarmMsg = UserGuidanceCatalog.content(GuidanceCode.DISARMED).telegramTh!!

        // 1. Simulate Command Reply for /disarm
        client.sendTelegramMessage("1001", disarmMsg)

        Thread.sleep(150)
        assertEquals(1, interceptedRequests.size)
        assertEquals(disarmMsg, interceptedRequests[0].getString("text"))

        // 2. Simulate ProtectionStateTelegramNotifier state change (ARMED_HEALTHY -> DISARMED_ONLINE)
        val notifier = ProtectionStateTelegramNotifier()
        val messages = notifier.messagesFor(ProtectionState.ARMED_HEALTHY, ProtectionState.DISARMED_ONLINE)
        assertEquals(1, messages.size)

        runBlocking {
            client.sendTelegramAlert(messages.first())
        }

        // Assert duplicate is suppressed
        assertEquals("Disarm state notification must be deduplicated", 1, interceptedRequests.size)
    }

    @Test
    fun movementEventHasSingleOwnerResponsibilityWithoutDuplicateAlertText() = runBlocking {
        var onMovementConfirmedInvoked = false
        var confirmedFix: TrackedLocationFix? = null
        var elapsedNowMs = 10_000L

        val fakeTracking = FakeTracking()
        val fakeStore = FakeStore()
        val fakeTransport = FakeTransport()
        val fakeLabelResolver = FakeLabelResolver()
        val fakeScheduler = FakeScheduler()

        val coordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = fakeTransport,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            onMovementConfirmed = { fix ->
                onMovementConfirmedInvoked = true
                confirmedFix = fix
            },
            wallClockMs = { 10_000L },
            elapsedClockMs = { elapsedNowMs },
        )

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "sess-1")

        // Fix 1: Anchor fix
        coordinator.onLocationFix(fix(13.7563, 100.5018, 10_000L, 5f))

        // Fix 2: First displacement (>50m)
        elapsedNowMs = 12_000L
        coordinator.onLocationFix(fix(13.7580, 100.5018, 12_000L, 5f))

        // Fix 3: Second displacement >= 15s later -> Confirmed movement
        elapsedNowMs = 27_001L
        coordinator.onLocationFix(fix(13.7582, 100.5018, 27_001L, 5f))

        // Assertions:
        // 1. Live Pursuit session was started (Live Location map pin)
        assertEquals(1, fakeTransport.starts.size)

        // 2. onMovementConfirmed callback was invoked (delegating text alert to IncidentDeliveryCoordinator)
        assertTrue(onMovementConfirmedInvoked)
        assertEquals(13.7582, confirmedFix!!.latitude, 0.0001)

        // 3. LivePursuitCoordinator did NOT send duplicate alert text directly
        assertEquals("LivePursuitCoordinator must not send duplicate text alerts when onMovementConfirmed is provided", 0, fakeTransport.alerts.size)
    }

    private fun fix(
        lat: Double,
        lon: Double,
        elapsed: Long,
        accuracy: Float,
        wall: Long = 123456L
    ) = TrackedLocationFix(lat, lon, elapsed, wall, accuracy)
}

private class FakeTracking : MovementLocationTracking {
    var isTrackingState = false
    override fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean {
        isTrackingState = true
        return true
    }
    override fun enterPursuitMode(): Boolean = true
    override fun exitPursuitMode(): Boolean = true
    override fun stopTracking() { isTrackingState = false }
    override fun isTracking(): Boolean = isTrackingState
    override fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix? = null
}

private class FakeStore : MovementTrackingStore {
    var state = MovementTrackingState()
    override suspend fun load(): MovementTrackingState = state
    override suspend fun save(state: MovementTrackingState): Boolean {
        this.state = state
        return true
    }
    override suspend fun clear(): Boolean {
        state = MovementTrackingState()
        return true
    }
}

private class FakeTransport : TelegramLiveLocationTransport {
    val starts = mutableListOf<Pair<TrackedLocationFix, Int>>()
    val alerts = mutableListOf<String>()
    val updates = mutableListOf<Pair<LiveLocationHandle, TrackedLocationFix>>()
    val stops = mutableListOf<LiveLocationHandle>()

    override suspend fun startForOwners(fix: TrackedLocationFix, livePeriodSeconds: Int): List<LiveLocationHandle> {
        starts.add(Pair(fix, livePeriodSeconds))
        return listOf(LiveLocationHandle("1001", 99L))
    }

    override suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit> {
        updates.add(Pair(handle, fix))
        return TelegramCallResult.Success(Unit)
    }

    override suspend fun stop(handle: LiveLocationHandle): TelegramCallResult<Unit> {
        stops.add(handle)
        return TelegramCallResult.Success(Unit)
    }

    override suspend fun alertOwners(text: String): Boolean {
        alerts.add(text)
        return true
    }
}

private class FakeLabelResolver : LocationLabelResolver {
    override suspend fun resolve(fix: TrackedLocationFix): String? = "Bangkok"
}

private class FakeScheduler : PursuitExpiryScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): PursuitExpiryHandle {
        return PursuitExpiryHandle {}
    }
}
