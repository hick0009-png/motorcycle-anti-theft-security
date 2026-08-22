package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorCapability
import com.example.motorcycleantitheftsensor.protection.SensorConfigurationPolicy
import com.example.motorcycleantitheftsensor.protection.SensorHealthState
import com.example.motorcycleantitheftsensor.protection.SensorObservation
import com.example.motorcycleantitheftsensor.protection.SensorPreset
import com.example.motorcycleantitheftsensor.protection.SensorRole
import com.example.motorcycleantitheftsensor.protection.SensorSamplingProfile
import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCapabilityControllerTest {

    private class FakeSensorCatalog(
        private val availableSources: Set<SensorSource> = SensorSource.entries.toSet(),
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

    private class FakeAdapter(override val source: SensorSource) : SensorSourceAdapter {
        private var running = false
        var startCount: Int = 0
            private set
        var lastSamplingProfile: SensorSamplingProfile? = null
            private set
        override val isRunning: Boolean get() = running
        override fun start(samplingProfile: SensorSamplingProfile, onSample: (RawSensorSample) -> Unit): Boolean {
            running = true
            startCount += 1
            lastSamplingProfile = samplingProfile
            return true
        }
        override fun stop() {
            running = false
        }
    }

    private val policy = SensorConfigurationPolicy()

    @Test
    fun calibratingStateReportsAvailableNotHealthy() {
        val calibrationManager = SensorCalibrationManager(calibrationDurationMs = 10_000L)
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeSensorCatalog(),
            calibrationManager = calibrationManager,
            adapterFactory = { FakeAdapter(it) },
        )

        val config = policy.forPreset(SensorPreset.BALANCED)
        controller.start(config) {}

        // During calibration, health should be AVAILABLE, not HEALTHY
        val health = controller.getEffectiveHealth(SensorSource.ACCELEROMETER)
        assertEquals(SensorHealthState.AVAILABLE, health)
    }

    @Test
    fun modifyingOneSensorLeavesOtherActiveSensorsOperational() {
        val calibrationManager = SensorCalibrationManager(calibrationDurationMs = 0L)
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeSensorCatalog(),
            calibrationManager = calibrationManager,
            adapterFactory = { FakeAdapter(it) },
        )

        val initialConfig = policy.forPreset(SensorPreset.BALANCED)
        val deliveredObservations = mutableListOf<SensorObservation>()
        controller.start(initialConfig) { obs -> deliveredObservations.add(obs) }

        val accelGenInitial = controller.currentGenerationId(SensorSource.ACCELEROMETER)
        val lightGenInitial = controller.currentGenerationId(SensorSource.AMBIENT_LIGHT)

        // Modify only Light capability sensitivity
        val newConfig = policy.withGroupSensitivity(initialConfig, SensorCapability.LIGHT, 8)
        val diffResult = controller.applyConfigurationDiff(newConfig)

        assertTrue(diffResult.affectedCapabilities.contains(SensorCapability.LIGHT))

        val accelGenAfter = controller.currentGenerationId(SensorSource.ACCELEROMETER)
        val lightGenAfter = controller.currentGenerationId(SensorSource.AMBIENT_LIGHT)

        // Accelerometer generation should be unchanged, Light generation should advance
        assertEquals(accelGenInitial, accelGenAfter)
        assertNotEquals(lightGenInitial, lightGenAfter)
    }

    @Test
    fun samplingProfileChangeRestartsActiveSensorAdapters() {
        val adapterMap = java.util.concurrent.ConcurrentHashMap<SensorSource, FakeAdapter>()
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeSensorCatalog(),
            calibrationManager = SensorCalibrationManager(calibrationDurationMs = 0L),
            adapterFactory = { source ->
                FakeAdapter(source).also { adapterMap[source] = it }
            },
        )

        val initialConfig = policy.forPreset(SensorPreset.BALANCED)
        controller.start(initialConfig) {}

        val accelGenInitial = controller.currentGenerationId(SensorSource.ACCELEROMETER)
        assertEquals(SensorSamplingProfile.BALANCED, adapterMap[SensorSource.ACCELEROMETER]?.lastSamplingProfile)

        // Change sampling profile to RESPONSIVE
        val newConfig = initialConfig.copy(samplingProfile = SensorSamplingProfile.RESPONSIVE)
        val diffResult = controller.applyConfigurationDiff(newConfig)

        val accelGenAfter = controller.currentGenerationId(SensorSource.ACCELEROMETER)

        // Verify generation incremented and adapter restarted with RESPONSIVE profile
        assertNotEquals(accelGenInitial, accelGenAfter)
        assertEquals(SensorSamplingProfile.RESPONSIVE, adapterMap[SensorSource.ACCELEROMETER]?.lastSamplingProfile)
        assertTrue(diffResult.affectedCapabilities.contains(SensorCapability.MOVEMENT))
    }

    @Test
    fun sourceSamplingOverrideIsAppliedOnInitialStart() {
        val adapterMap = java.util.concurrent.ConcurrentHashMap<SensorSource, FakeAdapter>()
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeSensorCatalog(),
            calibrationManager = SensorCalibrationManager(calibrationDurationMs = 0L),
            adapterFactory = { source -> FakeAdapter(source).also { adapterMap[source] = it } },
        )
        val base = policy.forPreset(SensorPreset.BALANCED)
        val movement = base.capability(SensorCapability.MOVEMENT)
        val accelerometer = movement.source(SensorSource.ACCELEROMETER).copy(
            samplingProfileOverride = SensorSamplingProfile.RESPONSIVE,
        )
        val config = base.copy(
            capabilities = base.capabilities + (
                SensorCapability.MOVEMENT to movement.copy(
                    sources = movement.sources + (SensorSource.ACCELEROMETER to accelerometer),
                )
            ),
        )

        controller.start(config) {}

        assertEquals(
            SensorSamplingProfile.RESPONSIVE,
            adapterMap[SensorSource.ACCELEROMETER]?.lastSamplingProfile,
        )
    }

    @Test
    fun changingOneSourceSamplingOverrideRestartsOnlyThatSource() {
        val adapterMap = java.util.concurrent.ConcurrentHashMap<SensorSource, FakeAdapter>()
        val controller = DefaultSensorCapabilityController(
            sensorManager = null,
            catalog = FakeSensorCatalog(),
            calibrationManager = SensorCalibrationManager(calibrationDurationMs = 0L),
            adapterFactory = { source -> FakeAdapter(source).also { adapterMap[source] = it } },
        )
        val initial = policy.forPreset(SensorPreset.BALANCED)
        controller.start(initial) {}
        val accelerometerStarts = adapterMap.getValue(SensorSource.ACCELEROMETER).startCount
        val ambientLightStarts = adapterMap.getValue(SensorSource.AMBIENT_LIGHT).startCount
        val movement = initial.capability(SensorCapability.MOVEMENT)
        val accelerometer = movement.source(SensorSource.ACCELEROMETER).copy(
            samplingProfileOverride = SensorSamplingProfile.RESPONSIVE,
        )
        val changed = initial.copy(
            capabilities = initial.capabilities + (
                SensorCapability.MOVEMENT to movement.copy(
                    sources = movement.sources + (SensorSource.ACCELEROMETER to accelerometer),
                )
            ),
        )

        val result = controller.applyConfigurationDiff(changed)

        assertEquals(accelerometerStarts + 1, adapterMap.getValue(SensorSource.ACCELEROMETER).startCount)
        assertEquals(ambientLightStarts, adapterMap.getValue(SensorSource.AMBIENT_LIGHT).startCount)
        assertEquals(
            SensorSamplingProfile.RESPONSIVE,
            adapterMap.getValue(SensorSource.ACCELEROMETER).lastSamplingProfile,
        )
        assertTrue(result.affectedCapabilities.contains(SensorCapability.MOVEMENT))
    }
}
