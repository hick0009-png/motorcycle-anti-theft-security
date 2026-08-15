package com.example.motorcycleantitheftsensor.sensor

import android.location.LocationManager
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocationObservationProviderTest {

    private lateinit var fakeClient: FakeLocationUpdatesClient
    private lateinit var provider: LocationObservationProvider
    private var simulatedElapsedMs = 100_000L

    @Before
    fun setup() {
        simulatedElapsedMs = 100_000L
        fakeClient = FakeLocationUpdatesClient()
        provider = LocationObservationProvider(
            client = fakeClient,
            elapsedClock = { simulatedElapsedMs },
            wallClock = { 500_000L },
        )
    }

    @Test
    fun startArmedRegistersArmedProfileWithGpsAndNetworkOnly() {
        val success = provider.startArmedTracking {}
        assertTrue(success)
        assertEquals(1, fakeClient.requests.size)
        val req = fakeClient.requests[0]
        assertEquals(LocationTrackingMode.ARMED, req.mode)
        assertEquals(10_000L, req.minTimeMs)
        assertEquals(5f, req.minDistanceMeters)
        assertEquals(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), req.providers)
    }

    @Test
    fun startArmedTwiceRegistersOnlyOnceAndUpdatesCallback() {
        val fixes1 = mutableListOf<TrackedLocationFix>()
        val fixes2 = mutableListOf<TrackedLocationFix>()
        provider.startArmedTracking { fixes1 += it }
        provider.startArmedTracking { fixes2 += it }
        assertEquals(1, fakeClient.requests.size)
        val fix = fix(1.0, 1.0, 1000L, 5f)
        fakeClient.emitCurrent(fix)
        assertTrue(fixes1.isEmpty())
        assertTrue(fixes2.isNotEmpty())
    }

    @Test
    fun failedArmedStartLeavesModeStoppedAndAllowsRetry() {
        fakeClient.registrationResult = LocationRegistrationResult.Unavailable(LocationStartFailure.PERMISSION_DENIED)
        val success1 = provider.startArmedTracking {}
        assertFalse(success1)
        assertEquals("provider/permission failure", provider.currentObservation().diagnostic)

        // Retry with success
        fakeClient.registrationResult = LocationRegistrationResult.Started(LocationRegistration {})
        val success2 = provider.startArmedTracking {}
        assertTrue(success2)
        assertEquals(2, fakeClient.requests.size)
    }

    @Test
    fun pursuitModeRegistersPursuitProfileWith5sAnd5m() {
        provider.startArmedTracking {}
        provider.enterPursuitMode()
        assertEquals(2, fakeClient.requests.size)
        val req = fakeClient.requests[1]
        assertEquals(LocationTrackingMode.PURSUIT, req.mode)
        assertEquals(5_000L, req.minTimeMs)
        assertEquals(5f, req.minDistanceMeters)
        assertEquals(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), req.providers)
    }

    @Test
    fun failedPursuitUpgradeKeepsArmedRegistrationAndReturnsFalse() {
        var armedCancelled = false
        fakeClient.customRegistration = LocationRegistration { armedCancelled = true }
        assertTrue(provider.startArmedTracking {})
        fakeClient.customRegistration = null

        fakeClient.registrationResult = LocationRegistrationResult.Unavailable(LocationStartFailure.REGISTRATION_FAILED)
        val entered = provider.enterPursuitMode()
        assertFalse(entered)
        assertFalse(armedCancelled)
        assertTrue(provider.isTracking())
    }

    @Test
    fun failedArmedDowngradeKeepsPursuitRegistrationAndReturnsFalse() {
        assertTrue(provider.startArmedTracking {})
        assertTrue(provider.enterPursuitMode())

        var pursuitCancelled = false
        fakeClient.customRegistration = LocationRegistration { pursuitCancelled = true }
        fakeClient.registrationResult = LocationRegistrationResult.Unavailable(LocationStartFailure.REGISTRATION_FAILED)
        fakeClient.customRegistration = null
        val exited = provider.exitPursuitMode()
        assertFalse(exited)
        assertFalse(pursuitCancelled)
        assertTrue(provider.isTracking())
    }

    @Test
    fun successfulDowngradeCancelsOldRegistrationAfterSwap() {
        var pursuitCancelled = false
        provider.startArmedTracking {}
        fakeClient.customRegistration = LocationRegistration { pursuitCancelled = true }
        provider.enterPursuitMode()
        fakeClient.customRegistration = null

        var armedCancelled = false
        fakeClient.customRegistration = LocationRegistration { armedCancelled = true }
        val exited = provider.exitPursuitMode()
        assertTrue(exited)
        assertTrue(pursuitCancelled)
        assertFalse(armedCancelled)
        assertTrue(provider.isTracking())
    }

    @Test
    fun isTrackingReflectsARealRegistration() {
        assertFalse(provider.isTracking())
        fakeClient.registrationResult = LocationRegistrationResult.Unavailable(LocationStartFailure.PERMISSION_DENIED)
        assertFalse(provider.startArmedTracking {})
        assertFalse(provider.isTracking())

        fakeClient.registrationResult = LocationRegistrationResult.Started(LocationRegistration {})
        assertTrue(provider.startArmedTracking {})
        assertTrue(provider.isTracking())

        provider.stopTracking()
        assertFalse(provider.isTracking())
    }

    @Test
    fun failedPursuitTransitionRemainsArmedAndIsRetryable() {
        provider.startArmedTracking {}
        assertEquals(1, fakeClient.requests.size)

        fakeClient.registrationResult = LocationRegistrationResult.Unavailable(LocationStartFailure.REGISTRATION_FAILED)
        provider.enterPursuitMode()

        // Should still be armed and retryable
        fakeClient.registrationResult = LocationRegistrationResult.Started(LocationRegistration {})
        provider.enterPursuitMode()
        assertEquals(3, fakeClient.requests.size)
    }

    @Test
    fun pursuitModeTwiceRegistersPursuitOnlyOnce() {
        provider.startArmedTracking {}
        provider.enterPursuitMode()
        provider.enterPursuitMode()
        assertEquals(2, fakeClient.requests.size)
    }

    @Test
    fun stopUnregistersAndCancelsRegistration() {
        var cancelled = false
        fakeClient.customRegistration = LocationRegistration { cancelled = true }
        provider.startArmedTracking {}
        provider.stopTracking()
        assertTrue(cancelled)
        assertNull(provider.currentUsableFix(simulatedElapsedMs))
    }

    @Test
    fun stopRacingLateRegistrationCancelsRegistrationAndForwardsNoFix() {
        val enteredRegister = CountDownLatch(1)
        val releaseRegister = CountDownLatch(1)
        var cancelledCount = 0
        val slowRegistration = LocationRegistration { cancelledCount++ }
        val capturedCallbacks = mutableListOf<(TrackedLocationFix) -> Unit>()
        val blockingClient = object : LocationUpdatesClient {
            override fun register(
                request: LocationTrackingRequest,
                onFix: (TrackedLocationFix) -> Unit,
            ): LocationRegistrationResult {
                capturedCallbacks += onFix
                enteredRegister.countDown()
                releaseRegister.await(5, TimeUnit.SECONDS)
                return LocationRegistrationResult.Started(slowRegistration)
            }
            override fun lastKnownFixes(): List<TrackedLocationFix> = emptyList()
        }

        val racingProvider = LocationObservationProvider(
            client = blockingClient,
            elapsedClock = { simulatedElapsedMs },
            wallClock = { 500_000L },
        )

        val executor = Executors.newSingleThreadExecutor()
        val fixes = mutableListOf<TrackedLocationFix>()
        val future = executor.submit<Boolean> {
            racingProvider.startArmedTracking { fixes += it }
        }

        assertTrue(enteredRegister.await(5, TimeUnit.SECONDS))
        racingProvider.stopTracking()
        releaseRegister.countDown()
        val success = future.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertFalse(success)
        assertEquals(1, cancelledCount)
        assertFalse(racingProvider.isTracking())
        // Late callback arriving from the unregistered listener is ignored
        capturedCallbacks.forEach { it(fix(1.0, 1.0, simulatedElapsedMs, 5f)) }
        assertTrue(fixes.isEmpty())
        assertNull(racingProvider.currentUsableFix(simulatedElapsedMs))
    }

    @Test
    fun oldCallbackCannotOverwriteNewerFix() {
        provider.startArmedTracking { }
        val oldCallback = fakeClient.callbacks.single()
        provider.enterPursuitMode()
        fakeClient.emitCurrent(fix(1.0, 1.0, 20_000L, 5f))
        oldCallback(fix(2.0, 2.0, 30_000L, 5f))
        assertEquals(1.0, provider.currentUsableFix(20_001L)!!.latitude, 0.0)
    }

    @Test
    fun callbackCanReenterProviderWithoutDeadlock() {
        provider.startArmedTracking { provider.currentUsableFix(it.elapsedRealtimeMs) }
        fakeClient.emitCurrent(fix(1.0, 1.0, 20_000L, 5f))
    }

    @Test
    fun currentUsableFixAppliesFreshnessPolicy() {
        provider.startArmedTracking {}
        val nowElapsedMs = 100_000L
        simulatedElapsedMs = nowElapsedMs

        // Fresh fix
        fakeClient.emit(fix(1.0, 1.0, nowElapsedMs - 1000L, 10f))
        assertNotNull(provider.currentUsableFix(nowElapsedMs))

        // Stale fix older than 30s
        simulatedElapsedMs = nowElapsedMs + 31_000L
        assertNull(provider.currentUsableFix(simulatedElapsedMs))
    }

    @Test
    fun inaccurateFixIsRejected() {
        provider.startArmedTracking {}
        val nowElapsedMs = 100_000L
        fakeClient.emit(fix(1.0, 1.0, nowElapsedMs - 1000L, 101f))
        assertNull(provider.currentUsableFix(nowElapsedMs))
    }

    @Test
    fun futureFixIsRejected() {
        provider.startArmedTracking {}
        val nowElapsedMs = 100_000L
        fakeClient.emit(fix(1.0, 1.0, nowElapsedMs + 1000L, 10f))
        assertNull(provider.currentUsableFix(nowElapsedMs))
    }

    @Test
    fun currentObservationDiagnosticDoesNotContainRawLatitudeOrLongitude() {
        provider.startArmedTracking {}
        fakeClient.emit(fix(13.7563, 100.5018, 99_000L, 8f))
        val obs = provider.currentObservation()
        assertTrue(obs.valid)
        val diag = obs.diagnostic ?: ""
        assertTrue(diag.startsWith("fix age_ms="))
        assertTrue(diag.contains("accuracy_m="))
        assertFalse(diag.contains("lat="))
        assertFalse(diag.contains("lon="))
        assertFalse(diag.contains("13.7563"))
        assertFalse(diag.contains("100.5018"))
    }

    private fun fix(lat: Double, lon: Double, elapsed: Long, accuracy: Float) =
        TrackedLocationFix(lat, lon, elapsed, 123456L, accuracy)
}

private class FakeLocationUpdatesClient : LocationUpdatesClient {
    val requests = mutableListOf<LocationTrackingRequest>()
    val callbacks = mutableListOf<(TrackedLocationFix) -> Unit>()
    var fixes: List<TrackedLocationFix> = emptyList()
    var registrationResult: LocationRegistrationResult = LocationRegistrationResult.Started(LocationRegistration {})
    var customRegistration: LocationRegistration? = null

    override fun register(
        request: LocationTrackingRequest,
        onFix: (TrackedLocationFix) -> Unit,
    ): LocationRegistrationResult {
        requests += request
        callbacks += onFix
        return if (customRegistration != null) {
            LocationRegistrationResult.Started(customRegistration!!)
        } else {
            registrationResult
        }
    }

    override fun lastKnownFixes(): List<TrackedLocationFix> = fixes

    fun emitCurrent(fix: TrackedLocationFix) {
        callbacks.lastOrNull()?.invoke(fix)
    }

    fun emit(fix: TrackedLocationFix) {
        emitCurrent(fix)
    }
}
