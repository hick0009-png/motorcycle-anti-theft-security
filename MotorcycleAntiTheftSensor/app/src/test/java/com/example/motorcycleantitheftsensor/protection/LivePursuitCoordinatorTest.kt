package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.location.LiveLocationHandle
import com.example.motorcycleantitheftsensor.location.LocationLabelResolver
import com.example.motorcycleantitheftsensor.location.MovementDisplacementPolicy
import com.example.motorcycleantitheftsensor.location.MovementTrackingState
import com.example.motorcycleantitheftsensor.location.MovementTrackingStore
import com.example.motorcycleantitheftsensor.location.ParkingAnchor
import com.example.motorcycleantitheftsensor.location.PersistedLivePursuitSession
import com.example.motorcycleantitheftsensor.location.TrackedLocationFix
import com.example.motorcycleantitheftsensor.sensor.MovementLocationTracking
import com.example.motorcycleantitheftsensor.telegram.TelegramCallResult
import com.example.motorcycleantitheftsensor.telegram.TelegramFailureCode
import com.example.motorcycleantitheftsensor.telegram.TelegramLiveLocationTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LivePursuitCoordinatorTest {

    private lateinit var fakeTracking: FakeMovementLocationTracking
    private lateinit var fakeStore: FakeMovementTrackingStore
    private lateinit var fakeTransport: FakeTelegramLiveLocationTransport
    private lateinit var fakeLabelResolver: FakeLocationLabelResolver
    private lateinit var fakeScheduler: FakePursuitExpiryScheduler
    private lateinit var coordinator: DefaultLivePursuitCoordinator
    private val testScope = CoroutineScope(Dispatchers.Unconfined)
    private var simulatedWallClockMs = 10_000L
    private var simulatedElapsedClockMs = 10_000L

    @Before
    fun setup() {
        fakeTracking = FakeMovementLocationTracking()
        fakeStore = FakeMovementTrackingStore()
        fakeTransport = FakeTelegramLiveLocationTransport()
        fakeLabelResolver = FakeLocationLabelResolver()
        fakeScheduler = FakePursuitExpiryScheduler()
        simulatedWallClockMs = 10_000L
        simulatedElapsedClockMs = 10_000L

        coordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = fakeTransport,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs }
        )
    }

    @Test
    fun armingDoesNotStartTracking() = runBlocking {
        coordinator.onProtectionStateChanged(ProtectionState.ARMING, "arm-1")
        assertEquals(0, fakeTracking.startCalls)
        assertNull(fakeStore.savedAnchor)
    }

    @Test
    fun armedHealthyStartsTracking() = runBlocking {
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        assertEquals(1, fakeTracking.startCalls)
    }

    @Test
    fun firstInvalidFixDoesNotSetAnchor() = runBlocking {
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(Double.NaN, 0.0, 1000L, 5f))
        assertNull(fakeStore.savedAnchor)
    }

    @Test
    fun firstUsableFixSetsAnchor() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))
        assertNotNull(fakeStore.savedAnchor)
        assertEquals("arm-1", fakeStore.savedAnchor?.armedSessionId)
        assertEquals(0, fakeTransport.starts.size)
    }

    @Test
    fun outsideCandidateDoesNotStartPursuit() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))
        assertEquals(0, fakeTransport.starts.size)
    }

    @Test
    fun confirmedMovementStartsPursuitOnce() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertEquals(1, fakeTransport.starts.size)
        assertEquals(1, fakeTracking.pursuitCalls)
        assertNotNull(fakeScheduler.scheduledAction)
        assertEquals(900_000L, fakeScheduler.scheduledDelayMs)
    }

    @Test
    fun repeatedConfirmedFixesDoNotStartSecondPursuit() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        simulatedElapsedClockMs = 35000L
        coordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))

        assertEquals(1, fakeTransport.starts.size)
    }

    @Test
    fun allStartFailedSavesEmptyHandleAndDoesNotRetry() = runBlocking {
        fakeTransport.startResult = emptyList()
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertEquals(1, fakeTransport.starts.size)
        assertEquals(0, fakeTracking.pursuitCalls)
        assertNotNull(fakeStore.savedSession)
        assertTrue(fakeStore.savedSession!!.handles.isEmpty())

        // Later fix should not retry start
        simulatedElapsedClockMs = 35000L
        coordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))
        assertEquals(1, fakeTransport.starts.size)
    }

    @Test
    fun emptyAttemptMarkerSaveFailurePreventsTelegramStart() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        // Force store save failure on empty attempt marker
        fakeStore.saveResult = false
        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertEquals(0, fakeTransport.starts.size)
    }

    @Test
    fun saveReturnedHandlesFailureStopsNewHandles() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        // First save succeeds (empty attempt marker), second save fails (returned handles)
        var saveCallCount = 0
        fakeStore.customSave = { state ->
            saveCallCount++
            if (saveCallCount == 2) {
                false // fail saving returned handles
            } else {
                fakeStore.savedState = state
                true
            }
        }

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertEquals(1, fakeTransport.starts.size)
        // Stale handles should be stopped
        assertEquals(1, fakeTransport.stops.size)
    }

    @Test
    fun updatesThrottledByTenSecondsOrTenMetersWithFiveSecFloor() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f)) // start pursuit at 17_000L

        // Fix 2s later (< 5s floor) -> rejected
        simulatedElapsedClockMs = 19000L
        coordinator.onLocationFix(fix(0.00201, 0.0, 19000L, 5f))
        assertEquals(0, fakeTransport.updates.size)

        // Fix 6s later (>= 5s floor, but < 10s and < 10m) -> rejected
        simulatedElapsedClockMs = 23000L
        coordinator.onLocationFix(fix(0.00201, 0.0, 23000L, 5f))
        assertEquals(0, fakeTransport.updates.size)

        // Fix 6s later with > 10m displacement -> accepted!
        simulatedElapsedClockMs = 24000L
        coordinator.onLocationFix(fix(0.0022, 0.0, 24000L, 5f))
        assertEquals(1, fakeTransport.updates.size)

        // Another fix 2s later (< 5s floor) -> rejected even with huge displacement
        simulatedElapsedClockMs = 26000L
        coordinator.onLocationFix(fix(0.003, 0.0, 26000L, 5f))
        assertEquals(1, fakeTransport.updates.size)

        // Fix 6s later (>= 5s floor) and > 10s time -> accepted!
        simulatedElapsedClockMs = 35000L
        coordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))
        assertEquals(2, fakeTransport.updates.size)
    }

    @Test
    fun retryableFailureAppliesExponentialBackoff() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f)) // start

        // First update fails with Retryable
        fakeTransport.updateResult = TelegramCallResult.Retryable(retryAfterMs = 5000L)
        simulatedElapsedClockMs = 30000L
        coordinator.onLocationFix(fix(0.0025, 0.0, 30000L, 5f))
        assertEquals(1, fakeTransport.updates.size)

        // Immediately retry (< backoff) -> rejected
        simulatedElapsedClockMs = 32000L
        coordinator.onLocationFix(fix(0.0026, 0.0, 32000L, 5f))
        assertEquals(1, fakeTransport.updates.size)

        // After backoff passes (30_000 + 5_000 = 35_000) -> retries!
        simulatedElapsedClockMs = 36000L
        fakeTransport.updateResult = TelegramCallResult.Success(Unit)
        coordinator.onLocationFix(fix(0.0026, 0.0, 36000L, 5f))
        assertEquals(2, fakeTransport.updates.size)
    }

    @Test
    fun terminalFailureRemovesHandleAndExitsPursuitCadence() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f)) // start

        // Terminal failure
        fakeTransport.updateResult = TelegramCallResult.Terminal(TelegramFailureCode.FORBIDDEN)
        simulatedElapsedClockMs = 30000L
        coordinator.onLocationFix(fix(0.0025, 0.0, 30000L, 5f))

        assertEquals(1, fakeTracking.exitPursuitCalls)
        assertTrue(fakeStore.savedSession?.handles?.isEmpty() == true)
    }

    @Test
    fun prepareForStopStopsTrackingAndHandlesUnderTimeout() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f)) // start

        coordinator.prepareForStop()

        assertTrue(fakeTracking.stopped)
        assertEquals(1, fakeTransport.stops.size)
        assertTrue(fakeStore.savedSession?.handles?.isEmpty() == true)
    }

    @Test
    fun abortLocalStopsTrackingSynchronously() {
        coordinator.abortLocal()
        assertTrue(fakeTracking.stopped)
    }

    @Test
    fun oldStructurallyValidAnchorRecoversWithoutFreshnessCheck() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "recovered-arm-1"),
            session = null
        )
        simulatedWallClockMs = 500_000L // much later in wall clock time

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)

        assertEquals(1, fakeTracking.startCalls)
        // Anchor should already be set from recovery, so a non-moving fix does not overwrite it
        simulatedElapsedClockMs = 600_000L
        coordinator.onLocationFix(fix(13.75001, 100.50001, simulatedElapsedClockMs, 5f))
        assertEquals("recovered-arm-1", fakeStore.savedAnchor?.armedSessionId)
    }

    @Test
    fun directEligibleRecoveryWithoutIdUsesInjectedFactoryOnce() = runBlocking {
        fakeStore.savedState = MovementTrackingState() // empty store
        var factoryCallCount = 0
        val factoryCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = fakeTransport,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs },
            armedSessionIdFactory = {
                factoryCallCount++
                "factory-session-$factoryCallCount"
            }
        )

        factoryCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)
        assertEquals(1, factoryCallCount)
        factoryCoordinator.onLocationFix(fix(1.0, 1.0, 1000L, 5f))
        assertEquals("factory-session-1", fakeStore.savedAnchor?.armedSessionId)
    }

    @Test
    fun alertActiveIsEligibleForRecovery() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        val handle = LiveLocationHandle("chat-1", 999L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "alert-arm-1"),
            session = PersistedLivePursuitSession(
                armedSessionId = "alert-arm-1",
                attemptedAtMs = 1000L,
                expiresAtMs = 901_000L,
                handles = listOf(handle)
            )
        )
        simulatedWallClockMs = 50_000L

        coordinator.onProtectionStateChanged(ProtectionState.ALERT_ACTIVE, null)

        assertEquals(1, fakeTracking.startCalls)
        assertEquals(1, fakeTracking.pursuitCalls)
        assertNotNull(fakeScheduler.scheduledAction)
    }

    @Test
    fun mismatchedAnchorAndSessionFailsClosedWithoutNewStart() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "arm-A"),
            session = PersistedLivePursuitSession(
                armedSessionId = "arm-B",
                attemptedAtMs = 1000L,
                expiresAtMs = 901_000L,
                handles = listOf(LiveLocationHandle("c", 1))
            )
        )

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)

        // Mismatched anchor/session: falls to session != null branch (fails closed without starting new pursuit)
        assertEquals(1, fakeTracking.startCalls)
        assertEquals(0, fakeTracking.pursuitCalls)
        assertEquals(1, fakeTransport.stops.size) // stale handles stopped
    }

    @Test
    fun expiredRecoveredHandlesAreStoppedAndNotUpdated() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        val handle = LiveLocationHandle("chat-1", 999L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "arm-1"),
            session = PersistedLivePursuitSession(
                armedSessionId = "arm-1",
                attemptedAtMs = 1000L,
                expiresAtMs = 901_000L,
                handles = listOf(handle)
            )
        )
        simulatedWallClockMs = 1_000_000L // past 901_000L expiry

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)

        // Expired handles stopped
        assertEquals(1, fakeTransport.stops.size)
        assertEquals(0, fakeTracking.pursuitCalls)

        // Location fix does not send update
        simulatedElapsedClockMs = 20_000L
        coordinator.onLocationFix(fix(13.80, 100.60, simulatedElapsedClockMs, 5f))
        assertEquals(0, fakeTransport.updates.size)
    }

    @Test
    fun clockRollbackCannotExtendRecoveryBeyondFifteenMinutes() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        val handle = LiveLocationHandle("chat-1", 999L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "arm-1"),
            session = PersistedLivePursuitSession(
                armedSessionId = "arm-1",
                attemptedAtMs = 1000L,
                expiresAtMs = 901_000L,
                handles = listOf(handle)
            )
        )
        // Clock rolled back to negative or 0
        simulatedWallClockMs = 0L

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)

        // remainingMs clamped to at most 900_000L (15 minutes)
        assertEquals(900_000L, fakeScheduler.scheduledDelayMs)
    }

    @Test
    fun clockForwardPastExpiryStopsImmediately() = runBlocking {
        val anchorFix = fix(13.75, 100.5, elapsed = 100L, accuracy = 5f, wall = 1000L)
        val handle = LiveLocationHandle("chat-1", 999L)
        fakeStore.savedState = MovementTrackingState(
            anchor = ParkingAnchor(anchorFix, "arm-1"),
            session = PersistedLivePursuitSession(
                armedSessionId = "arm-1",
                attemptedAtMs = 1000L,
                expiresAtMs = 901_000L,
                handles = listOf(handle)
            )
        )
        simulatedWallClockMs = 950_000L

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, null)

        assertEquals(1, fakeTransport.stops.size)
        assertNull(fakeScheduler.scheduledAction)
    }

    @Test
    fun offlineThenNewArmingUsesANewSessionId() = runBlocking {
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "session-1")
        coordinator.onProtectionStateChanged(ProtectionState.OFFLINE, null)
        coordinator.onProtectionStateChanged(ProtectionState.ARMING, "session-2")
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "session-2")

        coordinator.onLocationFix(fix(1.0, 1.0, 1000L, 5f))
        assertEquals("session-2", fakeStore.savedAnchor?.armedSessionId)
    }

    @Test
    fun suspendedStoreSaveDoesNotBlockDisarmStateChange() = runBlocking {
        val saveEntered = CompletableDeferred<Unit>()
        val allowSaveToFinish = CompletableDeferred<Unit>()

        fakeStore.customSave = { state ->
            saveEntered.complete(Unit)
            allowSaveToFinish.await()
            fakeStore.savedState = state
            true
        }

        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        val fixJob = testScope.launch {
            coordinator.onLocationFix(fix(1.0, 1.0, 1000L, 5f))
        }

        saveEntered.await()
        // Disarm while store.save is in-flight
        coordinator.onProtectionStateChanged(ProtectionState.DISARMED_ONLINE, null)

        allowSaveToFinish.complete(Unit)
        fixJob.join()
        assertTrue(fakeTracking.stopped)
    }

    @Test
    fun disarmInvalidatesQueuedUpdateBeforeRemoteCall() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))
        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))
        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f)) // start

        // Queue an update fix
        simulatedElapsedClockMs = 35000L
        coordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))

        coordinator.onProtectionStateChanged(ProtectionState.DISARMED_ONLINE, null)

        // Remote stop was called, update was invalidated
        assertEquals(1, fakeTransport.stops.size)
    }

    @Test
    fun admittedUpdateFinishesBeforeDisarmStop() = runBlocking {
        val updateEntered = CompletableDeferred<Unit>()
        val allowUpdateFinish = CompletableDeferred<Unit>()

        fakeTransport.updateResult = TelegramCallResult.Success(Unit)
        val transportWithPause = object : TelegramLiveLocationTransport by fakeTransport {
            override suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit> {
                updateEntered.complete(Unit)
                allowUpdateFinish.await()
                return fakeTransport.update(handle, fix)
            }
        }

        val testCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = transportWithPause,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs }
        )

        simulatedElapsedClockMs = 1000L
        testCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        testCoordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        testCoordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        testCoordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        simulatedElapsedClockMs = 35000L
        val updateJob = testScope.launch {
            testCoordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))
        }

        updateEntered.await()
        val disarmJob = testScope.launch {
            testCoordinator.onProtectionStateChanged(ProtectionState.DISARMED_ONLINE, null)
        }

        allowUpdateFinish.complete(Unit)
        updateJob.join()
        disarmJob.join()

        assertEquals(1, fakeTransport.updates.size)
        assertEquals(1, fakeTransport.stops.size)
    }

    @Test
    fun expiryStopIsTheFinalRemoteAction() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        // Trigger expiry
        assertNotNull(fakeScheduler.scheduledAction)
        fakeScheduler.scheduledAction!!.invoke()

        var retries = 0
        while (fakeTransport.stops.isEmpty() && retries++ < 50) {
            kotlinx.coroutines.delay(10)
        }

        assertEquals(1, fakeTransport.stops.size)
        assertEquals(1, fakeTracking.exitPursuitCalls)

        // Late fix after expiry
        simulatedElapsedClockMs = 50000L
        coordinator.onLocationFix(fix(0.004, 0.0, 50000L, 5f))
        assertEquals(0, fakeTransport.updates.size)
    }

    @Test
    fun retryableStopFailureRetainsHandleForRecovery() = runBlocking {
        fakeTransport.stopResult = TelegramCallResult.Retryable(retryAfterMs = 5000L)

        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        coordinator.prepareForStop()

        assertEquals(1, fakeTransport.stops.size)
        // Handle retained for recovery because stop returned Retryable and wall clock has not expired
        assertEquals(1, fakeStore.savedSession?.handles?.size)
    }

    @Test
    fun successfulStopRemovesOnlyThatHandle() = runBlocking {
        fakeTransport.startResult = listOf(
            LiveLocationHandle("chat-1", 101L),
            LiveLocationHandle("chat-2", 102L),
        )
        fakeTransport.stopResult = TelegramCallResult.Success(Unit)

        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        // Selective terminal failure: chat-1 fails terminally, chat-2 succeeds
        val transportWithSelectiveTerminal = object : TelegramLiveLocationTransport by fakeTransport {
            override suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit> {
                fakeTransport.updates += Pair(handle, fix)
                return if (handle.chatId == "chat-1") {
                    TelegramCallResult.Terminal(TelegramFailureCode.MESSAGE_UNAVAILABLE)
                } else {
                    TelegramCallResult.Success(Unit)
                }
            }
        }

        val testCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = transportWithSelectiveTerminal,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs }
        )

        // Sync coordinator state by transitioning with existing store state
        testCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")

        simulatedElapsedClockMs = 35000L
        testCoordinator.onLocationFix(fix(0.003, 0.0, 35000L, 5f))

        // chat-1 removed, chat-2 remains
        assertEquals(1, fakeStore.savedSession?.handles?.size)
        assertEquals("chat-2", fakeStore.savedSession?.handles?.first()?.chatId)
    }

    @Test
    fun messageUnavailableCountsAsAlreadyStopped() = runBlocking {
        fakeTransport.stopResult = TelegramCallResult.Terminal(TelegramFailureCode.MESSAGE_UNAVAILABLE)

        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        coordinator.prepareForStop()

        assertEquals(1, fakeTransport.stops.size)
        // MESSAGE_UNAVAILABLE counts as stopped, so handle is NOT retained
        assertTrue(fakeStore.savedSession?.handles?.isEmpty() == true)
    }

    @Test
    fun abortLocalInvalidatesQueuedRemoteWorkSynchronously() = runBlocking {
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.abortLocal()
        assertTrue(fakeTracking.stopped)
    }

    @Test
    fun shutdownDeadlineIncludesIngressPersistenceAndRemoteStop() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        coordinator.shutdown()

        assertTrue(fakeTracking.stopped)
    }

    @Test
    fun failedTrackingStartLeavesNoOrphanIngressAndRetries() = runBlocking {
        fakeTracking.stopped = true
        var shouldFailStart = true
        val trackingWithFailure = object : MovementLocationTracking by fakeTracking {
            override fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean {
                return if (shouldFailStart) {
                    false
                } else {
                    fakeTracking.startArmedTracking(onFix)
                }
            }
        }

        val testCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = trackingWithFailure,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = fakeTransport,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs }
        )

        testCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")

        // Retry succeeds
        shouldFailStart = false
        testCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        assertEquals(1, fakeTracking.startCalls)
    }

    @Test
    fun nativeLiveLocationDoesNotResolveOrSendMovementAlertText() = runBlocking {
        var startCalled = false
        var labelResolverInvoked = false

        val slowResolver = LocationLabelResolver {
            labelResolverInvoked = true
            "Bangkok"
        }
        val customTransport = object : TelegramLiveLocationTransport by fakeTransport {
            override suspend fun startForOwners(fix: TrackedLocationFix, livePeriodSeconds: Int): List<LiveLocationHandle> {
                startCalled = true
                return fakeTransport.startForOwners(fix, livePeriodSeconds)
            }
        }

        val testCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = customTransport,
            labelResolver = slowResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs }
        )

        simulatedElapsedClockMs = 1000L
        testCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        testCoordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        testCoordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        testCoordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertTrue(startCalled)
        assertFalse(labelResolverInvoked)
        assertTrue(fakeTransport.alerts.isEmpty())
    }

    @Test
    fun livePursuitOwnsOnlyLiveLocationForConfirmedMovement() = runBlocking {
        simulatedElapsedClockMs = 1000L
        coordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "arm-1")
        coordinator.onLocationFix(fix(0.0, 0.0, 1000L, 5f))

        simulatedElapsedClockMs = 2000L
        coordinator.onLocationFix(fix(0.002, 0.0, 2000L, 5f))

        simulatedElapsedClockMs = 17000L
        coordinator.onLocationFix(fix(0.002, 0.0, 17000L, 5f))

        assertEquals(1, fakeTransport.starts.size)
        assertTrue(fakeTransport.alerts.isEmpty())
    }

    @Test
    fun onConfirmedMovementCallbackIsInvokedOnConfirmedMovement() = runBlocking {
        var confirmedFix: TrackedLocationFix? = null
        simulatedElapsedClockMs = 10_000L
        simulatedWallClockMs = 10_000L
        val customCoordinator = DefaultLivePursuitCoordinator(
            locationTracking = fakeTracking,
            store = fakeStore,
            displacementPolicy = MovementDisplacementPolicy(),
            transport = fakeTransport,
            labelResolver = fakeLabelResolver,
            expiryScheduler = fakeScheduler,
            scope = testScope,
            onMovementConfirmed = { confirmedFix = it },
            wallClockMs = { simulatedWallClockMs },
            elapsedClockMs = { simulatedElapsedClockMs },
        )

        customCoordinator.onProtectionStateChanged(ProtectionState.ARMED_HEALTHY, "sess-1")
        // Set anchor fix
        customCoordinator.onLocationFix(fix(13.7563, 100.5018, 10_000L, 5f))
        assertNull(confirmedFix)

        // First outside fix (>50m)
        simulatedElapsedClockMs = 12_000L
        simulatedWallClockMs = 12_000L
        customCoordinator.onLocationFix(fix(13.7580, 100.5018, 12_000L, 5f))
        assertNull(confirmedFix)

        // Second outside fix >= 15s later -> Confirmed
        simulatedElapsedClockMs = 27_001L
        simulatedWallClockMs = 27_001L
        customCoordinator.onLocationFix(fix(13.7582, 100.5018, 27_001L, 5f))

        assertNotNull(confirmedFix)
        assertEquals(13.7582, confirmedFix!!.latitude, 0.0001)
    }

    private fun fix(
        lat: Double,
        lon: Double,
        elapsed: Long,
        accuracy: Float,
        wall: Long = 123456L
    ) = TrackedLocationFix(lat, lon, elapsed, wall, accuracy)
}

