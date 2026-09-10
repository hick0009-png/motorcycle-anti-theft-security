package com.example.motorcycleantitheftsensor.protection

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host proofs for the Entry Guard two-cycle hinge commissioning contract
 * (spec section 5): five-second still check, two consistent guided cycles,
 * model fingerprint coverage, and the invalidation matrix.
 */
class EntryCommissioningPolicyTest {

    private fun rotZ(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(cos(half), 0.0, 0.0, sin(half))
    }

    private fun rotY(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(cos(half), 0.0, sin(half), 0.0)
    }

    private fun sample(tMs: Long, q: EntryQuaternion, fresh: Boolean = true) =
        EntryOrientationSample(timestampMs = tMs, quaternion = q, fresh = fresh)

    private fun policy(minPeak: Double = 15.0) = EntryCommissioningPolicy(
        stillRequiredMs = 5_000L,
        stillToleranceDeg = 2.0,
        minPeakAngleDeg = minPeak,
        closeThresholdDeg = 3.0,
        axisAgreementToleranceDeg = 10.0,
        sensorIdentity = "rotation-vector",
        orientationSourcePolicy = "default",
    )

    /** Feeds a still window then one full open/close cycle starting at [startMs]. */
    private fun feedStillAndCycle(
        policy: EntryCommissioningPolicy,
        state: EntryCommissioningPolicy.State,
        startMs: Long,
        peakDeg: Double = 20.0,
        openRot: (Double) -> EntryQuaternion = ::rotZ,
    ): EntryCommissioningPolicy.State {
        var s = state
        var t = startMs
        // Still window: five seconds of tiny-noise stillness at identity.
        while (t < startMs + 5_000L) {
            s = policy.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        s = policy.onSample(s, sample(t, EntryQuaternion.IDENTITY))
        t += 200
        // Open to peak.
        var angle = 0.0
        while (angle < peakDeg) {
            angle += 5.0
            s = policy.onSample(s, sample(t, openRot(angle)))
            t += 200
        }
        // Hold, then close back below the close threshold.
        s = policy.onSample(s, sample(t, openRot(peakDeg)))
        t += 200
        var closing = peakDeg
        while (closing > 0.0) {
            closing -= 5.0
            s = policy.onSample(s, sample(t, openRot(closing.coerceAtLeast(0.0))))
            t += 200
        }
        return s
    }

    @Test
    fun stillCheckDoesNotAdvanceBeforeFiveContinuousSeconds() {
        val p = policy()
        var s = p.start()
        var t = 0L
        while (t < 4_500L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.STILL_CHECK, s.phase)
        s = p.onSample(s, sample(5_000L, EntryQuaternion.IDENTITY))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
    }

    @Test
    fun stillCheckRestartsOnMovement() {
        val p = policy()
        var s = p.start()
        var t = 0L
        while (t < 3_000L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        // Movement of 10 degrees restarts the still window from this new pose.
        s = p.onSample(s, sample(3_200L, rotZ(10.0)))
        // Stillness at the new pose: window must complete only 5 s after the movement.
        while (t < 8_000L) {
            s = p.onSample(s, sample(t, rotZ(10.0)))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.STILL_CHECK, s.phase)
        s = p.onSample(s, sample(8_200L, rotZ(10.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
    }

    @Test
    fun staleSampleRestartsStillWindow() {
        val p = policy()
        var s = p.start()
        var t = 0L
        while (t < 2_000L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        s = p.onSample(s, sample(2_200L, EntryQuaternion.IDENTITY, fresh = false))
        // Only 2.5 s of fresh stillness after the stale sample: must not advance yet.
        while (t < 4_500L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.STILL_CHECK, s.phase)
        s = p.onSample(s, sample(7_200L, EntryQuaternion.IDENTITY))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
    }

    @Test
    fun twoConsistentCyclesCommissionWithAveragedAxis() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        s = feedStillAndCycle(p, s, startMs = 10_000L)
        assertEquals(EntryCommissioningPolicy.Phase.COMMISSIONED, s.phase)
        val model = s.model
        assertNotNull(model)
        // Both cycles rotated around +Z; the learned axis must point along Z.
        assertEquals(0.0, model!!.axisX, 1e-6)
        assertEquals(0.0, model.axisY, 1e-6)
        assertEquals(1.0, kotlin.math.abs(model.axisZ), 1e-6)
        assertEquals(1, model.allowedDirection)
        // The pose the axis was measured from, so a later session can tell whether the phone
        // is still mounted where that measurement applies.
        assertNotNull(model.mountUp)
        assertTrue(model.residualToleranceDeg > 0.0)
        assertEquals("rotation-vector", model.sensorIdentity)
        assertEquals("default", model.orientationSourcePolicy)
        assertEquals(EntryCommissioningPolicy.ALGORITHM_VERSION, model.algorithmVersion)
    }

    @Test
    fun cycleBelowSelectedAngleDoesNotComplete() {
        val p = policy(minPeak = 15.0)
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, peakDeg = 12.0)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
        assertNull(s.cycleOne)
    }

    @Test
    fun mismatchedAxisSecondCycleIsRejected() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, openRot = ::rotZ)
        s = feedStillAndCycle(p, s, startMs = 10_000L, openRot = ::rotY)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        assertNull(s.model)
        assertNotNull(s.rejectionReason)
    }

    @Test
    fun oppositeDirectionSecondCycleIsRejected() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, openRot = ::rotZ)
        s = feedStillAndCycle(p, s, startMs = 10_000L, openRot = { deg -> rotZ(-deg) })
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        assertNull(s.model)
        assertNotNull(s.rejectionReason)
    }

