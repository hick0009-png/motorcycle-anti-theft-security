package com.example.motorcycleantitheftsensor.protection

import kotlin.math.acos
import kotlin.math.abs

/** Timestamped orientation reading with a source freshness/quality flag. */
data class EntryOrientationSample(
    val timestampMs: Long,
    val quaternion: EntryQuaternion,
    val fresh: Boolean,
)

/**
 * Learned hinge model produced by two consistent commissioning cycles. The axis is
 * oriented along opening rotation so `allowedDirection = +1` means "opening rotates
 * positively around the stored axis".
 */
data class EntryHingeModel(
    val axisX: Double,
    val axisY: Double,
    val axisZ: Double,
    val allowedDirection: Int,
    val residualToleranceDeg: Double,
    val algorithmVersion: Int,
    val sensorIdentity: String,
    val mountSignature: String,
    val orientationSourcePolicy: String,
    /**
     * When this model was accepted, stamped by [ProtectionProfilePolicy.commissionEntry]
     * rather than by the pure state machine that computes the geometry.
     *
     * Null for a model commissioned before it was recorded — the status report says so
     * instead of inventing a date. Deliberately outside [fingerprint]: when the hinge was
     * measured says nothing about whether the measurement still applies.
     */
    val commissionedAtWallMs: Long? = null,
    /**
     * How the phone was tilted when the model was measured, in its own frame.
     *
     * The hinge axis is expressed in the device frame, so it describes this door only while
     * the phone sits the way it sat during commissioning. Move the phone to another cradle,
     * another angle, another door, and the axis silently describes nothing — which used to be
     * discovered only later, by a door verdict that was wrong.
     *
     * Null for every model commissioned before this was recorded. Those keep exactly the
     * behaviour they have always had rather than being invalidated for a measurement that was
     * never taken; recalibrating is what gives a model its pose. Deliberately outside
     * [EntryCommissioningPolicy.fingerprint], because this is checked against a live reading
     * at the moment the watch starts, not against a string at Arm.
     */
    val mountUp: EntryVector3? = null,
)

/**
 * Pure state machine for Entry Guard commissioning (spec section 5):
 * a five-second still check, then two guided open/close cycles that must agree on
 * hinge axis, opening direction, and closed-position return before a model is issued.
 */
