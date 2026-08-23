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
 * sign-canonicalized (largest-absolute component positive) so `allowedDirection = +1`
 * means "opening rotates positively around the stored axis".
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
    private val closeThresholdDeg: Double,
    private val axisAgreementToleranceDeg: Double,
    private val sensorIdentity: String,
    private val mountSignature: String,
    private val orientationSourcePolicy: String,
    private val residualMarginDeg: Double = 2.0,
) {

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
        val backBelowClose = total <= closeThresholdDeg && peakAngle > total || total <= closeThresholdDeg
        if (!(backBelowClose && currentPeak != null)) return updated
        if (!qualifies) {
            // Peak never reached the selected angle: discard this attempt, keep waiting.
            return resetCycleTracking(updated)
        }
        val record = buildCycleRecord(currentPeak)
        return if (updated.phase == Phase.AWAITING_CYCLE_ONE) {
            State(
                phase = Phase.AWAITING_CYCLE_TWO,
                cycleBaseline = sample.quaternion,
                cycleOne = record,
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
        val axisAngleDeg = Math.toDegrees(
            acos(
                (first.axisX * second.axisX + first.axisY * second.axisY + first.axisZ * second.axisZ)
                    .coerceIn(-1.0, 1.0),
            ),
        )
        if (axisAngleDeg > axisAgreementToleranceDeg) {
            return resetCycleTracking(state)
                .copy(rejectionReason = "axis-mismatch-${axisAngleDeg.toInt()}deg")
        }
        val peakRel = state.peakRel ?: return resetCycleTracking(state)
        val signedTwist = EntryOrientationMath.twistAroundAxisDeg(
            peakRel,
            doubleArrayOf(first.axisX, first.axisY, first.axisZ),
        )
        if (signedTwist <= 0.0) {
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
        ).let { raw ->
            val length = kotlin.math.sqrt(raw.axisX * raw.axisX + raw.axisY * raw.axisY + raw.axisZ * raw.axisZ)
            raw.copy(axisX = raw.axisX / length, axisY = raw.axisY / length, axisZ = raw.axisZ / length)
        }
        return State(phase = Phase.COMMISSIONED, model = model)
    }

    private fun resetCycleTracking(state: State): State = state.copy(
        peakAngleDeg = 0.0,
        peakRel = null,
    )

    /** Axis is sign-canonicalized so the recorded opening direction is always +1. */
    private fun buildCycleRecord(peakRel: EntryQuaternion): CycleRecord {
        val q = EntryOrientationMath.normalize(peakRel)
        val length = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        var ax = q.x / length
        var ay = q.y / length
        var az = q.z / length
        val largest = maxOf(abs(ax), abs(ay), abs(az))
        if ((largest == abs(ax) && ax < 0.0) ||
            (largest != abs(ax) && largest == abs(ay) && ay < 0.0) ||
            (largest != abs(ax) && largest != abs(ay) && az < 0.0)
        ) {
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
