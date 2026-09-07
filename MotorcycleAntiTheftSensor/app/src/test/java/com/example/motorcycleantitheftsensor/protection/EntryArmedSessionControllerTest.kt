package com.example.motorcycleantitheftsensor.protection

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the armed Entry session's drift compensation (checkpoint
 * 2026-09-06 overnight false-alarm root cause): the orientation sensor drifts on its
 * own, the baseline is frozen at Arm, and over a long session that drift alone used to
 * climb past the open threshold and alarm a shut, untouched door. While the door reads
 * closed and still the baseline is re-captured to the current orientation, tracking the
 * drift out — without ever following a door that is actually opening.
 */
class EntryArmedSessionControllerTest {

    private val stable = EntryArmedSessionController.DRIFT_REBASELINE_STABLE_MS

    /** Rotation about the hinge axis (Z): reads as door-open twist. */
    private fun rotZ(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(cos(half), 0.0, 0.0, sin(half))
    }

    /** Rotation about X: off-axis swing, the mount-moved signal. */
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
        residualToleranceDeg = 16.0,
        algorithmVersion = 1,
        sensorIdentity = "rotation-vector",
        mountSignature = "mount-a",
        orientationSourcePolicy = "default",
    )

    // close band 3°, open threshold 15°, open confirmed after 750ms.
    private val settings = EntryProfileSettings()

    /** The same model, but measured with the phone lying flat. */
    private val posedModel = model.copy(
        mountUp = EntryOrientationMath.deviceUpVector(EntryQuaternion.IDENTITY),
    )

    private fun armed(): EntryArmedSessionController {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = model, settings = settings)
        controller.onSample(sample(0L, rotZ(0.0)), 1L) // capture baseline at the shut position
        return controller
    }

    @Test
    fun slowTwistDriftPastTheOpenThresholdNeverAlarmsAShutDoor() {
        val controller = armed()
        val verdicts = mutableListOf<EntryDetectionVerdict>()

        // Ten 120s windows, each drifting +2° about the hinge — 20° absolute by the end, well
        // past the 15° open threshold, but never more than 2° from the walking baseline.
        var absDeg = 0.0
        var t = 1_000L
        repeat(10) {
            absDeg += 2.0
            verdicts += controller.onSample(sample(t, rotZ(absDeg)), 1L)     // opens the window
            t += stable + 1_000L
            verdicts += controller.onSample(sample(t, rotZ(absDeg)), 1L)     // window elapsed: rebaseline
            t += 1_000L
        }

        assertTrue("absolute drift must exceed the open threshold", absDeg > 15.0)
        assertFalse(
            "a shut door that only drifted must never be reported open",
            verdicts.any { it is EntryDetectionVerdict.DoorOpened || it is EntryDetectionVerdict.DoorStillOpen },
        )
        // The live angle stays pinned near zero because the baseline walked with the drift.
        assertEquals(0.0, controller.liveAngleDeg() ?: -1.0, 0.5)
    }

    /**
     * A whole night at the worst drift this phone has ever measured of itself.
     *
     * The two synthetic proofs above step the angle in tidy jumps. This one walks it the way
     * the hardware does, at the rate the device actually recorded in its own mount on the real
     * door: `entry-drift-20260906-195659.csv` gives −11.9°/hr of hinge twist and −2.1°/hr of
     * off-axis swing. Eight hours of that is ninety-five degrees past a fifteen-degree alert
     * angle and sixteen degrees into a sixteen-degree residual tolerance — every gate the door
     * watch has, crossed twice over, by a door that nobody touched.
     *
     * The rebaseline only has to beat it by outrunning it: at that rate the reference walks
     * every two minutes and the angle has 0.4° to accumulate before it does.
     */
    @Test
    fun aWholeNightAtThisPhonesMeasuredDriftNeverAlarmsAShutDoor() {
        val controller = armed()
        val verdicts = mutableListOf<EntryDetectionVerdict>()

        val nightMs = 8L * 3_600_000L
        val stepMs = 10_000L
        var t = stepMs
        while (t <= nightMs) {
            val hours = t / 3_600_000.0
            val pose = EntryOrientationMath.multiply(rotZ(11.9 * hours), rotX(2.1 * hours))
            verdicts += controller.onSample(sample(t, pose), 1L)
            t += stepMs
        }

        assertTrue("the night must outrun every gate", 11.9 * 8 > 15.0 && 2.1 * 8 > 16.0 - 0.5)
        assertTrue(
            "a shut door that only drifted must stay silent all night",
            verdicts.isEmpty(),
        )
        // And the watch is still watching, pinned to the door rather than to eight hours ago.
        assertEquals(0.0, controller.liveAngleDeg() ?: -1.0, 0.5)
    }

    @Test
    fun slowSwingDriftNeverReportsAMountMove() {
        val controller = armed()
        val verdicts = mutableListOf<EntryDetectionVerdict>()

        // Off-axis drift toward the 16° residual tolerance, tracked out the same way.
        var absDeg = 0.0
        var t = 1_000L
        repeat(8) {
            absDeg += 3.0
            verdicts += controller.onSample(sample(t, rotX(absDeg)), 1L)
            t += stable + 1_000L
            verdicts += controller.onSample(sample(t, rotX(absDeg)), 1L)
            t += 1_000L
        }

        assertTrue("absolute swing must exceed the residual tolerance", absDeg > 16.0)
        assertFalse(verdicts.any { it is EntryDetectionVerdict.MountMoved })
    }

    @Test
    fun aRealOpeningStillAlarmsThroughTheDriftCompensation() {
        val controller = armed()
        val verdicts = mutableListOf<EntryDetectionVerdict>()

        // A genuine swing to 18° held past the 750ms confirmation must still fire.
        var t = 1_000L
        while (t <= 2_000L) {
            verdicts += controller.onSample(sample(t, rotZ(18.0)), 1L)
            t += 250L
        }

        assertTrue(verdicts.any { it is EntryDetectionVerdict.DoorOpened })
    }

    @Test
    fun rebaselineOnlyHappensAfterTheStabilityWindow() {
        val controller = armed()

        // Just under the window: no rebaseline yet, so the live angle still reflects the offset.
        controller.onSample(sample(1_000L, rotZ(2.5)), 1L)
        controller.onSample(sample(1_000L + stable - 1_000L, rotZ(2.5)), 1L)
        assertEquals(2.5, controller.liveAngleDeg() ?: -1.0, 0.3)

        // Past the window: the baseline snaps to the current 2.5°, so a later 5.0° reads ~2.5°.
        controller.onSample(sample(1_000L + stable + 1_000L, rotZ(2.5)), 1L)
        assertEquals(0.0, controller.liveAngleDeg() ?: -1.0, 0.3)
        controller.onSample(sample(1_000L + stable + 2_000L, rotZ(5.0)), 1L)
        assertEquals(2.5, controller.liveAngleDeg() ?: -1.0, 0.3)
    }

    /**
     * The blind spot the freshness gate could not cover by itself. It notices a gap when a
     * sample arrives late — so it catches a stuttering source and misses the one that stops
     * dead, where no sample ever arrives to be judged. A door watch with nothing to report
     * looks exactly like a door that never opened.
     */
    @Test
    fun aStreamThatStopsDeadIsReported() {
        val controller = armed()
        controller.onSample(sample(1_000L, rotZ(0.0)), 1L)

        assertTrue("inside the gap is not a complaint", controller.onSilence(6_000L).isEmpty())

        val verdicts = controller.onSilence(12_000L)
        assertTrue(verdicts.any { it is EntryDetectionVerdict.SourceUnavailable })

        // And it stays one fact, not one per tick.
        assertTrue(controller.onSilence(17_000L).none { it is EntryDetectionVerdict.SourceUnavailable })
    }

    @Test
    fun aStreamThatComesBackAfterASilenceRecoversNormally() {
        val controller = armed()
        controller.onSample(sample(1_000L, rotZ(0.0)), 1L)
        assertTrue(controller.onSilence(12_000L).any { it is EntryDetectionVerdict.SourceUnavailable })

        var recovered = false
        var t = 13_000L
        while (t <= 20_000L) {
            recovered = recovered || controller.onSample(sample(t, rotZ(0.0)), 1L)
                .any { it is EntryDetectionVerdict.SourceRecovered }
            t += 1_000L
        }
        assertTrue(recovered)
    }

    /**
     * A listener that failed to register, or a source the phone refused, leaves a session
     * armed with no stream at all. There is no baseline to judge and never was — and that is
     * the loudest form of the same fact, not a reason to say nothing.
     */
    @Test
    fun anArmedSessionThatNeverReceivesASampleSaysSoOnce() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = model, settings = settings)

        assertTrue("the first look only starts the clock", controller.onSilence(0L).isEmpty())
        assertTrue(controller.onSilence(11_000L).any { it is EntryDetectionVerdict.SourceUnavailable })
        assertTrue(controller.onSilence(22_000L).isEmpty())
    }

    /**
     * The hinge axis is a direction in the device's own frame, so it describes this door only
     * while the phone sits where it was measured. A fixed placeholder string was supposed to
     * be the mount signature that caught a remounting; it was equal to itself on every phone
     * in every position, so nothing was ever caught, and a phone moved to another cradle went
     * on being read as a door until a verdict came out wrong.
     */
    @Test
    fun aPhoneMountedSomewhereElseSaysSoInsteadOfMeasuringADoor() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = posedModel, settings = settings)

        // Armed with the phone on edge — a quarter turn from where the model was measured.
        val verdicts = controller.onSample(sample(0L, rotX(90.0)), 1L)
        assertTrue(verdicts.any { it is EntryDetectionVerdict.MountUnrecognized })
    }

    /**
     * And it must not talk itself out of it. The baseline is captured at the unrecognized
     * pose, so the residual against it reads perfect from the first sample — the displaced
     * watch's way back would otherwise declare the mount good five seconds later.
     */
    @Test
    fun anUnrecognizedMountIsNeverRestoredByStandingStill() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = posedModel, settings = settings)
        controller.onSample(sample(0L, rotX(90.0)), 1L)

        val later = mutableListOf<EntryDetectionVerdict>()
        var t = 1_000L
        while (t <= 30_000L) {
            later += controller.onSample(sample(t, rotX(90.0)), 1L)
            t += 1_000L
        }
        assertTrue(later.none { it is EntryDetectionVerdict.MountRestored })
    }

    /**
     * The bug the device found. The pose check runs on the first sample of an armed session,
     * which arrives while the coordinator is still in ARMING — a window the incident engine
     * drops everything in, correctly, because sensors are settling and the owner is stood over
     * the phone. So the one time this could speak was the one time nobody was listening, and a
     * phone taken off its door and left on a desk armed in silence, watching for a door with a
     * hinge axis that no longer described anything.
     */
    @Test
    fun anUnrecognizedMountSaysSoAgainBecauseTheFirstTimeIsUsuallyLost() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = posedModel, settings = settings)

        val first = controller.onSample(sample(0L, rotX(90.0)), 1L)
        assertTrue(first.any { it is EntryDetectionVerdict.MountUnrecognized })

        // Nothing more inside the floor, however many samples arrive.
        val quiet = mutableListOf<EntryDetectionVerdict>()
        var t = 1_000L
        while (t < 60_000L) {
            quiet += controller.onSample(sample(t, rotX(90.0)), 1L)
            t += 1_000L
        }
        assertTrue(quiet.none { it is EntryDetectionVerdict.MountUnrecognized })

        // And then it says it again, to whoever is listening by now.
        val repeat = controller.onSample(sample(61_000L, rotX(90.0)), 1L)
        assertTrue(repeat.any { it is EntryDetectionVerdict.MountUnrecognized })
    }

    @Test
    fun aModelCommissionedBeforePosesWereRecordedIsTakenAtItsWord() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = model, settings = settings)

        // Same quarter turn, no recorded pose to compare it against: nothing to say.
        val verdicts = controller.onSample(sample(0L, rotX(90.0)), 1L)
        assertTrue(verdicts.none { it is EntryDetectionVerdict.MountUnrecognized })
    }

    @Test
    fun aPhoneReseatedInItsOwnCradleIsNotCalledARemount() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = posedModel, settings = settings)

        // Twelve degrees off: how differently a phone sits when it is picked up and put back.
        val verdicts = controller.onSample(sample(0L, rotX(12.0)), 1L)
        assertTrue(verdicts.none { it is EntryDetectionVerdict.MountUnrecognized })
    }

    /**
     * Bug #5. The closed reference is captured from the first *fresh* sample, and "fresh" waits
     * for the rotation vector to converge — which on the test device landed several seconds in,
     * exactly while the owner still had a hand on the door during the arming countdown. Frozen
     * there, a half-open pose became "closed" for the whole session, past the reach of the drift
     * rebaseline. While arming the baseline is provisional and follows each fresh sample, so the
     * pose the door settles on as the countdown ends is the one that sticks.
     */
    @Test
    fun aDoorMovedDuringTheArmingWindowDoesNotFreezeItselfInAsClosed() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = model, settings = settings)

        // The first fresh sample lands while the door is swung 20° open, mid-countdown.
        controller.onSample(sample(0L, rotZ(20.0)), 1L, arming = true)
        // The owner lets it settle shut before the countdown ends.
        controller.onSample(sample(3_000L, rotZ(0.0)), 1L, arming = true)
        // Countdown over with the door shut: that pose is the reference from here on.
        controller.onSample(sample(10_000L, rotZ(0.0)), 1L, arming = false)

        // The shut door reads shut — not the 20° offset a first-sample capture would have frozen.
        assertEquals(0.0, controller.liveAngleDeg() ?: -1.0, 0.5)

        // And a genuine opening still alarms through the settled baseline.
        val verdicts = mutableListOf<EntryDetectionVerdict>()
        var t = 11_000L
        while (t <= 12_000L) {
            verdicts += controller.onSample(sample(t, rotZ(18.0)), 1L, arming = false)
            t += 250L
        }
        assertTrue(verdicts.any { it is EntryDetectionVerdict.DoorOpened })
    }

    /**
     * The arming window stays silent regardless of how the door is moved in it — the baseline is
     * still being settled, and the engine drops verdicts there anyway, so producing them would
     * be noise timed for the one moment nobody is meant to be alarmed.
     */
    @Test
    fun noDoorVerdictIsProducedWhileStillArming() {
        val controller = EntryArmedSessionController()
        controller.begin(generation = 1L, model = model, settings = settings)
        controller.onSample(sample(0L, rotZ(0.0)), 1L, arming = true)

        val during = mutableListOf<EntryDetectionVerdict>()
        var t = 250L
        while (t <= 8_000L) {
            during += controller.onSample(sample(t, rotZ(30.0)), 1L, arming = true)
            t += 250L
        }
        assertTrue("an arming window must not raise a door verdict", during.isEmpty())
    }

    /**
     * The field failure behind "the angle reads fine and nothing is ever announced". A door
     * verdict is asked to corroborate its angle with movement, and the only signal that could
     * answer was the accelerometer — which a door barely troubles, because a hinge rotates the
     * phone without accelerating it. The device measured 1.04 g through a 38 degree opening: no
     * shake, so every correct verdict was refused and the watch stayed silent all session.
     * Rotation over a short window is the corroboration a door can actually supply.
     */
    @Test
    fun aDoorSwingProvesItsOwnMovementWithoutAnyShake() {
        val controller = armed()

        assertNull("a still door has proved no movement", controller.lastDoorMotionElapsedMs())

        // A swing well past the two-degree motion floor, with no accelerometer input at all.
        controller.onSample(sample(1_000L, rotZ(18.0)), 1L)

        assertEquals(1_000L, controller.lastDoorMotionElapsedMs())
    }

    /**
     * And the thing corroboration exists to refuse still cannot satisfy it: drift at the worst
     * rate this phone has measured of itself walks a few degrees an hour, which is thousandths
     * of a degree inside the motion window, however many hours it is given.
     */
    @Test
    fun aWholeNightOfDriftNeverCountsAsDoorMovement() {
        val controller = armed()

        val nightMs = 8L * 3_600_000L
        val stepMs = 10_000L
        var t = stepMs
        while (t <= nightMs) {
            val hours = t / 3_600_000.0
            controller.onSample(sample(t, EntryOrientationMath.multiply(rotZ(11.9 * hours), rotX(2.1 * hours))), 1L)
            t += stepMs
        }

        assertNull(
            "drift must never be mistaken for a door that moved",
            controller.lastDoorMotionElapsedMs(),
        )
    }

    @Test
    fun anOpenDoorHeldPastTheWindowIsNeverRebaselinedShut() {
        val controller = armed()
        val verdicts = mutableListOf<EntryDetectionVerdict>()

        // Hold the door genuinely open (18°) far longer than the stability window. It must not
        // be treated as a new closed reference: the angle keeps reading open the whole time.
        var t = 1_000L
        repeat(4) {
            verdicts += controller.onSample(sample(t, rotZ(18.0)), 1L)
            t += stable + 1_000L
        }

        assertTrue(verdicts.any { it is EntryDetectionVerdict.DoorOpened })
        assertTrue("an open door must keep reading open", (controller.liveAngleDeg() ?: 0.0) > 15.0)
    }
}