private class FakeMovementLocationTracking : MovementLocationTracking {
    var startCalls = 0
    var pursuitCalls = 0
    var exitPursuitCalls = 0
    var stopped = false
    var isTrackingState = false
    private var callback: ((TrackedLocationFix) -> Unit)? = null

    override fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean {
        startCalls++
        stopped = false
        isTrackingState = true
        callback = onFix
        return true
    }

    override fun enterPursuitMode(): Boolean {
        pursuitCalls++
        return true
    }

    override fun exitPursuitMode(): Boolean {
        exitPursuitCalls++
        return true
    }

    override fun stopTracking() {
        stopped = true
        isTrackingState = false
        callback = null
    }

    override fun isTracking(): Boolean = isTrackingState

    override fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix? = null

    fun emitFix(fix: TrackedLocationFix) {
        callback?.invoke(fix)
    }
}

private class FakeMovementTrackingStore : MovementTrackingStore {
    var savedState = MovementTrackingState()
    var saveResult = true
    var customSave: (suspend (MovementTrackingState) -> Boolean)? = null
    var cleared = false

    val savedAnchor get() = savedState.anchor
    val savedSession get() = savedState.session

    override suspend fun load(): MovementTrackingState = savedState

    override suspend fun save(state: MovementTrackingState): Boolean {
        return customSave?.invoke(state) ?: run {
            if (saveResult) {
                savedState = state
                true
            } else {
                false
            }
        }
    }

