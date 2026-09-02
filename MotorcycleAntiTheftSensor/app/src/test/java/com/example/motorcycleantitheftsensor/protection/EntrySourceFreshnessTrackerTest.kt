package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The door watch's first gate, which until now could never close.
 *
 * `fresh = true` was hardcoded at the one place that builds a sample, and the accuracy
 * callback beside it was empty, so a source that had stopped delivering or had declared
 * itself unreliable was still read as a door angle.
 */
class EntrySourceFreshnessTrackerTest {

    @Test
    fun samplesArrivingAtRateAreBelieved() {
        val tracker = EntrySourceFreshnessTracker()
        var t = 0L
        repeat(500) {
            t += 20L
            assertTrue(tracker.onSample(t))
        }
    }

    @Test
    fun aSourceThatWentQuietIsNotBelieved() {
        // What a vendor freezing the app looks like from in here.
        val tracker = EntrySourceFreshnessTracker()
        assertTrue(tracker.onSample(1_000L))
        assertFalse(tracker.onSample(1_000L + 10_001L))
    }

    @Test
    fun theFirstSampleAfterRegistrationIsBelieved() {
        // There is no gap to measure yet, and refusing it would announce a lost source on
        // every arm.
        val tracker = EntrySourceFreshnessTracker()
        assertTrue(tracker.onSample(9_999_999L))
    }

    @Test
    fun deliveryResumingIsBelievedAgain() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onSample(1_000L)
        assertFalse(tracker.onSample(30_000L))
        assertTrue(tracker.onSample(30_020L))
    }

    @Test
    fun aMomentaryComplaintWhileTheFusionSettlesIsNotALostSource() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onAccuracy(EntrySourceAccuracy.UNRELIABLE, 100L)
        assertTrue(tracker.onSample(120L))
        tracker.onAccuracy(EntrySourceAccuracy.HIGH, 500L)
        assertTrue(tracker.onSample(520L))
    }

    @Test
    fun aComplaintThatPersistsIsALostSource() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onAccuracy(EntrySourceAccuracy.UNRELIABLE, 100L)
        assertTrue(tracker.onSample(1_000L))
        assertFalse(tracker.onSample(3_100L))
    }

    @Test
    fun noContactIsAComplaintTooAndRecoveryClearsIt() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onAccuracy(EntrySourceAccuracy.NO_CONTACT, 0L)
        assertFalse(tracker.onSample(5_000L))
        tracker.onAccuracy(EntrySourceAccuracy.MEDIUM, 6_000L)
        assertTrue(tracker.onSample(6_010L))
    }

    @Test
    fun sayingNothingAboutAccuracyIsNotAComplaint() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onAccuracy(EntrySourceAccuracy.UNKNOWN, 0L)
        assertTrue(tracker.onSample(60_000L))
    }

    @Test
    fun reRegisteringForgetsTheOldGap() {
        val tracker = EntrySourceFreshnessTracker()
        tracker.onSample(1_000L)
        tracker.reset()
        assertTrue(tracker.onSample(600_000L))
    }
}
