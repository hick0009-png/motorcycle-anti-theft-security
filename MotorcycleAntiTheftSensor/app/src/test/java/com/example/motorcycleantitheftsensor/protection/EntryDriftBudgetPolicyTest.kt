package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.SensorAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a phone is allowed to claim about its own door watch, given what it measured.
 *
 * There is no list of phones that answers "does the door watch work here". The rate the
 * reported orientation walks at belongs to the chipset, and the only honest source is the
 * phone in the owner's hand. These proofs are about what the app does with that number —
 * including the two ways it could do harm: refusing a watch that would have worked, and
 * promising one that would have cried wolf all afternoon.
 */
class EntryDriftBudgetPolicyTest {

    private fun measurement(
        degPerHour: Double,
        measuredMs: Long = 30L * 60_000L,
        sourceLabel: String = EntryOrientationSource.GAME_ROTATION_VECTOR.label,
    ) = EntryDriftMeasurement(
        sourceLabel = sourceLabel,
        degPerHour = degPerHour,
        measuredMs = measuredMs,
        measuredAtWallMs = 1_788_000_000_000L,
    )

    @Test
    fun theMeasuredPhoneHoldsForDays() {
        // INE-LX2, measured for 6.8 undisturbed hours: 0.076 degrees an hour, which reaches
        // fifteen degrees in about eight days. Drift is not this phone's problem.
        val verdict = EntryDriftBudgetPolicy.verdict(
            measurement(degPerHour = 0.076),
            alertAngleDeg = 15,
            currentSource = EntryOrientationSource.GAME_ROTATION_VECTOR,
        )

        assertTrue(verdict is EntryDriftVerdict.Trustworthy)
        assertTrue((verdict as EntryDriftVerdict.Trustworthy).hoursToThreshold > 190.0)
    }

    @Test
    fun aPhoneThatCrossesInsideAWorkingDayIsToldInHours() {
        // Two degrees an hour reaches fifteen in seven and a half: fine overnight in theory,
        // spent by the time the owner gets home. The owner is owed the number, not a verdict.
        val verdict = EntryDriftBudgetPolicy.verdict(
            measurement(degPerHour = 2.0),
            alertAngleDeg = 15,
            currentSource = EntryOrientationSource.GAME_ROTATION_VECTOR,
        )

        assertTrue(verdict is EntryDriftVerdict.Limited)
        assertEquals(7, EntryDriftBudgetPolicy.trustedHours(verdict))
    }

    @Test
    fun aPhoneThatCannotCoverAnErrandIsCalledUnusable() {
        val verdict = EntryDriftBudgetPolicy.verdict(
            measurement(degPerHour = 20.0),
            alertAngleDeg = 15,
            currentSource = EntryOrientationSource.GAME_ROTATION_VECTOR,
        )

        assertTrue(verdict is EntryDriftVerdict.Unusable)
    }

    @Test
    fun aMeasurementOfAnotherSensorAnswersNothing() {
        // The sources do not drift alike — the game vector has no compass holding its heading
        // and the other two do. A number measured on one says nothing about another.
        val verdict = EntryDriftBudgetPolicy.verdict(
            measurement(degPerHour = 20.0, sourceLabel = EntryOrientationSource.ROTATION_VECTOR.label),
            alertAngleDeg = 15,
            currentSource = EntryOrientationSource.GAME_ROTATION_VECTOR,
        )

        assertEquals(EntryDriftVerdict.NotMeasured, verdict)
    }

    @Test
    fun aGlanceIsNotAMeasurement() {
        // Half a minute of a still phone turns ordinary sample noise into an alarming rate.
        val verdict = EntryDriftBudgetPolicy.verdict(
            measurement(degPerHour = 30.0, measuredMs = 30_000L),
            alertAngleDeg = 15,
            currentSource = EntryOrientationSource.GAME_ROTATION_VECTOR,
        )

        assertEquals(EntryDriftVerdict.NotMeasured, verdict)
    }

    @Test
    fun aPhoneThatNeverMeasuredKeepsTheWatchItHasToday() {
        // The regression this could cause. Every phone in the field measured nothing, and
        // treating silence as suspicion would take the door watch away from all of them.
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            fullInventory(),
            entryDrift = EntryDriftVerdict.NotMeasured,
        )

        assertEquals(ProfileDeviceSupport.Supported, support)
    }

    @Test
    fun aLimitedPhoneStaysSelectableAndSaysHowLong() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            fullInventory(),
            entryDrift = EntryDriftVerdict.Limited(hoursToThreshold = 7.5),
        )

        assertTrue(support is ProfileDeviceSupport.Degraded)
        assertTrue(support.selectable)
        assertEquals(ProfileSupportReason.DRIFT_LIMITS_SESSION, (support as ProfileDeviceSupport.Degraded).reason)
        assertEquals(7, support.trustedHours)
        assertTrue(PresentationTextCatalog.profileSupport(support)!!.contains("7 ชั่วโมง"))
    }

    @Test
    fun anUnusablePhoneIsRefusedRatherThanAllowedToCryWolf() {
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            fullInventory(),
            entryDrift = EntryDriftVerdict.Unusable(hoursToThreshold = 0.75),
        )

        assertTrue(support is ProfileDeviceSupport.Unsupported)
        assertEquals(false, support.selectable)
        assertNull(PresentationTextCatalog.profileSupport(ProfileDeviceSupport.Supported))
        assertTrue(PresentationTextCatalog.profileSupport(support)!!.isNotBlank())
    }

    @Test
    fun missingHardwareOutranksAnyMeasurement() {
        // A phone with nothing to measure an angle with is refused for that reason, not for a
        // drift number it could not have produced honestly.
        val support = ProfileDeviceSupportPolicy.support(
            ProtectionProfile.ENTRY,
            mapOf(
                SensorSource.ACCELEROMETER to SensorAvailability.AVAILABLE,
                SensorSource.AMBIENT_LIGHT to SensorAvailability.AVAILABLE,
            ),
            entryDrift = EntryDriftVerdict.Trustworthy(hoursToThreshold = 999.0),
        )

        assertTrue(support is ProfileDeviceSupport.Unsupported)
        assertEquals(ProfileSupportReason.NO_ANGLE_SENSOR, (support as ProfileDeviceSupport.Unsupported).reason)
    }

    @Test
    fun theRateIsThePeakOverTheHoursItTook() {
        // A phone that wandered to eight degrees and came back to two has an eight-degree
        // problem, not a two-degree one: eight is what would have raised the alert.
        val sampler = EntryDriftSampler(rowIntervalMs = 1_000L)
        sampler.onSample(0L, EntryQuaternion(1.0, 0.0, 0.0, 0.0))
        sampler.onSample(1_800_000L, twist(8.0))
        sampler.onSample(3_600_000L, twist(2.0))

        val measured = sampler.measurement(EntryOrientationSource.GAME_ROTATION_VECTOR.label, 1L)

        assertEquals(8.0, measured.degPerHour, 0.05)
        assertEquals(3_600_000L, measured.measuredMs)
        assertEquals(EntryOrientationSource.GAME_ROTATION_VECTOR.label, measured.sourceLabel)
    }

    private fun twist(degrees: Double) = EntryQuaternion(
        w = Math.cos(Math.toRadians(degrees / 2)),
        x = 0.0,
        y = 0.0,
        z = Math.sin(Math.toRadians(degrees / 2)),
    )

    private fun fullInventory(): Map<SensorSource, SensorAvailability> =
        SensorSource.entries.associateWith { SensorAvailability.AVAILABLE }
}