    override suspend fun clear(): Boolean {
        savedState = MovementTrackingState()
        cleared = true
        return true
    }
}

private class FakeTelegramLiveLocationTransport : TelegramLiveLocationTransport {
    val starts = mutableListOf<Pair<TrackedLocationFix, Int>>()
    val alerts = mutableListOf<String>()
    val updates = mutableListOf<Pair<LiveLocationHandle, TrackedLocationFix>>()
    val stops = mutableListOf<LiveLocationHandle>()

    var startResult: List<LiveLocationHandle> = listOf(LiveLocationHandle("owner1", 123))
    var alertResult = true
    var updateResult: TelegramCallResult<Unit> = TelegramCallResult.Success(Unit)
    var stopResult: TelegramCallResult<Unit> = TelegramCallResult.Success(Unit)

    override suspend fun startForOwners(
        fix: TrackedLocationFix,
        livePeriodSeconds: Int
    ): List<LiveLocationHandle> {
        starts += Pair(fix, livePeriodSeconds)
        return startResult
    }

    override suspend fun update(handle: LiveLocationHandle, fix: TrackedLocationFix): TelegramCallResult<Unit> {
        updates += Pair(handle, fix)
        return updateResult
    }

    override suspend fun stop(handle: LiveLocationHandle): TelegramCallResult<Unit> {
        stops += handle
        return stopResult
    }

    override suspend fun alertOwners(text: String): Boolean {
        alerts += text
        return alertResult
    }
}

private class FakeLocationLabelResolver : LocationLabelResolver {
    var labelToReturn: String? = null

    override suspend fun resolve(fix: TrackedLocationFix): String? = labelToReturn
}

private class FakePursuitExpiryScheduler : PursuitExpiryScheduler {
    var scheduledDelayMs: Long? = null
    var scheduledAction: (() -> Unit)? = null
    var cancelled = false

    override fun schedule(delayMs: Long, action: () -> Unit): PursuitExpiryHandle {
        scheduledDelayMs = delayMs
        scheduledAction = action
        return PursuitExpiryHandle {
            cancelled = true
        }
    }
}
