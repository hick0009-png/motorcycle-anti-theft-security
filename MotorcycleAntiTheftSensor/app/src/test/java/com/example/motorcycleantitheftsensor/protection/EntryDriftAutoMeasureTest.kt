package com.example.motorcycleantitheftsensor.protection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Measuring drift out of the door watch's own stream, while it is on watch.
 *
 * The value of doing it here rather than on a table is that it needs nothing from the owner,
 * and the risk of doing it here is the same thing: nobody is watching, so a measurement that
 * is wrong is saved and believed. These pin the two ways this stream can produce a number
 * that was never measured — a source that stopped answering, and a session that was not one.
 */
class EntryDriftAutoMeasureTest {

    private class RecordingStore : EntryDriftMeasurementStore {
        var saved: EntryDriftMeasurement? = null
        var saves = 0
        override fun load(): EntryDriftMeasurement? = saved
        override fun save(measurement: EntryDriftMeasurement) {
            saves += 1
            saved = measurement
        }
        override fun clear() {
            saved = null
        }
    }

    private fun yaw(degrees: Double): EntryQuaternion {
        val half = Math.toRadians(degrees) / 2.0
        return EntryQuaternion(w = cos(half), x = 0.0, y = 0.0, z = sin(half))
    }

    private fun measure(store: RecordingStore) = EntryDriftAutoMeasure(
        samples = { emptyFlow() },
        store = store,
        currentSourceLabel = { "rotation-vector" },
        wallClockMs = { 1_000L },
        dispatcher = Dispatchers.Unconfined,
    )

    private suspend fun feed(
        subject: EntryDriftAutoMeasure,
        fromMs: Long,
        toMs: Long,
        degPerHour: Double,
        fresh: Boolean = true,
        stepMs: Long = 1_000L,
    ) {
        var t = fromMs
        while (t <= toMs) {
            subject.onSample(
                EntryOrientationSample(
                    timestampMs = t,
                    quaternion = yaw(degPerHour * (t / 3_600_000.0)),
                    fresh = fresh,
                ),
            )
            t += stepMs
        }
    }

    @Test
    fun anArmedNightMeasuresThePhoneWithoutTheOwnerDoingAnything() = runTest {
        val store = RecordingStore()
        val subject = measure(store)

        feed(subject, 0L, 3L * 3_600_000L, degPerHour = 0.03)

        val saved = assertNotNull("the night produced a measurement", store.saved).let { store.saved!! }
        assertEquals("rotation-vector", saved.sourceLabel)
        assertEquals(0.03, saved.degPerHour, 0.005)
        assertTrue(saved.measuredMs > 2L * 3_600_000L)
    }

    @Test
    fun nothingIsSavedBeforeTheMeasurementIsWorthAnything() {
        runTest {
            val store = RecordingStore()
            val subject = measure(store)

            feed(subject, 0L, EntryDriftBudgetPolicy.MIN_USEFUL_MEASUREMENT_MS - 60_000L, 0.03)

            assertNull(store.saved)
        }
    }

    @Test
    fun aSourceThatStoppedAnsweringIsNotAPhoneThatNeverMoved() {
        // A stale rotation vector repeats its last reading. Read as data that is exactly what
        // a flawless phone looks like, and it would be saved as the best measurement this
        // device ever took — on hours in which nothing was measured at all.
        runTest {
            val store = RecordingStore()
            val subject = measure(store)

            feed(subject, 0L, 2L * 3_600_000L, degPerHour = 0.03, fresh = false)

            assertNull(store.saved)
        }
    }

    @Test
    fun aStaleStretchDoesNotLendItsHoursToTheFreshOneAfterIt() {
        runTest {
            val store = RecordingStore()
            val subject = measure(store)

            feed(subject, 0L, 3L * 3_600_000L, degPerHour = 0.0, fresh = false)
            feed(subject, 3L * 3_600_000L, 4L * 3_600_000L, degPerHour = 0.03)

            val saved = store.saved
            assertNotNull(saved)
            requireNotNull(saved)
            assertTrue("only the fresh hour counts", saved.measuredMs <= 3_600_000L + 20_000L)
        }
    }

    @Test
    fun twoNightsAreTwoSessionsRatherThanOneLongOne() {
        // Between them the watch was disarmed and the phone was somewhere else entirely.
        // Carried on as one stretch, the gap becomes hours of evidence nobody collected.
        runTest {
            val store = RecordingStore()
            val subject = measure(store)

            feed(subject, 0L, 2L * 3_600_000L, degPerHour = 0.03)
            val firstNight = store.saved
            feed(subject, 20L * 3_600_000L, 21L * 3_600_000L, degPerHour = 0.03)

            assertNotNull(firstNight)
            requireNotNull(firstNight)
            assertTrue(firstNight.measuredMs > 1L * 3_600_000L)
            // The second night is shorter, so the store keeps the first; what matters is that
            // the second never claimed the eighteen hours the phone spent off watch.
            assertEquals(1, subject.restarts)
        }
    }
}
