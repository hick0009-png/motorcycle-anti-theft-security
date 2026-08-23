package com.example.motorcycleantitheftsensor.protection

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the Entry Guard armed-session detection contract (spec sections
 * 6-7): strict evaluation order, threshold/hysteresis windows, one episode per
 * physical opening, evidence interruption, deduplicated health episodes, and the
 * frozen baseline.
 */
class EntryDetectionPolicyTest {

    private fun rotZ(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(cos(half), 0.0, 0.0, sin(half))
    }

    private fun rotX(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(cos(half), sin(half), 0.0, 0.0)
    }

    private fun sample(tMs: Long, q: EntryQuaternion, fresh: Boolean = true) =
        EntryOrientationSample(timestampMs = tMs, quaternion = q, fresh = fresh)

    private val model = EntryHingeModel(
        axisX = 0.0,
        axisY = 0.0,
        axisZ = 1.0,
        allowedDirection = 1,
        residualToleranceDeg = 5.0,
        algorithmVersion = 1,
        sensorIdentity = "rotation-vector",
        mountSignature = "mount-a",
        orientationSourcePolicy = "default",
    )

    private val settings = EntryProfileSettings()

    private fun policy() = EntryDetectionPolicy(
        baseline = EntryQuaternion.IDENTITY,
        model = model,
        settings = settings,
    )

    /** Drives an open past confirmation and returns the final state. */
    private fun driveOpen(policy: EntryDetectionPolicy, startMs: Long = 1_000L): Pair<EntryDetectionPolicy.State, EntryDetectionVerdict?> {
        var state = policy.initialState()
        var verdict: EntryDetectionVerdict? = null
        var t = startMs
        while (t <= startMs + 800L) {
            val (v, s) = policy.evaluate(state, sample(t, rotZ(18.0)))
            verdict = v ?: verdict
            state = s
            t += 200
        }
        return state to verdict
    }

    @Test
    fun staleSourceBlocksDoorOpenEvenWithLargeTwist() {
        val p = policy()
        val (verdict, state) = p.evaluate(
            p.initialState(),
            sample(1_000L, rotZ(45.0), fresh = false),
        )
        assertTrue(verdict is EntryDetectionVerdict.SourceUnavailable)
        assertNull(state.doorEpisode)
    }

    @Test
    fun offAxisSwingReportsMountMovedNotDoorOpen() {
        val p = policy()
        // 20-degree hinge twist plus a 30-degree off-axis swing: twist alone would pass
        // the 15-degree threshold, but the residual gate must fire first.
        val combined = EntryOrientationMath.multiply(rotZ(20.0), rotX(30.0))
        val (verdict, state) = p.evaluate(p.initialState(), sample(1_000L, combined))
        assertTrue(verdict is EntryDetectionVerdict.MountMoved)
        assertNull(state.doorEpisode)
    }

    @Test
    fun oppositeDirectionMotionReportsMountMoved() {
        val p = policy()
        val (verdict, state) = p.evaluate(p.initialState(), sample(1_000L, rotZ(-20.0)))
        assertTrue(verdict is EntryDetectionVerdict.MountMoved)
        assertNull(state.doorEpisode)
    }

    @Test
    fun openRequiresAngleForConfirmationWindow() {
        val p = policy()
        var state = p.initialState()
        var opened: EntryDetectionVerdict.DoorOpened? = null
        // 16 degrees held for only 400 ms: below the 750 ms confirmation window.
        var t = 1_000L
        while (t < 1_400L) {
            val (v, s) = p.evaluate(state, sample(t, rotZ(16.0)))
            if (v is EntryDetectionVerdict.DoorOpened) opened = v
            state = s
            t += 200
        }
        assertNull(opened)
        // Drop back below close threshold resets the streak; still no episode.
        val (v2, s2) = p.evaluate(state, sample(1_600L, EntryQuaternion.IDENTITY))
        assertNull(v2 as? EntryDetectionVerdict.DoorOpened)
        assertNull(s2.doorEpisode)
        // Held long enough this time: opens.
        var t2 = 2_000L
        while (t2 <= 2_800L) {
            val (v, s) = p.evaluate(state, sample(t2, rotZ(16.0)))
            if (v is EntryDetectionVerdict.DoorOpened) opened = v
            state = s
            t2 += 200
        }
        assertNotNull(opened)
        assertEquals(16.0, opened!!.angleDeg, 0.5)
    }