class EntryCommissioningPolicy(
    private val stillRequiredMs: Long,
    private val stillToleranceDeg: Double,
    private val minPeakAngleDeg: Double,
    closeThresholdDeg: Double = 3.0,
    private val axisAgreementToleranceDeg: Double = 10.0,
    private val sensorIdentity: String,
    private val mountSignature: String,
    private val orientationSourcePolicy: String,
    private val residualMarginDeg: Double = 2.0,
) {
    private val closeThresholdDeg: Double =
        closeThresholdDeg.coerceAtMost((minPeakAngleDeg - 1.0).coerceAtLeast(1.0))

    enum class Phase { IDLE, STILL_CHECK, AWAITING_CYCLE_ONE, AWAITING_CYCLE_TWO, COMMISSIONED }

    data class CycleRecord(
        val peakAngleDeg: Double,
        val axisX: Double,
        val axisY: Double,
        val axisZ: Double,
        val swingResidualDeg: Double,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val stillBaseline: EntryQuaternion? = null,
        val stillWindowStartMs: Long? = null,
        val cycleBaseline: EntryQuaternion? = null,
        val peakAngleDeg: Double = 0.0,
        val peakRel: EntryQuaternion? = null,
        val cycleOne: CycleRecord? = null,
        val cycleTwo: CycleRecord? = null,
        val model: EntryHingeModel? = null,
        val rejectionReason: String? = null,
    )

    fun start(): State = State(phase = Phase.STILL_CHECK)

    fun onSample(state: State, sample: EntryOrientationSample): State = when (state.phase) {
        Phase.STILL_CHECK -> handleStillSample(state, sample)
        Phase.AWAITING_CYCLE_ONE, Phase.AWAITING_CYCLE_TWO -> handleCycleSample(state, sample)
        else -> state
    }

    private fun handleStillSample(state: State, sample: EntryOrientationSample): State {
        val restart = state.copy(
            phase = Phase.STILL_CHECK,
            stillBaseline = sample.quaternion,
            stillWindowStartMs = sample.timestampMs,
        )
        if (!sample.fresh) return restart
        val baseline = state.stillBaseline
        if (baseline == null || state.stillWindowStartMs == null) return restart
        val total = EntryOrientationMath.totalRotationDeg(
            EntryOrientationMath.relativeRotation(baseline, sample.quaternion),
        )
        if (total > stillToleranceDeg) return restart
        val elapsed = sample.timestampMs - state.stillWindowStartMs
        return if (elapsed >= stillRequiredMs) {
            State(
                phase = Phase.AWAITING_CYCLE_ONE,
                cycleBaseline = EntryOrientationMath.canonicalizeSign(baseline),
            )
        } else {
            state
        }
    }

    private fun handleCycleSample(state: State, sample: EntryOrientationSample): State {
        if (!sample.fresh) return state
        val baseline = state.cycleBaseline ?: sample.quaternion
        val rel = EntryOrientationMath.relativeRotation(baseline, sample.quaternion)
        val total = EntryOrientationMath.totalRotationDeg(rel)
        val peakAngle = state.peakAngleDeg
        val peakRel = state.peakRel
        val updated = if (total > peakAngle) {
            state.copy(cycleBaseline = baseline, peakAngleDeg = total, peakRel = rel)
        } else {
            state.copy(cycleBaseline = baseline)
        }
        val currentPeak = updated.peakRel ?: return updated
        val qualifies = updated.peakAngleDeg >= minPeakAngleDeg
        val hasDepartedClosed = updated.peakAngleDeg > closeThresholdDeg
        val backBelowClose = total <= closeThresholdDeg && hasDepartedClosed
        if (!backBelowClose) return updated
        if (!qualifies) {
            // Peak never reached the selected angle: discard this attempt, keep waiting.
            return resetCycleTracking(updated).copy(
                rejectionReason = "peak-too-small-${updated.peakAngleDeg.toInt()}deg",
            )
        }
        val record = buildCycleRecord(currentPeak)
        return if (updated.phase == Phase.AWAITING_CYCLE_ONE) {
            State(
                phase = Phase.AWAITING_CYCLE_TWO,
                cycleBaseline = baseline,
                cycleOne = record,
                rejectionReason = null,
            )
        } else {
            validateSecondCycle(updated, record, sample)
        }
    }

    private fun validateSecondCycle(
        state: State,
        second: CycleRecord,
        sample: EntryOrientationSample,
    ): State {
        val first = state.cycleOne
            ?: return resetCycleTracking(state).copy(rejectionReason = "missing-first-cycle")
        val dot = (first.axisX * second.axisX + first.axisY * second.axisY + first.axisZ * second.axisZ)
            .coerceIn(-1.0, 1.0)
        val axisAngleDeg = Math.toDegrees(acos(abs(dot)))
        if (axisAngleDeg > axisAgreementToleranceDeg) {
            return resetCycleTracking(state)
                .copy(rejectionReason = "axis-mismatch-${axisAngleDeg.toInt()}deg")
        }
        val peakRel = state.peakRel ?: return resetCycleTracking(state)
        val signedTwist = EntryOrientationMath.twistAroundAxisDeg(
            peakRel,
            doubleArrayOf(first.axisX, first.axisY, first.axisZ),
        )
        if (signedTwist <= 0.0 || dot < 0.0) {
            return resetCycleTracking(state).copy(rejectionReason = "opposite-opening-direction")
        }
        val model = EntryHingeModel(
            axisX = first.axisX + second.axisX,
            axisY = first.axisY + second.axisY,
            axisZ = first.axisZ + second.axisZ,
            allowedDirection = 1,
            residualToleranceDeg = maxOf(first.swingResidualDeg, second.swingResidualDeg) + residualMarginDeg,
            algorithmVersion = ALGORITHM_VERSION,
            sensorIdentity = sensorIdentity,
            mountSignature = mountSignature,
            orientationSourcePolicy = orientationSourcePolicy,
            // The still-check baseline is the phone at rest against a shut door, which is the
            // only pose worth remembering: it is the one the axis was measured from.
            mountUp = state.cycleBaseline?.let(EntryOrientationMath::deviceUpVector),
        ).let { raw ->
            val length = kotlin.math.sqrt(raw.axisX * raw.axisX + raw.axisY * raw.axisY + raw.axisZ * raw.axisZ)
            raw.copy(axisX = raw.axisX / length, axisY = raw.axisY / length, axisZ = raw.axisZ / length)
        }
        return State(phase = Phase.COMMISSIONED, model = model, rejectionReason = null)
    }

    private fun resetCycleTracking(state: State): State = state.copy(
        peakAngleDeg = 0.0,
        peakRel = null,
    )

    fun tareBaseline(state: State, sample: EntryOrientationSample): State = state.copy(
        cycleBaseline = sample.quaternion,
        peakAngleDeg = 0.0,
        peakRel = null,
        rejectionReason = null,
    )

    /** Axis is oriented along opening rotation so the recorded opening direction is always +1. */
    private fun buildCycleRecord(peakRel: EntryQuaternion): CycleRecord {
        val q = EntryOrientationMath.normalize(peakRel)
        val length = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        var ax = if (length > 1e-12) q.x / length else 0.0
        var ay = if (length > 1e-12) q.y / length else 0.0
        var az = if (length > 1e-12) q.z / length else 1.0

        // Orient axis along the opening rotation so opening twist is strictly positive.
        // Rotation by -theta around A is mathematically identical to +theta around -A.
        val twist = EntryOrientationMath.twistAroundAxisDeg(peakRel, doubleArrayOf(ax, ay, az))
        if (twist < 0.0) {
            ax = -ax
            ay = -ay
            az = -az
        }
        return CycleRecord(
            peakAngleDeg = EntryOrientationMath.totalRotationDeg(peakRel),
            axisX = ax,
            axisY = ay,
            axisZ = az,
            swingResidualDeg = EntryOrientationMath.swingResidualDeg(
                peakRel,
                doubleArrayOf(ax, ay, az),
            ),
        )
    }

    data class CommissioningContext(
        val sensorIdentity: String,
        val mountSignature: String,
        val orientationSourcePolicy: String,
        val algorithmVersion: Int,
        val entryUseContinuous: Boolean,
        val alertAngleDeg: Int,
        val openConfirmationMs: Long,
    )

    companion object {
        const val ALGORITHM_VERSION = 1

        /**
         * Stable, inspectable fingerprint covering every invalidating field: axis,
         * allowed direction, residual tolerance, algorithm version, sensor identity,
         * mount signature, and orientation-source policy.
         */
        fun fingerprint(model: EntryHingeModel): String =
            "entry-hinge|v=${model.algorithmVersion}" +
                "|axis=${"%.6f".format(model.axisX)},${"%.6f".format(model.axisY)},${"%.6f".format(model.axisZ)}" +
                "|dir=${model.allowedDirection}" +
                "|tol=${"%.3f".format(model.residualToleranceDeg)}" +
                "|sensor=${model.sensorIdentity}" +
                "|mount=${model.mountSignature}" +
                "|src=${model.orientationSourcePolicy}"

        /**
         * Invalidation matrix (spec section 5): leaving Entry, remounting, sensor or
         * source-policy change, or an algorithm bump require recommissioning. Alert
         * angle, confirmation time, and notification preferences never do.
         */
        fun requiresRecommission(
            previous: CommissioningContext,
            current: CommissioningContext,
        ): Boolean =
            previous.sensorIdentity != current.sensorIdentity ||
                previous.mountSignature != current.mountSignature ||
                previous.orientationSourcePolicy != current.orientationSourcePolicy ||
                previous.algorithmVersion != current.algorithmVersion ||
                !current.entryUseContinuous
    }
}