    @Test
    fun twoCyclesClockwiseNegativeTwistPassesCommissioning() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, peakDeg = 20.0, openRot = { deg -> rotZ(-deg) })
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        s = feedStillAndCycle(p, s, startMs = 10_000L, peakDeg = 20.0, openRot = { deg -> rotZ(-deg) })
        assertEquals(EntryCommissioningPolicy.Phase.COMMISSIONED, s.phase)
        val model = s.model
        assertNotNull(model)
        assertEquals(0.0, model!!.axisX, 1e-6)
        assertEquals(0.0, model.axisY, 1e-6)
        assertEquals(-1.0, model.axisZ, 1e-6)
        assertEquals(1, model.allowedDirection)
        assertTrue(model.residualToleranceDeg > 0.0)
        assertNull(s.rejectionReason)
    }

    @Test
    fun oppositeOpeningDirectionRejectsProperly() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, openRot = ::rotZ)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        s = feedStillAndCycle(p, s, startMs = 10_000L, openRot = { deg -> rotZ(-deg) })
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        assertNull(s.model)
        assertEquals("opposite-opening-direction", s.rejectionReason)
    }

    @Test
    fun fingerprintChangesWhenAnyCoveredFieldChanges() {
        val base = EntryHingeModel(
            axisX = 0.0,
            axisY = 0.0,
            axisZ = 1.0,
            allowedDirection = 1,
            residualToleranceDeg = 8.0,
            algorithmVersion = 1,
            sensorIdentity = "rotation-vector",
                orientationSourcePolicy = "default",
        )
        val baseFp = EntryCommissioningPolicy.fingerprint(base)
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(axisX = 0.1)))
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(allowedDirection = -1)))
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(residualToleranceDeg = 9.0)))
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(algorithmVersion = 2)))
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(sensorIdentity = "other")))
        assertNotEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy(orientationSourcePolicy = "fused")))
        assertEquals(baseFp, EntryCommissioningPolicy.fingerprint(base.copy()))
    }

    @Test
    fun invalidationMatrixMatchesSpec() {
        val base = EntryCommissioningPolicy.CommissioningContext(
            sensorIdentity = "rotation-vector",
                orientationSourcePolicy = "default",
            algorithmVersion = 1,
            entryUseContinuous = true,
            alertAngleDeg = 15,
            openConfirmationMs = 750L,
        )
        val p = EntryCommissioningPolicy.Companion
        // Invalidating changes:
        assertTrue(p.requiresRecommission(base, base.copy(sensorIdentity = "other")))
        assertTrue(p.requiresRecommission(base, base.copy(orientationSourcePolicy = "fused")))
        assertTrue(p.requiresRecommission(base, base.copy(algorithmVersion = 2)))
        assertTrue(p.requiresRecommission(base, base.copy(entryUseContinuous = false)))
        // Non-invalidating settings-only changes:
        org.junit.Assert.assertFalse(p.requiresRecommission(base, base.copy(alertAngleDeg = 30)))
        org.junit.Assert.assertFalse(p.requiresRecommission(base, base.copy(openConfirmationMs = 1_000L)))
        org.junit.Assert.assertFalse(p.requiresRecommission(base, base.copy()))
    }

    @Test
    fun configurableCloseThresholdAccommodatesRestingGap() {
        // Tight 3.0 deg threshold fails when door only returns to 3.5 deg
        val tightPolicy = EntryCommissioningPolicy(
            stillRequiredMs = 5_000L,
            stillToleranceDeg = 2.0,
            minPeakAngleDeg = 15.0,
            closeThresholdDeg = 3.0,
            axisAgreementToleranceDeg = 10.0,
            sensorIdentity = "rotation-vector",
                orientationSourcePolicy = "default",
        )
        var sTight = tightPolicy.start()
        sTight = feedStillAndCycle(tightPolicy, sTight, startMs = 0L, peakDeg = 20.0) { deg ->
            rotZ(if (deg < 3.5) 3.5 else deg)
        }
        // Never reached below 3.0 deg -> still in cycle one
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, sTight.phase)

        // Compensated 4.0 deg threshold succeeds when door returns to 3.5 deg
        val compensatedPolicy = EntryCommissioningPolicy(
            stillRequiredMs = 5_000L,
            stillToleranceDeg = 2.0,
            minPeakAngleDeg = 15.0,
            closeThresholdDeg = 4.0,
            axisAgreementToleranceDeg = 10.0,
            sensorIdentity = "rotation-vector",
                orientationSourcePolicy = "default",
        )
        var sComp = compensatedPolicy.start()
        sComp = feedStillAndCycle(compensatedPolicy, sComp, startMs = 0L, peakDeg = 20.0) { deg ->
            rotZ(if (deg < 3.5) 3.5 else deg)
        }
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, sComp.phase)
    }

    @Test
    fun resetCycleTrackingPreservesCycleBaselineForCleanRetry() {
        val p = policy()
        var s = p.start()
        s = feedStillAndCycle(p, s, startMs = 0L, openRot = ::rotZ)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        val cycleOneBaseline = s.cycleBaseline
        assertNotNull(cycleOneBaseline)

        // Force axis mismatch in cycle 2
        s = feedStillAndCycle(p, s, startMs = 10_000L, openRot = ::rotY)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        // cycleBaseline must be preserved rather than cleared
        assertEquals(cycleOneBaseline, s.cycleBaseline)
        assertEquals(0.0, s.peakAngleDeg, 1e-6)
        assertNull(s.peakRel)
        assertNotNull(s.rejectionReason)
    }

    @Test
    fun openingSlightlyBelowCloseThresholdIsNotDiscardedImmediately() {
        val p = policy(minPeak = 15.0)
        var s = p.start()
        var t = 0L
        while (t <= 5_000L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
        val initialBaseline = s.cycleBaseline
        assertNotNull(initialBaseline)
        assertNull(s.rejectionReason)

        // Door begins opening slightly: 0.0° -> 0.8° (below closeThreshold of 3.0°)
        t += 200
        s = p.onSample(s, sample(t, rotZ(0.8)))

        // Must not be discarded as closed/peak-too-small
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
        assertNull(s.rejectionReason)
        assertEquals(initialBaseline, s.cycleBaseline)
        assertEquals(0.8, s.peakAngleDeg, 1e-3)
    }

    @Test
    fun doorOpeningPastMinPeakAndClosingAdvancesToCycleTwo() {
        val p = policy(minPeak = 15.0)
        var s = p.start()
        var t = 0L
        while (t <= 5_000L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        // Start opening with slight movement 0.8°, then progress past minPeak (20.0°)
        t += 200
        s = p.onSample(s, sample(t, rotZ(0.8)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        t += 200
        s = p.onSample(s, sample(t, rotZ(5.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        t += 200
        s = p.onSample(s, sample(t, rotZ(20.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)
        assertEquals(20.0, s.peakAngleDeg, 1e-3)

        // Door closes back below closeThreshold (e.g., 2.0° <= 3.0°)
        t += 200
        s = p.onSample(s, sample(t, rotZ(2.0)))

        // Must successfully qualify and advance to AWAITING_CYCLE_TWO
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        assertNotNull(s.cycleOne)
        assertEquals(20.0, s.cycleOne!!.peakAngleDeg, 1e-3)
        assertNull(s.rejectionReason)
    }

    @Test
    fun invertedCloseThresholdIsConstrainedBelowMinPeak() {
        // closeThresholdDeg (20.0) configured higher than minPeakAngleDeg (15.0)
        val invertedPolicy = EntryCommissioningPolicy(
            stillRequiredMs = 5_000L,
            stillToleranceDeg = 2.0,
            minPeakAngleDeg = 15.0,
            closeThresholdDeg = 20.0,
            axisAgreementToleranceDeg = 10.0,
            sensorIdentity = "rotation-vector",
                orientationSourcePolicy = "default",
        )
        var s = invertedPolicy.start()
        var t = 0L
        while (t <= 5_000L) {
            s = invertedPolicy.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        // Open to 14.0° - since closeThreshold is clamped to 14.0, door hasn't departed yet
        t += 200
        s = invertedPolicy.onSample(s, sample(t, rotZ(14.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        // Open to 18.0° (> minPeak 15.0)
        t += 200
        s = invertedPolicy.onSample(s, sample(t, rotZ(18.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        // Close to 5.0° (<= effectiveClose of 14.0)
        t += 200
        s = invertedPolicy.onSample(s, sample(t, rotZ(5.0)))
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s.phase)
        assertNotNull(s.cycleOne)
    }

    @Test
    fun tareBaselineReSeedsZeroReference() {
        val p = policy()
        var s = p.start()
        var t = 0L
        while (t <= 5_000L) {
            s = p.onSample(s, sample(t, EntryQuaternion.IDENTITY))
            t += 500
        }
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_ONE, s.phase)

        // Tare zero at 5 degrees
        val offsetSample = sample(6_000L, rotZ(5.0))
        s = p.tareBaseline(s, offsetSample)
        assertEquals(rotZ(5.0), s.cycleBaseline)
        assertEquals(0.0, s.peakAngleDeg, 1e-6)
        assertNull(s.rejectionReason)
    }

    @Test
    fun cycleTwoPeakTooSmallRetainsCycleOne() {
        val p = policy(minPeak = 15.0)
        val s1 = feedStillAndCycle(p, p.start(), startMs = 0L, peakDeg = 20.0, openRot = ::rotZ)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s1.phase)
        val cycleOneRecord = s1.cycleOne
        assertNotNull(cycleOneRecord)

        // Second cycle opens only to 10.0° (< minPeak 15.0) then closes
        val s2 = feedStillAndCycle(p, s1, startMs = 10_000L, peakDeg = 10.0, openRot = ::rotZ)
        assertEquals(EntryCommissioningPolicy.Phase.AWAITING_CYCLE_TWO, s2.phase)
        assertEquals(cycleOneRecord, s2.cycleOne)
        assertTrue(s2.rejectionReason?.startsWith("peak-too-small") == true)

        // Retry second cycle with sufficient angle (20.0°)
        val s3 = feedStillAndCycle(p, s2, startMs = 20_000L, peakDeg = 20.0, openRot = ::rotZ)
        assertEquals(EntryCommissioningPolicy.Phase.COMMISSIONED, s3.phase)
        assertNotNull(s3.model)
        assertNull(s3.rejectionReason)
    }
}

