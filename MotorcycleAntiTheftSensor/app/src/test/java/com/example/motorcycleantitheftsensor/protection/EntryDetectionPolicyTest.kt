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

    private val SOURCE_LOSS_REANNOUNCE = EntryDetectionPolicy.SOURCE_LOSS_REANNOUNCE_MS

    /**
     * Drives seven seconds of healthy shut-door samples through a source-loss episode.
     * @return whether a recovery was announced, and the resulting state.
     */
    private fun driveRecovery(
        policy: EntryDetectionPolicy,
        from: EntryDetectionPolicy.State,
        startMs: Long,
    ): Pair<Boolean, EntryDetectionPolicy.State> {
        var state = from
        var recovered = false
        var t = startMs
        while (t <= startMs + 7_000L) {
            val (verdict, next) = policy.evaluate(state, sample(t, rotZ(0.0)))
            if (verdict is EntryDetectionVerdict.SourceRecovered) recovered = true
            state = next
            t += 1_000L
        }
        return recovered to state
    }

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

    /**
     * A phone that has been displaced stays displaced. Re-announcing that on every sample
     * turned one incident into fifteen full alerts in thirty seconds on the test device —
     * each a Telegram message and an SMS — and the guard meant to stop it sat one statement
     * below the gate that fires it, where only a sample that no longer tripped the gate could
     * reach it.
     */
    @Test
    fun aDisplacedMountIsAnnouncedOnceAndThenStaysQuiet() {
        val p = policy()
        val combined = EntryOrientationMath.multiply(rotZ(20.0), rotX(30.0))

        val (first, afterFirst) = p.evaluate(p.initialState(), sample(1_000L, combined))
        assertTrue(first is EntryDetectionVerdict.MountMoved)
        assertTrue(afterFirst.mountMoved)

        // Still displaced, still tripping the residual gate, and now with nothing to add.
        var state = afterFirst
        repeat(10) { tick ->
            val (verdict, next) = p.evaluate(state, sample(2_000L + tick * 200L, combined))
            assertNull(verdict)
            assertTrue(next.mountMoved)
            state = next
        }

        // And one sample back inside tolerance is not enough to resume the watch: the way
        // back is sustained compatible evidence, proved below.
        val (afterSettling, settled) = p.evaluate(state, sample(9_000L, rotZ(0.0)))
        assertNull(afterSettling)
        assertTrue(settled.mountMoved)
    }

    /**
     * The displacement used to be the end of the armed session: every later sample returned
     * nothing, so a phone knocked at dusk left the door unwatched until morning. One shove
     * bought a burglar the whole night, and the moment right after somebody touches the
     * alarm is the worst possible moment for it to go blind.
     */
    @Test
    fun aDisplacedMountThatComesBackResumesTheWatch() {
        val p = policy()
        val combined = EntryOrientationMath.multiply(rotZ(20.0), rotX(30.0))
        val (moved, displaced) = p.evaluate(p.initialState(), sample(1_000L, combined))
        assertTrue(moved is EntryDetectionVerdict.MountMoved)

        // Back inside tolerance, on-axis and shut. Compatible for the required five seconds.
        var state = displaced
        var restored: EntryDetectionVerdict? = null
        var t = 10_000L
        while (t <= 15_000L) {
            val (verdict, next) = p.evaluate(state, sample(t, rotZ(0.0)))
            restored = verdict ?: restored
            state = next
            t += 1_000L
        }
        assertTrue(restored is EntryDetectionVerdict.MountRestored)
        assertFalse(state.mountMoved)

        // And the watch really is watching again, not merely un-flagged.
        var opened: EntryDetectionVerdict? = null
        var openT = 16_000L
        while (openT <= 17_000L) {
            val (verdict, next) = p.evaluate(state, sample(openT, rotZ(18.0)))
            opened = verdict ?: opened
            state = next
            openT += 250L
        }
        assertTrue(opened is EntryDetectionVerdict.DoorOpened)
    }

    /**
     * The other half of not being terminal: a phone that stays displaced is still worth
     * watching, because the commissioned axis is what was lost — not the ability to tell
     * that somebody is handling the phone right now.
     */
    @Test
    fun aDisplacedPhoneMovedAgainIsAnnouncedAgain() {
        val p = policy()
        val displacedPose = EntryOrientationMath.multiply(rotZ(20.0), rotX(30.0))
        val (moved, afterMove) = p.evaluate(p.initialState(), sample(1_000L, displacedPose))
        assertTrue(moved is EntryDetectionVerdict.MountMoved)

        // Left alone where it landed: it settles, and settling is never news.
        var state = afterMove
        var t = 2_000L
        while (t <= 9_000L) {
            val (verdict, next) = p.evaluate(state, sample(t, displacedPose))
            assertNull(verdict)
            state = next
            t += 1_000L
        }

        // Then somebody turns it another fifteen degrees off the pose it settled at.
        val movedAgain = EntryOrientationMath.multiply(rotZ(20.0), rotX(45.0))
        val (second, _) = p.evaluate(state, sample(10_000L, movedAgain))
        assertTrue(second is EntryDetectionVerdict.MountMoved)
    }

    @Test
    fun aRestoredMountResolvesTheEpisodeTheDisplacementInterrupted() {
        val p = policy()
        val (openState, _) = driveOpen(p)
        val combined = EntryOrientationMath.multiply(rotZ(10.0), rotX(30.0))
        val (movedVerdict, movedState) = p.evaluate(openState, sample(5_000L, combined))
        assertTrue(movedVerdict is EntryDetectionVerdict.MountMoved)
        assertTrue(movedState.doorEpisode!!.interrupted)

        var state = movedState
        var resolved: EntryDetectionVerdict? = null
        var t = 10_000L
        while (t <= 15_000L) {
            val (verdict, next) = p.evaluate(state, sample(t, rotZ(0.0)))
            resolved = verdict ?: resolved
            state = next
            t += 1_000L
        }
        assertTrue(resolved is EntryDetectionVerdict.DoorClosedConfirmed)
        assertFalse(state.mountMoved)
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

    /**
     * Bug #6. A door edged just past the alert angle must hold the full 750ms, but a swing flung
     * wide is confirmed on a short floor instead — otherwise a door thrown open and shut again
     * inside 750ms left no alert at all. The floor is never zero: a lone wide sample is a spike,
     * not a swing, and must not alarm on its own.
     */
    @Test
    fun aWideSwingConfirmsFasterThanTheFullDwell() {
        val p = policy()
        var state = p.initialState()

        // A lone wide sample is not yet a swing.
        val (v0, s0) = p.evaluate(state, sample(1_000L, rotZ(50.0)))
        assertNull(v0 as? EntryDetectionVerdict.DoorOpened)
        state = s0

        // 200ms later — far short of the 750ms dwell a narrow opening would need — it confirms.
        val (v1, _) = p.evaluate(state, sample(1_200L, rotZ(50.0)))
        assertTrue(v1 is EntryDetectionVerdict.DoorOpened)
        assertEquals(50.0, (v1 as EntryDetectionVerdict.DoorOpened).angleDeg, 0.5)
    }

    @Test
    fun aLoneWideSpikeThatVanishesNextSampleNeverAlarms() {
        val p = policy()
        var state = p.initialState()

        val (v0, s0) = p.evaluate(state, sample(1_000L, rotZ(50.0)))
        assertNull(v0 as? EntryDetectionVerdict.DoorOpened)
        state = s0

        // Back shut before the floor elapses: the streak resets, no episode is ever opened.
        val (v1, s1) = p.evaluate(state, sample(1_020L, EntryQuaternion.IDENTITY))
        assertNull(v1 as? EntryDetectionVerdict.DoorOpened)
        assertNull(s1.doorEpisode)
    }

    /**
     * The field failure. The device commissioned at `tol=2.000`: two tidy guided cycles measured
     * almost no off-axis residual, so the tolerance collapsed to the bare margin. A real door is
     * not swung the way a calibration is performed, and every ordinary opening cleared two
     * degrees — tripping the residual gate, which runs *before* the angle gate, so the watch
     * answered "the mount moved" and could never once say "door opened", on a phone that was
     * reading the door perfectly. Recalibrating could not fix it; the formula gave the same two
     * degrees back. The enforced tolerance therefore has a floor.
     */
    @Test
    fun aModelCommissionedTooTightStillReadsAnOrdinaryDoorOpening() {
        val tight = model.copy(residualToleranceDeg = 2.0)
        val p = EntryDetectionPolicy(EntryQuaternion.IDENTITY, tight, settings)
        var state = p.initialState()
        // A 20 degree swing carrying 6 degrees of off-axis slop: an ordinary opening on a real
        // hinge, well past the commissioned 2 degrees and well inside the enforced floor.
        val opening = EntryOrientationMath.multiply(rotZ(20.0), rotX(6.0))

        var opened: EntryDetectionVerdict.DoorOpened? = null
        var calledItAMountMove = false
        var t = 1_000L
        while (t <= 2_000L) {
            val (v, s) = p.evaluate(state, sample(t, opening))
            if (v is EntryDetectionVerdict.MountMoved) calledItAMountMove = true
            if (v is EntryDetectionVerdict.DoorOpened) opened = v
            state = s
            t += 250L
        }

        assertFalse("an ordinary opening must not read as a displaced mount", calledItAMountMove)
        assertNotNull("the door opening must be reported", opened)
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

    /**
     * The overnight repeat-alert shape. A phone that freezes background apps loses the sensor
     * for twenty seconds every few minutes, and every one of those cycles used to be a whole
     * incident with its own Telegram message and SMS, all saying the sentence the owner read
     * the first time.
     */
    @Test
    fun aSourceThatKeepsDroppingOutIsAnnouncedOnceNotOnEveryGap() {
        val p = policy()
        var state = p.initialState()
        val announcements = mutableListOf<Long>()

        // Five loss/recovery cycles two minutes apart — all inside the floor — and a sixth
        // just past it, which is a fresh piece of news and says so.
        val lossTimes = listOf(1_000L, 121_000L, 241_000L, 361_000L, 481_000L, 611_000L)
        for (lossAt in lossTimes) {
            val (lost, afterLoss) = p.evaluate(state, sample(lossAt, rotZ(0.0), fresh = false))
            if (lost is EntryDetectionVerdict.SourceUnavailable) announcements += lossAt
            state = driveRecovery(p, afterLoss, lossAt + 20_000L).second
        }

        assertEquals(listOf(1_000L, 611_000L), announcements)
        assertTrue(lossTimes.last() - lossTimes.first() >= SOURCE_LOSS_REANNOUNCE)
    }

    @Test
    fun aSuppressedLossDoesNotAnnounceItsOwnRecovery() {
        val p = policy()
        var state = p.initialState()

        val (first, afterFirst) = p.evaluate(state, sample(1_000L, rotZ(0.0), fresh = false))
        assertTrue(first is EntryDetectionVerdict.SourceUnavailable)
        state = afterFirst
        state = driveRecovery(p, state, 20_000L).also { assertTrue(it.first) }.second

        // Second gap inside the floor: silent going down, and silent coming back up.
        val (second, afterSecond) = p.evaluate(state, sample(120_000L, rotZ(0.0), fresh = false))
        assertNull(second)
        state = afterSecond
        val (recoveredAgain, _) = driveRecovery(p, state, 140_000L)
        assertFalse(recoveredAgain)
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
