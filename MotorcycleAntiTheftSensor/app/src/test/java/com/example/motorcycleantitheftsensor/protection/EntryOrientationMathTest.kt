package com.example.motorcycleantitheftsensor.protection

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-domain proofs for the Entry Guard door-angle math (spec section 4):
 * quaternion normalization, sign equivalence, relative rotation, hinge-axis twist
 * decomposition, and the 0-180 door angle with no heading wrap.
 */
class EntryOrientationMathTest {

    @Test
    fun deviceUpVectorReadsTheTiltAndIgnoresTheDoubleCoverSign() {
        val flat = EntryOrientationMath.deviceUpVector(EntryQuaternion.IDENTITY)
        assertEquals(0.0, flat.x, 1e-9)
        assertEquals(0.0, flat.y, 1e-9)
        assertEquals(1.0, flat.z, 1e-9)

        // Tipped a quarter turn about X: up now points along the device's own +Y.
        val half = Math.toRadians(90.0) / 2.0
        val onEdge = EntryQuaternion(kotlin.math.cos(half), kotlin.math.sin(half), 0.0, 0.0)
        val tilted = EntryOrientationMath.deviceUpVector(onEdge)
        assertEquals(90.0, EntryOrientationMath.angleBetweenDeg(flat, tilted), 1e-6)

        val negated = EntryQuaternion(-onEdge.w, -onEdge.x, -onEdge.y, -onEdge.z)
        val fromNegated = EntryOrientationMath.deviceUpVector(negated)
        assertEquals(0.0, EntryOrientationMath.angleBetweenDeg(tilted, fromNegated), 1e-9)
    }


    private val epsilon = 1e-6

    private fun quatFromAxisAngleDeg(
        ax: Double,
        ay: Double,
        az: Double,
        degrees: Double,
    ): EntryQuaternion {
        val norm = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(
            w = cos(half),
            x = ax / norm * sin(half),
            y = ay / norm * sin(half),
            z = az / norm * sin(half),
        )
    }

    @Test
    fun identityRelativeRotationReadsZeroDegrees() {
        val q = quatFromAxisAngleDeg(0.0, 0.0, 1.0, 37.0)
        val rel = EntryOrientationMath.relativeRotation(baseline = q, current = q)
        assertEquals(0.0, EntryOrientationMath.doorAngleDeltaDeg(rel, Z_AXIS), epsilon)
    }

    @Test
    fun ninetyDegreeTwistAroundZReadsNinetyDegrees() {
        val baseline = EntryQuaternion.IDENTITY
        val current = quatFromAxisAngleDeg(0.0, 0.0, 1.0, 90.0)
        val rel = EntryOrientationMath.relativeRotation(baseline, current)
        assertEquals(90.0, EntryOrientationMath.doorAngleDeltaDeg(rel, Z_AXIS), 1e-4)
    }

    @Test
    fun signEquivalentQuaternionsProduceSameAngle() {
        val baseline = quatFromAxisAngleDeg(1.0, 2.0, 3.0, 20.0)
        val current = quatFromAxisAngleDeg(1.0, 2.0, 3.0, 65.0)
        val relA = EntryOrientationMath.relativeRotation(baseline, current)
        val relB = EntryOrientationMath.relativeRotation(
            baseline,
            EntryQuaternion(
                -current.w,
                -current.x,
                -current.y,
                -current.z,
            ),
        )
        val angleA = EntryOrientationMath.doorAngleDeltaDeg(relA, Z_AXIS)
        val angleB = EntryOrientationMath.doorAngleDeltaDeg(relB, Z_AXIS)
        assertEquals(angleA, angleB, epsilon)
    }

    @Test
    fun unnormalizedInputIsNormalizedFirst() {
        val unit = quatFromAxisAngleDeg(0.0, 1.0, 0.0, 45.0)
        val scaled = EntryQuaternion(unit.w * 7.0, unit.x * 7.0, unit.y * 7.0, unit.z * 7.0)
        val relUnit = EntryOrientationMath.relativeRotation(EntryQuaternion.IDENTITY, unit)
        val relScaled = EntryOrientationMath.relativeRotation(EntryQuaternion.IDENTITY, scaled)
        assertEquals(
            EntryOrientationMath.doorAngleDeltaDeg(relUnit, Y_AXIS),
            EntryOrientationMath.doorAngleDeltaDeg(relScaled, Y_AXIS),
            epsilon,
        )
    }

    @Test
    fun swingResidualSeparatesFromTwist() {
        // Compose a pure 90-degree hinge twist around Z with a 30-degree swing around X.
        val twist = quatFromAxisAngleDeg(0.0, 0.0, 1.0, 90.0)
        val swing = quatFromAxisAngleDeg(1.0, 0.0, 0.0, 30.0)
        val combined = EntryOrientationMath.multiply(twist, swing)
        val rel = EntryOrientationMath.canonicalizeSign(combined)
        assertEquals(90.0, EntryOrientationMath.doorAngleDeltaDeg(rel, Z_AXIS), 1e-4)
        assertEquals(30.0, EntryOrientationMath.swingResidualDeg(rel, Z_AXIS), 1e-4)
    }

    @Test
    fun angleAlwaysWithinZeroToOneEighty() {
        var degrees = 0.0
        while (degrees <= 179.0) {
            val rel = EntryOrientationMath.relativeRotation(
                EntryQuaternion.IDENTITY,
                quatFromAxisAngleDeg(0.0, 0.0, 1.0, degrees),
            )
            val angle = EntryOrientationMath.doorAngleDeltaDeg(rel, Z_AXIS)
            assertTrue("angle $degrees produced $angle", angle >= 0.0 && angle <= 180.0)
            assertTrue(
                "angle $degrees produced $angle",
                abs(angle - degrees) < 1e-4 || abs(angle - (360.0 - degrees)) < 1e-4,
            )
            degrees += 1.0
        }
    }

    @Test
    fun oppositeDirectionTwistKeepsSameDoorAngleMagnitude() {
        val open = quatFromAxisAngleDeg(0.0, 0.0, 1.0, 25.0)
        val close = quatFromAxisAngleDeg(0.0, 0.0, 1.0, -25.0)
        val relOpen = EntryOrientationMath.relativeRotation(EntryQuaternion.IDENTITY, open)
        val relClose = EntryOrientationMath.relativeRotation(EntryQuaternion.IDENTITY, close)
        assertEquals(
            EntryOrientationMath.doorAngleDeltaDeg(relOpen, Z_AXIS),
            EntryOrientationMath.doorAngleDeltaDeg(relClose, Z_AXIS),
            epsilon,
        )
    }

    companion object {
        private val X_AXIS = doubleArrayOf(1.0, 0.0, 0.0)
        private val Y_AXIS = doubleArrayOf(0.0, 1.0, 0.0)
        private val Z_AXIS = doubleArrayOf(0.0, 0.0, 1.0)
    }
}
