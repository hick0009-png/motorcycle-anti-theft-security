package com.example.motorcycleantitheftsensor.protection

import com.example.motorcycleantitheftsensor.sensor.DefaultSensorCapabilityController
import com.example.motorcycleantitheftsensor.sensor.SensorCatalog
import com.example.motorcycleantitheftsensor.sensor.SensorDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurableSensorRuntimeIntegrationTest {

    private class FakeSensorCatalog(
        private val availableSources: Set<SensorSource>
    ) : SensorCatalog {
        override fun descriptors(): Map<SensorSource, SensorDescriptor> = SensorSource.entries.associateWith { descriptor(it) }
        override fun descriptor(source: SensorSource): SensorDescriptor = SensorDescriptor(
            source = source,
            androidType = 1,
            name = source.name,
            vendor = "Fake",
            reportingMode = 1,
            isWakeUp = false,
            minDelayUs = 1000,
            maxDelayUs = 200000,
            maximumRange = 100f,
            resolution = 0.01f,
            powerMa = 0.5f,
            isAvailable = availableSources.contains(source),
        )
        override fun isAvailable(source: SensorSource): Boolean = availableSources.contains(source)
    }

    private val policy = SensorConfigurationPolicy()

    @Test
    fun noPrimarySensorFailsArmEligibility() {
        val catalog = FakeSensorCatalog(setOf(SensorSource.ACCELEROMETER))
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = catalog,
        )

        val noPrimaryConfig = SensorSource.entries.fold(policy.forPreset(SensorPreset.BALANCED)) { cur, src ->
            policy.withSourceRole(cur, src, SensorRole.OFF)
        }

        controller.start(noPrimaryConfig) {}
        assertFalse("Arming must not be eligible when no primary is configured", controller.isArmEligible())
    }

    @Test
    fun availablePrimarySensorIsArmEligible() {
        val catalog = FakeSensorCatalog(setOf(SensorSource.ACCELEROMETER, SensorSource.AMBIENT_LIGHT))
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = catalog,
        )

        val balanced = policy.forPreset(SensorPreset.BALANCED)
        assertTrue(controller.isArmEligible())
    }

    @Test
    fun burstSampleIngressBoundedInPriorityChannel() {
        val priorityChannel = PriorityChannel<IncidentObservationBatch>(
            capacity = 100,
            priorityOf = { batch -> if (batch.primary.audioThreat != null) 10 else 0 }
        )

        // Offer 10,000 burst samples
        val sampleObs = SensorObservation(
            kind = SensorKind.VIBRATION,
            source = SensorSource.ACCELEROMETER,
            capability = SensorCapability.MOVEMENT,
            role = SensorRole.PRIMARY,
            generationId = 1L,
            unit = SensorUnit.METERS_PER_SECOND_SQUARED,
            eventElapsedMs = 100L,
            wallClockMs = 1000L,
            normalizedValue = 1.0,
            baselineDelta = 0.5,
            valid = true,
        )
        val batch = IncidentObservationBatch(primary = sampleObs)

        for (i in 0 until 10_000) {
            priorityChannel.trySend(batch)
        }

        // Bounded capacity must never exceed 100
        assertTrue("Queue size must be bounded at or below capacity", priorityChannel.size <= 100)
    }
}
