package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Unit quaternion used by Entry Guard orientation math. Components follow the
 * Hamilton convention `(w, x, y, z)`. `q` and `-q` represent the same rotation.
 */
data class EntryQuaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    companion object {
        val IDENTITY = EntryQuaternion(1.0, 0.0, 0.0, 0.0)
    }
}

/** A direction in the device's own frame, as the phone would describe it to itself. */
data class EntryVector3(
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * Pure-domain door-angle mathematics for Entry Guard (spec section 4).
 *
 * The customer-facing door angle is always the absolute hinge-axis twist from the
 * calibrated closed position, clamped to 0-180 degrees. There is no compass heading
 * and no 0/360 wrap anywhere in this math; sign-equivalent quaternions (`q` and `-q`)
 * produce identical results.
 */
object EntryOrientationMath {

    private const val DEGENERATE_EPSILON = 1e-12

    /** Returns a unit-length copy of [q]. A zero-length quaternion maps to identity. */
    fun normalize(q: EntryQuaternion): EntryQuaternion {
        val length = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
        if (length < DEGENERATE_EPSILON) return EntryQuaternion.IDENTITY
        return EntryQuaternion(q.w / length, q.x / length, q.y / length, q.z / length)
    }

    /**
     * Which way is up, as the device sees it.
     *
     * Every rotation source here reports a world frame whose Z is the vertical, so the world
     * vertical carried back into the device frame says how the phone is being held — face up,
     * on edge, upside down in a cradle — without depending on which way it is facing. That
     * last part is the point: a game rotation vector has no compass, its yaw is arbitrary and
     * unrelated between sessions, so heading is not a thing this can honestly ask about.
     * Tilt is, and tilt is most of what changes when a phone is remounted.
     *
     * Sign-invariant, like everything else here: `q` and `-q` give the same vector.
     */
    fun deviceUpVector(q: EntryQuaternion): EntryVector3 {
        val n = normalize(q)
        return EntryVector3(
            x = 2.0 * (n.x * n.z - n.w * n.y),
            y = 2.0 * (n.y * n.z + n.w * n.x),
            z = 1.0 - 2.0 * (n.x * n.x + n.y * n.y),
        )
    }

    /** Angle between two device-frame directions, in degrees. Zero for a degenerate input. */
    fun angleBetweenDeg(a: EntryVector3, b: EntryVector3): Double {
        val lengthA = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val lengthB = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (lengthA < DEGENERATE_EPSILON || lengthB < DEGENERATE_EPSILON) return 0.0
        val dot = ((a.x * b.x + a.y * b.y + a.z * b.z) / (lengthA * lengthB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    /**
     * Canonicalizes double-cover sign so `q` and `-q` compare equal: forces `w >= 0`,
     * breaking ties on the first nonzero vector component.
     */
    fun canonicalizeSign(q: EntryQuaternion): EntryQuaternion {
        if (q.w > DEGENERATE_EPSILON || q.w < -DEGENERATE_EPSILON) {
            return if (q.w < 0.0) EntryQuaternion(-q.w, -q.x, -q.y, -q.z) else q
        }
        // w == 0: decide by the first nonzero vector component.
        val first = listOf(q.x, q.y, q.z).firstOrNull { it > DEGENERATE_EPSILON || it < -DEGENERATE_EPSILON }
        return if (first != null && first < 0.0) {
            EntryQuaternion(-q.w, -q.x, -q.y, -q.z)
        } else {
            q
        }
    }

    /** Hamilton product `a (x) b`. Inputs need not be unit length. */
    fun multiply(a: EntryQuaternion, b: EntryQuaternion): EntryQuaternion = EntryQuaternion(
        w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
        x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    )

    private fun conjugate(q: EntryQuaternion) = EntryQuaternion(q.w, -q.x, -q.y, -q.z)

    /**
     * Relative rotation that carries [baseline] onto [current]: `baseline^-1 (x) current`.
     * Both inputs are normalized first; sign-equivalent inputs yield identical output.
     */
    fun relativeRotation(baseline: EntryQuaternion, current: EntryQuaternion): EntryQuaternion =
        canonicalizeSign(multiply(conjugate(normalize(baseline)), normalize(current)))

    /**
     * Total geodesic rotation angle of [q] in degrees, always within [0, 180].
     * Used for still-check movement detection and commissioning peak tracking where
     * no hinge axis is known yet.
     */
    fun totalRotationDeg(q: EntryQuaternion): Double {
        val n = normalize(q)
        val vecLength = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        val angleDeg = Math.toDegrees(2.0 * atan2(vecLength, n.w))
        return abs(angleDeg).coerceAtMost(180.0)
    }

    /**
     * Signed hinge-axis twist of [qRel] around unit [axis] in degrees, in (-180, 180].
     * Positive follows the axis right-hand rule. Degenerate projections (rotation by
     * ~180 degrees about an axis perpendicular to [axis]) resolve to 0 twist.
     */
    fun twistAroundAxisDeg(qRel: EntryQuaternion, axis: DoubleArray): Double {
        val q = normalize(qRel)
        val d = q.x * axis[0] + q.y * axis[1] + q.z * axis[2]
        if (q.w * q.w + d * d < DEGENERATE_EPSILON) return 0.0
        val radians = 2.0 * atan2(d, q.w)
        return Math.toDegrees(radians)
    }

    /**
     * Customer door angle: absolute hinge-axis twist clamped to [0, 180] degrees.
     */
    fun doorAngleDeltaDeg(qRel: EntryQuaternion, axis: DoubleArray): Double =
        abs(twistAroundAxisDeg(qRel, axis)).coerceIn(0.0, 180.0)

    /**
     * Cross-axis swing/residual in degrees: the geodesic angle of the swing quaternion
     * left after removing the hinge-axis twist. Used by the detection policy's residual
     * gate before any angle threshold is applied.
     */
    fun swingResidualDeg(qRel: EntryQuaternion, axis: DoubleArray): Double {
        val q = normalize(qRel)
        val d = q.x * axis[0] + q.y * axis[1] + q.z * axis[2]
        val twist = if (q.w * q.w + d * d < DEGENERATE_EPSILON) {
            EntryQuaternion.IDENTITY
        } else {
            val norm = sqrt(q.w * q.w + d * d)
            EntryQuaternion(q.w / norm, axis[0] * d / norm, axis[1] * d / norm, axis[2] * d / norm)
        }
        val swing = canonicalizeSign(multiply(q, conjugate(twist)))
        val vecLength = sqrt(swing.x * swing.x + swing.y * swing.y + swing.z * swing.z)
        val angleDeg = Math.toDegrees(2.0 * atan2(vecLength, swing.w))
        return if (angleDeg < 0.0) -angleDeg else angleDeg
    }
}
