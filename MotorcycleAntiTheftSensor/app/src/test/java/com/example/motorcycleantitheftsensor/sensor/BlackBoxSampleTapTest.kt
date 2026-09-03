package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorSamplingProfile
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap is allowed to watch the detection path and nothing else.
 *
 * Both tests here are about what the black box must never cost. A recorder that changed one
 * observation would make every incident it recorded evidence of itself, and a recorder that
 * could throw the detection path would turn the act of writing down an alarm into the reason
 * the alarm never fired.
 */
class BlackBoxSampleTapTest {

    @Test
    fun theObservationsReachingDetectionAreIdenticalWithAndWithoutTheTap() {
        val untapped = mutableListOf<SensorObservation>()
        val tapped = mutableListOf<SensorObservation>()

        runWithTap(tap = null, collected = untapped)
        val seen = mutableListOf<RawSensorSample>()
        runWithTap(tap = { sample -> seen.add(sample) }, collected = tapped)

        assertEquals(untapped.comparable(), tapped.comparable())
        assertTrue(untapped.isNotEmpty())
        assertEquals(SAMPLE_COUNT, seen.size)
    }

    @Test
    fun aBlackBoxThatThrowsOnEverySampleStopsNothing() {
        val collected = mutableListOf<SensorObservation>()
        val reference = mutableListOf<SensorObservation>()

        runWithTap(tap = null, collected = reference)
        runWithTap(tap = { throw IllegalStateException("disk on fire") }, collected = collected)

        assertEquals(reference.comparable(), collected.comparable())
        assertTrue(collected.isNotEmpty())
    }

    /**
     * Everything about an observation except when it happened and which run produced it. Two
     * runs of the same controller cannot share a wall clock or a generation counter, and those
     * two fields are the only ones a tap could not touch even in principle.
     */
    private fun List<SensorObservation>.comparable(): List<SensorObservation> =
        map { observation -> observation.copy(wallClockMs = 0L, generationId = 0L) }

    private fun runWithTap(
        tap: ((RawSensorSample) -> Unit)?,
        collected: MutableList<SensorObservation>,
    ) {
        val adapters = mutableMapOf<SensorSource, RecordingAdapter>()
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeCatalog(),
            calibrationManager = SensorCalibrationManager(calibrationDurationMs = 0L),
            adapterFactory = { source -> RecordingAdapter(source).also { adapters[source] = it } },
            sampleTap = tap,
        )
        controller.start(SensorConfigurationPolicy().forPreset(SensorPreset.BALANCED)) { observation ->
            collected.add(observation)
        }

        val accelerometer = adapters.getValue(SensorSource.ACCELEROMETER)
        repeat(SAMPLE_COUNT) { index ->
            accelerometer.emit(
                RawSensorSample(
                    source = SensorSource.ACCELEROMETER,
                    timestampNs = index * 1_000_000L,
                    values = floatArrayOf(0.1f * index, 0f, 9.8f),
                    accuracy = 3,
                ),
            )
        }
        controller.stop()
    }

    private class RecordingAdapter(override val source: SensorSource) : SensorSourceAdapter {
        private var listener: ((RawSensorSample) -> Unit)? = null
        override var isRunning: Boolean = false
            private set

        override fun start(
            samplingProfile: SensorSamplingProfile,
            onSample: (RawSensorSample) -> Unit,
        ): Boolean {
            listener = onSample
            isRunning = true
            return true
        }

        override fun stop() {
            isRunning = false
        }

        fun emit(sample: RawSensorSample) {
            listener?.invoke(sample)
        }
    }

    private class FakeCatalog : SensorCatalog {
        override fun descriptors(): Map<SensorSource, SensorDescriptor> =
            SensorSource.entries.associateWith(::descriptor)

        override fun descriptor(source: SensorSource): SensorDescriptor = SensorDescriptor(
            source = source,
            androidType = 1,
            name = source.name,
            vendor = "Fake",
            reportingMode = 1,
            isWakeUp = false,
            minDelayUs = 1_000,
            maxDelayUs = 200_000,
            maximumRange = 100f,
            resolution = 0.01f,
            powerMa = 0.5f,
            isAvailable = true,
        )

        override fun isAvailable(source: SensorSource): Boolean = true
    }

    private companion object {
        const val SAMPLE_COUNT = 25
    }
}
