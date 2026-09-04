package com.example.motorcycleantitheftsensor.location

import com.example.motorcycleantitheftsensor.sensor.LocationRegistration
import com.example.motorcycleantitheftsensor.sensor.LocationRegistrationResult
import com.example.motorcycleantitheftsensor.sensor.LocationStartFailure
import com.example.motorcycleantitheftsensor.sensor.LocationTrackingRequest
import com.example.motorcycleantitheftsensor.sensor.LocationUpdatesClient
import com.example.motorcycleantitheftsensor.sensor.MovementLocationTracking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandLocationFinderTest {

    private var elapsedMs = 100_000L

    @Test
    fun aFixTheTrackerAlreadyHasIsUsedWithoutWakingTheRadio() = runTest {
        val client = FakeClient()
        val finder = OnDemandLocationFinder(
            tracking = FakeTracking(usable = fix(latitude = 13.75)),
            client = client,
            elapsedMs = { elapsedMs },
        )

        val answer = finder.find()

        assertTrue(answer is LocationAnswer.Fresh)
        assertEquals(13.75, (answer as LocationAnswer.Fresh).fix.latitude, 0.0001)
        // The important half: no registration at all when the answer was already there.
        assertEquals(0, client.registrations)
    }

    @Test
    fun theArmedTrackerIsNeverAskedToHandOverItsCallback() = runTest {
        val tracking = FakeTracking(usable = null)
        val finder = OnDemandLocationFinder(tracking, FakeClient(emits = fix()), { elapsedMs })

        finder.find()

        // `startArmedTracking` assigns the shared callback, so calling it here would take the
        // pursuit's fixes away mid-theft and nothing would report that it had.
        assertEquals(0, tracking.startArmedTrackingCalls)
    }

    @Test
    fun theListenerIsReleasedOnceAnAnswerArrives() = runTest {
        val client = FakeClient(emits = fix())
        OnDemandLocationFinder(FakeTracking(usable = null), client, { elapsedMs }).find()

        assertEquals(1, client.registrations)
        assertEquals(1, client.cancellations)
    }

    @Test
    fun theListenerIsReleasedWhenNothingEverArrives() = runTest {
        val client = FakeClient(emits = null, remembered = emptyList())
        val answer = OnDemandLocationFinder(
            FakeTracking(usable = null),
            client,
            { elapsedMs },
            timeoutMs = 50L,
        ).find()

        assertTrue(answer is LocationAnswer.Unavailable)
        assertEquals(1, client.cancellations)
    }

    @Test
    fun whenNothingNewArrivesTheRememberedFixIsHandedOverWithItsAge() = runTest {
        val old = fix(latitude = 13.5).copy(elapsedRealtimeMs = elapsedMs - 600_000L)
        val answer = OnDemandLocationFinder(
            FakeTracking(usable = null),
            FakeClient(emits = null, remembered = listOf(old)),
            { elapsedMs },
            timeoutMs = 50L,
        ).find()

        val known = answer as LocationAnswer.LastKnown
        assertEquals(13.5, known.fix.latitude, 0.0001)
        assertEquals(600_000L, known.ageMs)
    }

    @Test
    fun aRefusedRegistrationIsReportedWithItsReasonRatherThanAsSilence() = runTest {
        val answer = OnDemandLocationFinder(
            FakeTracking(usable = null),
            FakeClient(failure = LocationStartFailure.PERMISSION_DENIED, remembered = emptyList()),
            { elapsedMs },
            timeoutMs = 50L,
        ).find()

        assertEquals(LocationStartFailure.PERMISSION_DENIED, (answer as LocationAnswer.Unavailable).reason)
    }

    @Test
    fun theNewestRememberedFixWinsOverOlderOnes() = runTest {
        val older = fix(latitude = 1.0).copy(elapsedRealtimeMs = elapsedMs - 900_000L)
        val newer = fix(latitude = 2.0).copy(elapsedRealtimeMs = elapsedMs - 60_000L)
        val answer = OnDemandLocationFinder(
            FakeTracking(usable = null),
            FakeClient(emits = null, remembered = listOf(older, newer)),
            { elapsedMs },
            timeoutMs = 50L,
        ).find()

        assertEquals(2.0, (answer as LocationAnswer.LastKnown).fix.latitude, 0.0001)
    }

    private fun fix(latitude: Double = 13.7, longitude: Double = 100.5) = TrackedLocationFix(
        latitude = latitude,
        longitude = longitude,
        elapsedRealtimeMs = elapsedMs,
        wallClockMs = 1_756_800_000_000L,
        accuracyMeters = 12f,
    )

    private class FakeTracking(private val usable: TrackedLocationFix?) : MovementLocationTracking {
        var startArmedTrackingCalls = 0

        override fun startArmedTracking(onFix: (TrackedLocationFix) -> Unit): Boolean {
            startArmedTrackingCalls += 1
            return true
        }

        override fun enterPursuitMode(): Boolean = true
        override fun exitPursuitMode(): Boolean = true
        override fun stopTracking() = Unit
        override fun isTracking(): Boolean = usable != null
        override fun currentUsableFix(nowElapsedMs: Long): TrackedLocationFix? = usable
    }

    private class FakeClient(
        private val emits: TrackedLocationFix? = null,
        private val remembered: List<TrackedLocationFix> = emptyList(),
        private val failure: LocationStartFailure? = null,
    ) : LocationUpdatesClient {
        var registrations = 0
        var cancellations = 0

        override fun register(
            request: LocationTrackingRequest,
            onFix: (TrackedLocationFix) -> Unit,
        ): LocationRegistrationResult {
            failure?.let { reason -> return LocationRegistrationResult.Unavailable(reason) }
            registrations += 1
            emits?.let(onFix)
            return LocationRegistrationResult.Started(LocationRegistration { cancellations += 1 })
        }

        override fun lastKnownFixes(): List<TrackedLocationFix> = remembered
    }
}
