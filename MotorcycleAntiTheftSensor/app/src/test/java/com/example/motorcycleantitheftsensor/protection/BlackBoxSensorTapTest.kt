package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBoxSensorTapTest {

    private val tap = BlackBoxSensorTap()

    @Test
    fun aMinuteNothingHappenedInSaysSoRatherThanGuessing() {
        val summary = tap.drain()

        assertNull(summary.samples)
        assertNull(summary.accelerationMaxG)
        assertNull(summary.rotationMaxDeg)
        assertNull(summary.lux)
        assertNull(summary.accuracyMin)
    }

    @Test
    fun theStillPhoneReadsAsOneGravityAndTheShakenOneReadsAsMore() {
        repeat(9) { accelerometer(0f, 0f, 9.80665f) }
        accelerometer(0f, 0f, 3f * 9.80665f)

        val summary = tap.drain()

        assertEquals(10, summary.samples)
        assertEquals(3.0, summary.accelerationMaxG!!, 0.001)
        // Nine samples at one g and one at three: the peak alone would call this a theft, and
        // the mean alone would call a real jolt a quiet minute. The row carries both.
        assertEquals(1.34, summary.accelerationRmsG!!, 0.01)
    }

    @Test
    fun theTurnIsMeasuredFromWhereTheMinuteStarted() {
        rotationVector(0f, 0f, 0f, 1f)
        rotationVector(0f, 0f, 0.08716f, 0.99619f) // 10 degrees about z
        rotationVector(0f, 0f, 0.25882f, 0.96593f) // 30 degrees about z
        rotationVector(0f, 0f, 0.08716f, 0.99619f) // back to 10

        assertEquals(30.0, tap.drain().rotationMaxDeg!!, 0.5)
    }

    @Test
    fun eachMinuteMeasuresItsOwnTurnRatherThanTheWholeNight() {
        rotationVector(0f, 0f, 0f, 1f)
        rotationVector(0f, 0f, 0.25882f, 0.96593f)
        assertEquals(30.0, tap.drain().rotationMaxDeg!!, 0.5)

        // A phone left at the new angle has not moved again, and must not keep reporting the
        // turn that got it there — hours of slow drift would otherwise add up to an alarm.
        rotationVector(0f, 0f, 0.25882f, 0.96593f)
        rotationVector(0f, 0f, 0.25882f, 0.96593f)
        assertEquals(0.0, tap.drain().rotationMaxDeg!!, 0.5)
    }

    @Test
    fun theBetterRotationSourceTakesTheColumnAndStartsItsOwnBaseline() {
        // Both can be registered at once, and their quaternions are not the same quantity.
        // A reading from one measured against a baseline from the other is a turn that never
        // happened — the column would report the phone swinging on a night it sat still.
        tap.onSample(SensorSource.GEOMAGNETIC_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0f, 1f), 3)
        tap.onSample(SensorSource.GEOMAGNETIC_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0.70711f, 0.70711f), 3)
        tap.onSample(SensorSource.GAME_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0f, 1f), 3)
        tap.onSample(SensorSource.GAME_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0.08716f, 0.99619f), 3)

        // Ten degrees of game rotation, not the ninety the geomagnetic source had already
        // recorded and not the mixture of the two.
        assertEquals(10.0, tap.drain().rotationMaxDeg!!, 0.5)
    }

    @Test
    fun aWorseRotationSourceDoesNotTakeTheColumnBack() {
        tap.onSample(SensorSource.GAME_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0f, 1f), 3)
        tap.onSample(SensorSource.ROTATION_VECTOR, floatArrayOf(0f, 0f, 0.70711f, 0.70711f), 3)
        tap.onSample(SensorSource.GAME_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0.08716f, 0.99619f), 3)

        assertEquals(10.0, tap.drain().rotationMaxDeg!!, 0.5)
    }

    @Test
    fun theBrightestMomentIsWhatTheLightColumnKeeps() {
        light(0.5f)
        light(240f)
        light(0.5f)

        assertEquals(240.0, tap.drain().lux!!, 0.001)
    }

    @Test
    fun theWorstAccuracyOfTheMinuteIsTheOneReported() {
        accelerometer(0f, 0f, 9.8f, accuracy = 3)
        accelerometer(0f, 0f, 9.8f, accuracy = 1)
        accelerometer(0f, 0f, 9.8f, accuracy = 3)

        // A jump because the sensor lost its bearings and a jump because someone moved the
        // bike look identical afterwards. Only this column separates them.
        assertEquals(1, tap.drain().accuracyMin)
    }

    @Test
    fun aRateInsteadOfAnAngleIsLeftBlankRatherThanPutUnderTheWrongHeading() {
        tap.onSample(SensorSource.GYROSCOPE, floatArrayOf(1.5f, 0f, 0f), accuracy = 3)

        val summary = tap.drain()
        assertEquals(1, summary.samples)
        assertNull(summary.rotationMaxDeg)
    }

    @Test
    fun aTruncatedEventIsCountedButNotBelieved() {
        tap.onSample(SensorSource.ACCELEROMETER, floatArrayOf(1f), accuracy = 3)
        tap.onSample(SensorSource.ROTATION_VECTOR, floatArrayOf(0f, 0f), accuracy = 3)
        tap.onSample(SensorSource.AMBIENT_LIGHT, floatArrayOf(), accuracy = 3)

        val summary = tap.drain()
        assertEquals(3, summary.samples)
        assertNull(summary.accelerationMaxG)
        assertNull(summary.rotationMaxDeg)
        assertNull(summary.lux)
    }

    @Test
    fun drainingLeavesNothingBehindForTheNextMinuteToInherit() {
        accelerometer(0f, 0f, 30f)
        light(900f)
        tap.drain()

        val second = tap.drain()
        assertNull(second.samples)
        assertNull(second.accelerationMaxG)
        assertNull(second.lux)
    }

    @Test
    fun theSummaryRidesTheRowInTheColumnsItWasGiven() {
        accelerometer(0f, 0f, 9.80665f)
        light(12f)

        val line = BlackBoxCsv.format(
            BlackBoxRow(
                type = BlackBoxRowType.MINUTE,
                elapsedMs = 1_000L,
                wallMs = 2_000L,
                state = BlackBoxState.UNKNOWN,
                sensors = tap.drain(),
            ),
        )

        val parsed = BlackBoxCsv.parse(line)!!
        assertEquals(2, parsed.sensors.samples)
        assertEquals(1.0, parsed.sensors.accelerationMaxG!!, 0.001)
        assertEquals(12.0, parsed.sensors.lux!!, 0.001)
        assertTrue(parsed.sensors.rotationMaxDeg == null)
    }

    private fun accelerometer(x: Float, y: Float, z: Float, accuracy: Int = 3) {
        tap.onSample(SensorSource.ACCELEROMETER, floatArrayOf(x, y, z), accuracy)
    }

    private fun rotationVector(x: Float, y: Float, z: Float, w: Float) {
        tap.onSample(SensorSource.ROTATION_VECTOR, floatArrayOf(x, y, z, w), accuracy = 3)
    }

    private fun light(lux: Float) {
        tap.onSample(SensorSource.AMBIENT_LIGHT, floatArrayOf(lux), accuracy = 3)
    }
}