    @Test
    fun closeRequiresBelowThreeDegreesStableFiveSeconds() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        assertNotNull(openState.doorEpisode)
        var state = openState
        var closedAt: Long? = null
        var t = 3_000L
        while (t < 7_500L) {
            val (v, s) = p.evaluate(state, sample(t, EntryQuaternion.IDENTITY))
            if (v is EntryDetectionVerdict.DoorClosedConfirmed) closedAt = t
            state = s
            t += 500
        }
        // 4.5 seconds of below-3-degree stillness is not enough; 5+ seconds closes.
        assertNull(closedAt)
        val (v, _) = p.evaluate(state, sample(8_000L, EntryQuaternion.IDENTITY))
        assertTrue(v is EntryDetectionVerdict.DoorClosedConfirmed)
    }

    @Test
    fun onePhysicalOpeningCreatesOneEpisodeAndUpdatesIt() {
        val p = policy()
        var state = p.initialState()
        var openedId: String? = null
        var t = 1_000L
        val angles = listOf(18.0, 22.0, 25.0, 25.0)
        for (angle in angles) {
            val (v, s) = p.evaluate(state, sample(t, rotZ(angle)))
            when (v) {
                is EntryDetectionVerdict.DoorOpened -> {
                    assertEquals(null, openedId)
                    openedId = v.episodeId
                }
                is EntryDetectionVerdict.DoorStillOpen -> assertEquals(openedId, v.episodeId)
                else -> Unit
            }
            state = s
            t += 300
        }
        assertNotNull(openedId)
        assertEquals(25.0, state.doorEpisode!!.peakAngleDeg, 0.5)
    }

    @Test
    fun invalidEvidenceMidOpenMarksInterruptedNeverSynthesizesClose() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        val episodeBefore = openState.doorEpisode!!
        assertFalse(episodeBefore.interrupted)
        val (v, interruptedState) = p.evaluate(
            openState,
            sample(5_000L, rotZ(18.0), fresh = false),
        )
        assertTrue(v is EntryDetectionVerdict.SourceUnavailable)
        assertTrue(interruptedState.doorEpisode!!.interrupted)
        assertEquals(episodeBefore.episodeId, interruptedState.doorEpisode!!.episodeId)
    }

    @Test
    fun validSameModelEvidenceClosesInterruptedEpisodeAfterFiveSeconds() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        val (_, afterStale) = p.evaluate(openState, sample(5_000L, rotZ(18.0), fresh = false))
        // Fresh valid below-3-degree evidence accumulates toward recovery + close.
        var state = afterStale
        var closed: EntryDetectionVerdict.DoorClosedConfirmed? = null
        var interruptedEpisodeId: String? = afterStale.doorEpisode?.episodeId
        var t = 6_000L
        while (t <= 11_000L) {
            val (v, s) = p.evaluate(state, sample(t, EntryQuaternion.IDENTITY))
            if (v is EntryDetectionVerdict.DoorClosedConfirmed) closed = v
            state = s
            t += 500
        }
        assertNotNull(closed)
        assertEquals(interruptedEpisodeId, closed!!.episodeId)
        assertNull(state.doorEpisode)
    }

    @Test
    fun mountMovedOutranksDoorOpen() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        // Off-axis movement while a door episode is open: mount-moved wins and interrupts.
        val combined = EntryOrientationMath.multiply(rotZ(10.0), rotX(30.0))
        val (v, movedState) = p.evaluate(openState, sample(5_000L, combined))
        assertTrue(v is EntryDetectionVerdict.MountMoved)
        assertTrue(movedState.doorEpisode!!.interrupted)
        assertTrue(movedState.mountMoved)
    }

    @Test
    fun healthEpisodesDeduplicateUntilFiveSecondFreshEvidence() {
        val p = policy()
        var state = p.initialState()
        var unavailableCount = 0
        // Repeated stale samples: one semantic transition only.
        for (i in 0 until 4) {
            val (v, s) = p.evaluate(state, sample(1_000L + i * 200L, rotZ(30.0), fresh = false))
            if (v is EntryDetectionVerdict.SourceUnavailable) unavailableCount++
            state = s
        }
        assertEquals(1, unavailableCount)
        // Fresh but only ~3 s: still degraded, no recovery verdict yet.
        var recovered = false
        var t = 2_000L
        while (t < 4_800L) {
            val (v, s) = p.evaluate(state, sample(t, EntryQuaternion.IDENTITY))
            if (v is EntryDetectionVerdict.SourceRecovered) recovered = true
            state = s
            t += 400
        }
        assertFalse(recovered)
        // Crossing five continuous seconds recovers exactly once.
        var recoverCount = 0
        while (t < 8_000L) {
            val (v, s) = p.evaluate(state, sample(t, EntryQuaternion.IDENTITY))
            if (v is EntryDetectionVerdict.SourceRecovered) recoverCount++
            state = s
            t += 400
        }
        assertEquals(1, recoverCount)
        assertNull(state.sourceUnavailableSinceMs)
    }

    @Test
    fun magneticOnlyInterferenceCannotProduceDoorOpen() {
        val p = policy()
        // Orientation unchanged: no door event can exist regardless of any external
        // magnetic-field disturbance, because field strength is not an input here.
        var state = p.initialState()
        var doorEvents = 0
        for (i in 0 until 10) {
            val (v, s) = p.evaluate(state, sample(1_000L + i * 200L, EntryQuaternion.IDENTITY))
            if (v is EntryDetectionVerdict.DoorOpened || v is EntryDetectionVerdict.DoorStillOpen) doorEvents++
            state = s
        }
        assertEquals(0, doorEvents)
        assertNull(state.doorEpisode)
    }

    @Test
    fun noAutoRebaselineWhileArmed() {
        val p = policy()
        // Slow sustained hinge-axis drift: 0.5 degrees every 60 s crosses 15 degrees
        // after 30 minutes. A rebaselining detector would stay silent forever.
        var state = p.initialState()
        var opened: EntryDetectionVerdict.DoorOpened? = null
        var t = 0L
        var angle = 0.0
        while (angle < 16.0) {
            angle += 0.5
            t += 60_000L
            val (v, s) = p.evaluate(state, sample(t, rotZ(angle)))
            if (v is EntryDetectionVerdict.DoorOpened) opened = v
            state = s
        }
        assertNotNull(opened)
    }

    @Test
    fun secondOpeningAfterCloseStartsNewEpisodeFromSameBaseline() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        val firstId = openState.doorEpisode!!.episodeId
        var state = openState
        var t = 3_000L
        while (t < 9_000L) {
            val (v, s) = p.evaluate(state, sample(t, EntryQuaternion.IDENTITY))
            state = s
            if (v is EntryDetectionVerdict.DoorClosedConfirmed) break
            t += 500
        }
        // Re-open: new episode id, measured from the same frozen baseline.
        var secondId: String? = null
        t = 10_000L
        while (t <= 10_800L) {
            val (v, s) = p.evaluate(state, sample(t, rotZ(18.0)))
            if (v is EntryDetectionVerdict.DoorOpened) secondId = v.episodeId
            state = s
            t += 200
        }
        assertNotNull(secondId)
        assertFalse(firstId == secondId)
    }
}
