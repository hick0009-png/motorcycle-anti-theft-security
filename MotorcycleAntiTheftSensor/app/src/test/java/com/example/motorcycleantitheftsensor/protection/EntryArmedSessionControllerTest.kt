package com.example.motorcycleantitheftsensor.protection

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
