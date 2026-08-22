package com.example.motorcycleantitheftsensor.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorConfigurationPolicyTest {

    private val policy = SensorConfigurationPolicy()

    @Test
    fun balancedPresetMatchesApprovedSourceRoles() {
        val config = policy.forPreset(SensorPreset.BALANCED, nowMs = 100L)

        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.SIGNIFICANT_MOTION).role)
        assertEquals(SensorRole.PRIMARY, config.source(SensorSource.ACCELEROMETER).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.LINEAR_ACCELERATION).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.ROTATION_VECTOR).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GAME_ROTATION_VECTOR).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.MAGNETIC_FIELD).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.GEOMAGNETIC_ROTATION_VECTOR).role)
        assertEquals(SensorRole.PRIMARY, config.source(SensorSource.AMBIENT_LIGHT).role)
        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.PROXIMITY).role)
        assertEquals(SensorSamplingProfile.BALANCED, config.samplingProfile)
    }

    @Test
    fun batterySaverPresetMatchesRoles() {
        val config = policy.forPreset(SensorPreset.BATTERY_SAVER, nowMs = 100L)

        assertEquals(SensorRole.SUPPORTING, config.source(SensorSource.SIGNIFICANT_MOTION).role)
        assertEquals(SensorRole.PRIMARY, config.source(SensorSource.ACCELEROMETER).role)
        assertEquals(SensorRole.OFF, config.source(SensorSource.LINEAR_ACCELERATION).role)
        assertEquals(SensorRole.OFF, config.source(SensorSource.GYROSCOPE).role)
        assertEquals(SensorRole.PRIMARY, config.source(SensorSource.AMBIENT_LIGHT).role)
        assertEquals(SensorRole.OFF, config.source(SensorSource.PROXIMITY).role)
        assertEquals(SensorSamplingProfile.BATTERY_SAVER, config.samplingProfile)
    }

    @Test
    fun maximumProtectionPresetMatchesRoles() {
        val config = policy.forPreset(SensorPreset.MAXIMUM_PROTECTION, nowMs = 100L)

        SensorSource.entries.forEach { source ->
            assertEquals(SensorRole.PRIMARY, config.source(source).role)
        }
        assertEquals(SensorSamplingProfile.RESPONSIVE, config.samplingProfile)
    }

    @Test
    fun sensitivityMappingIsMonotonicAndNeverDisablesDebounce() {
        val low = policy.parameters(SensorSource.ACCELEROMETER, 1)
        val high = policy.parameters(SensorSource.ACCELEROMETER, 10)

        assertNotNull(low.threshold)
        assertNotNull(high.threshold)
        assertTrue("Lower sensitivity must require higher threshold delta", low.threshold!! > high.threshold!!)
        assertTrue(high.debounceMs >= SensorConfigurationPolicy.MIN_DEBOUNCE_MS)
        assertTrue(low.debounceMs <= SensorConfigurationPolicy.MAX_DEBOUNCE_MS)
    }

    @Test
    fun deviationDisplaysCustomButRetainsBasePresetForReset() {
        val balanced = policy.forPreset(SensorPreset.BALANCED, nowMs = 100L)
        val changed = policy.withSourceRole(balanced, SensorSource.GYROSCOPE, SensorRole.PRIMARY, 200L)

        assertEquals(SensorPreset.BALANCED, changed.basePreset)
        assertEquals(SensorPresetDisplay.CUSTOM, policy.displayPreset(changed))
        assertEquals(
            policy.forPreset(SensorPreset.BALANCED, 300L).capability(SensorCapability.ROTATION),
            policy.resetCapability(changed, SensorCapability.ROTATION, 300L).capability(SensorCapability.ROTATION),
        )
    }

    @Test
    fun noPrimaryConfigurationIsSaveableWhileDisarmedButArmIneligible() {
        val noPrimary = SensorSource.entries.fold(policy.forPreset(SensorPreset.BALANCED, 100L)) { current, source ->
            policy.withSourceRole(current, source, SensorRole.OFF, 200L)
        }

        assertTrue(policy.validateForSave(noPrimary) is SensorConfigurationValidation.Valid)
        assertEquals(SensorArmEligibility.NoConfiguredPrimary, policy.armEligibility(noPrimary))
    }

    @Test
    fun invalidThresholdOrDebounceFailsValidation() {
        val valid = policy.forPreset(SensorPreset.BALANCED, 100L)
        val invalidCap = valid.capability(SensorCapability.MOVEMENT).copy(
            sources = mapOf(
                SensorSource.ACCELEROMETER to SensorSourceConfiguration(
                    source = SensorSource.ACCELEROMETER,
                    role = SensorRole.PRIMARY,
                    thresholdOverride = -1.0,
                    debounceOverrideMs = 50L // below 250
                )
            )
        )
        val invalidConfig = valid.copy(
            capabilities = valid.capabilities + (SensorCapability.MOVEMENT to invalidCap)
        )

        val validation = policy.validateForSave(invalidConfig)
        assertTrue(validation is SensorConfigurationValidation.Invalid)
    }
}
